// SPDX-License-Identifier: Apache-2.0
package org.tatrman.charon.endpoints

import org.tatrman.charon.core.Integrity
import org.tatrman.charon.core.LocationKind
import org.tatrman.charon.core.Source
import org.tatrman.charon.core.Target
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.pojo.Schema
import org.slf4j.LoggerFactory
import org.tatrman.transfer.v1.DescribeResult
import org.tatrman.transfer.v1.EvictResult
import org.tatrman.transfer.v1.SeaweedBlob
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.Tag
import software.amazon.awssdk.services.s3.model.Tagging
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

private val log = LoggerFactory.getLogger(SeaweedEndpoint::class.java)

/**
 * S3 endpoint for the SeaweedFS S3 gateway (charon/architecture.md §2).
 *
 * Atomic write strategy: **temp-key + copy + delete** (contracts §5
 * "no-partial-write" invariant; architecture §5 last paragraph). From the
 * consumer's perspective the move is atomic — until [commit] is called, the
 * real key never exists; on a mid-stream failure the temp key is the only
 * thing to clean up via [discard].
 *
 * Object metadata carries the `charon-schema-fp` (hex fingerprint) so
 * [describe] can return the schema fingerprint without re-parsing the bytes.
 */
class SeaweedEndpoint(
    private val client: S3Client,
    /** A parent allocator used by both [open] (the source's reader)
     *  and [begin] (the target's owned per-batch roots + the
     *  commit-time writer's destination). Both source and target
     *  of a move must share a root allocator (Arrow's
     *  `TransferPair` requirement). The default is a fresh
     *  `RootAllocator` per endpoint instance — production code
     *  wires the same parent to both endpoints in a move, and
     *  tests pass the same parent to the executor's source and
     *  target. */
    parentAllocator: org.apache.arrow.memory.RootAllocator? = null,
) : Source,
    Target {
    private val parentAllocator: org.apache.arrow.memory.RootAllocator =
        parentAllocator ?: org.apache.arrow.memory
            .RootAllocator()
    private var currentLocation: SeaweedBlob? = null

    // --- Source ---

    override fun open(): ArrowStreamReader? {
        val loc = currentLocation ?: error("open() called before setLocation")
        val req =
            GetObjectRequest
                .builder()
                .bucket(loc.bucket)
                .key(loc.key)
                .build()
        val resp =
            try {
                client.getObject(req)
            } catch (e: NoSuchKeyException) {
                return null
            }
        // The reader allocates under the shared parent allocator and
        // is closed by the pipe's `use {}`, which frees its buffers
        // back to the parent. We deliberately do NOT create a per-move
        // child here: a child object would have to be closed after the
        // reader, but the source has no discard hook, so it would leak
        // one allocator per move (review-006 R8.6). The parent is the
        // root the target's owned roots also live under, so
        // `TransferPair` still works.
        return try {
            ArrowStreamReader(resp, parentAllocator)
        } catch (e: Throwable) {
            // If reader construction fails (OOM, etc.) the open S3 response
            // stream would otherwise leak — the pipe never gets a reader to
            // close via `use {}`.
            resp.close()
            throw e
        }
    }

    override fun kind(): LocationKind = LocationKind.SEAWEED

    override fun ref(): String = currentLocation?.let { "${it.bucket}/${it.key}" } ?: "<unset>"

    // --- Target (three-phase write) ---

    override fun begin(schema: Schema): Any {
        val loc = currentLocation ?: error("begin() called before setLocation")
        // Atomic write strategy: **temp-key + copy + delete** (no
        // partial write — the real key only appears after a successful
        // `copyObject`; a mid-move failure deletes the temp key via
        // [discard]). review-006 R2: the earlier per-batch *multipart*
        // attempt produced a malformed Arrow stream (each part was a
        // self-contained schema+batch+EOS stream, so the concatenation
        // read back as zero batches). Per architecture §10, the P1 path
        // is a single-shot PUT of one coherent IPC stream; true
        // bounded-memory streaming is the Stage 1.4 hardening.
        return S3WriteReceipt(
            realBucket = loc.bucket,
            realKey = loc.key,
            tempKey = "${loc.key}.tmp.${UUID.randomUUID()}",
            schema = schema,
            retentionTag = loc.retentionTag,
            // Per-move child allocator (under the same parent
            // as the source's reader — so TransferPair
            // works). Closed by closeAll() at commit/discard.
            allocator =
                parentAllocator
                    .newChildAllocator("seaweed-write-${UUID.randomUUID()}", 0, Long.MAX_VALUE),
        )
    }

    override fun writeBatch(
        receipt: Any,
        root: VectorSchemaRoot,
    ) {
        // Materialise the batch into an owned `VectorSchemaRoot` so
        // the endpoint's buffered batches don't all point to the
        // reader's reused root (review-006 B1). The reader's
        // `loadNextBatch()` reloads the same root in place, so a
        // stored reference would have rowCount == 0 by the time
        // `commit()` runs. The fix is to deep-copy via
        // `TransferPair` into a fresh root per call. Memory is
        // O(total bytes) — review-006 R2 (multipart upload) replaces
        // this with a streaming path for >~100 MiB moves.
        if (root.rowCount == 0) return
        val r = receipt as S3WriteReceipt
        val owned =
            org.apache.arrow.vector.VectorSchemaRoot
                .create(r.schema, r.allocator)
        try {
            for (i in 0 until r.schema.fields.size) {
                val srcVec = root.getVector(i)
                val dstVec = owned.getVector(i)
                dstVec.reAlloc()
                srcVec.makeTransferPair(dstVec).transfer()
            }
            owned.rowCount = root.rowCount
            r.pendingBatches.add(owned)
        } catch (e: Exception) {
            owned.close()
            throw e
        }
    }

    override fun commit(receipt: Any): Any {
        val r = receipt as S3WriteReceipt
        try {
            // One coherent Arrow IPC stream (schema + N record-batch
            // messages + a single EOS) — the only correct shape for a
            // reader. Memory is O(total bytes) at P1; Stage 1.4 replaces
            // this with a bounded-memory streamed upload.
            val bytes = serializeBatchesToIpcStream(r.schema, r.pendingBatches, r.allocator)
            // Upload to the temp key with the schema fingerprint in
            // user-metadata; copy to the real key; delete the temp.
            client.putObject(
                PutObjectRequest
                    .builder()
                    .bucket(r.realBucket)
                    .key(r.tempKey)
                    .contentType("application/vnd.apache.arrow.stream")
                    .metadata(mapOf(SCHEMA_FP_METADATA_KEY to r.fingerprint()))
                    .build(),
                RequestBody.fromInputStream(ByteArrayInputStream(bytes), bytes.size.toLong()),
            )
            client.copyObject(
                CopyObjectRequest
                    .builder()
                    .sourceBucket(r.realBucket)
                    .sourceKey(r.tempKey)
                    .destinationBucket(r.realBucket)
                    .destinationKey(r.realKey)
                    .build(),
            )
            client.deleteObject(
                DeleteObjectRequest
                    .builder()
                    .bucket(r.realBucket)
                    .key(r.tempKey)
                    .build(),
            )
            // Retention-tag object tags (lifecycle rules in the GitOps repo
            // act on these; local MinIO ignores tags).
            val tag = r.retentionTag
            if (!tag.isNullOrBlank()) {
                client.putObjectTagging(
                    PutObjectTaggingRequest
                        .builder()
                        .bucket(r.realBucket)
                        .key(r.realKey)
                        .tagging(
                            Tagging
                                .builder()
                                .tagSet(
                                    Tag
                                        .builder()
                                        .key("retention")
                                        .value(tag)
                                        .build(),
                                ).build(),
                        ).build(),
                )
            }
        } finally {
            r.closeAll()
            r.allocator.close()
        }
        return CommitReceipt(key = "${r.realBucket}/${r.realKey}")
    }

    override fun discard(receipt: Any) {
        val r = receipt as? S3WriteReceipt ?: return
        // Delete the temp key — the real key is never visible because
        // it only appears after the `copyObject` in [commit]. If the
        // temp was never written (failure before PUT), the delete is a
        // harmless no-op.
        try {
            client.deleteObject(
                DeleteObjectRequest
                    .builder()
                    .bucket(r.realBucket)
                    .key(r.tempKey)
                    .build(),
            )
        } catch (e: S3Exception) {
            log.debug("discard: temp key {} cleanup failed: {}", r.tempKey, e.message)
        }
        r.closeAll()
        r.allocator.close()
    }

    // --- Standalone ops ---

    fun describe(blob: SeaweedBlob): DescribeResult {
        currentLocation = blob
        return try {
            val head: HeadObjectResponse =
                client.headObject(
                    HeadObjectRequest
                        .builder()
                        .bucket(blob.bucket)
                        .key(blob.key)
                        .build(),
                )
            val fp = head.metadata()?.get(SCHEMA_FP_METADATA_KEY).orEmpty()
            DescribeResult
                .newBuilder()
                .setExists(true)
                .setSchemaFingerprint(fp)
                .setRowCount(-1L)
                .setRowCountExact(false)
                .setSizeBytes(head.contentLength())
                .build()
        } catch (e: NoSuchKeyException) {
            DescribeResult.newBuilder().setExists(false).build()
        } catch (e: S3Exception) {
            DescribeResult.newBuilder().setExists(false).build()
        }
    }

    fun evict(blob: SeaweedBlob): EvictResult {
        currentLocation = blob
        // A key ending in '/' is a run-scoped staging PREFIX (FQ-6) — evict every object under it. S3 has
        // no directories, so an exact-key delete of a '<prefix>/' would be a silent no-op; the run-scoped
        // reclaim MUST enumerate the prefix and delete each object. A plain key evicts the single object.
        return if (blob.key.endsWith("/")) {
            evictPrefix(blob.bucket, blob.key)
        } else {
            evictObject(blob.bucket, blob.key)
        }
    }

    private fun evictObject(
        bucket: String,
        key: String,
    ): EvictResult {
        val existed =
            try {
                client.headObject(
                    HeadObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(key)
                        .build(),
                )
                true
            } catch (e: NoSuchKeyException) {
                false
            } catch (e: S3Exception) {
                false
            }
        try {
            client.deleteObject(
                DeleteObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(key)
                    .build(),
            )
        } catch (e: S3Exception) {
            // Even on delete error, surface the prior-existence state.
        }
        return EvictResult.newBuilder().setExisted(existed).build()
    }

    /**
     * Evict every object under a run-scoped staging prefix (FQ-6). Lists the prefix (paginated) and deletes
     * each object; `existed` is true iff at least one object was found. Idempotent — re-evicting an
     * already-reclaimed prefix lists nothing and reports `existed=false`. Best-effort like the single-object
     * path: a list/delete error is swallowed (the S3 `shallow` retention rule or a later sweep reclaims).
     */
    private fun evictPrefix(
        bucket: String,
        prefix: String,
    ): EvictResult {
        var existed = false
        var continuationToken: String? = null
        do {
            val builder = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix)
            if (continuationToken != null) builder.continuationToken(continuationToken)
            val resp =
                try {
                    client.listObjectsV2(builder.build())
                } catch (e: S3Exception) {
                    return EvictResult.newBuilder().setExisted(existed).build()
                }
            for (obj in resp.contents()) {
                existed = true
                try {
                    client.deleteObject(
                        DeleteObjectRequest
                            .builder()
                            .bucket(bucket)
                            .key(obj.key())
                            .build(),
                    )
                } catch (e: S3Exception) {
                    // best-effort per object — a missed delete is reclaimed by the retention rule.
                }
            }
            continuationToken = if (resp.isTruncated == true) resp.nextContinuationToken() else null
        } while (continuationToken != null)
        return EvictResult.newBuilder().setExisted(existed).build()
    }

    fun setLocation(loc: SeaweedBlob) {
        currentLocation = loc
    }

    companion object {
        const val SCHEMA_FP_METADATA_KEY = "charon-schema-fp"

        /**
         * Build a single Arrow IPC stream from the schema + N pending
         * batches. The schema message comes first; the batches follow in
         * order. Empty `batches` produces a schema-only stream — the
         * cross-check fixture (IntegritySpec) uses this to assert the
         * fingerprint is identical to the Python `_schema_fingerprint`
         * algorithm.
         *
         * Implementation: build a single [VectorSchemaRoot], iterate the
         * pending batches, and `writeBatch()` per batch. The root's
         * underlying vectors are reallocated per batch (the row count
         * may vary); the writer emits one record-batch message per
         * `writeBatch()` call, and the resulting IPC stream is valid
         * (single schema message + N record-batch messages + EOS).
         *
         * The vector data copy is done via [org.apache.arrow.vector.util.TransferPair]:
         * the source vector's buffers are transferred into the writer's
         * root vector, with the source zero-lengthed. This is zero-copy
         * at the buffer level and is the canonical Arrow Java pattern.
         */
        internal fun serializeBatchesToIpcStream(
            schema: Schema,
            batches: List<VectorSchemaRoot>,
            // Allocator for the writer's destination root. Must share
            // a root with the source [batches] (Arrow's
            // `TransferPair` requirement). The caller passes the
            // same allocator that the batches were allocated from.
            alloc: org.apache.arrow.memory.BufferAllocator,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            VectorSchemaRoot.create(schema, alloc).use { root ->
                ArrowStreamWriter(root, null, out).use { writer ->
                    writer.start()
                    for (batch in batches) {
                        if (batch.rowCount == 0) continue
                        // Re-allocate the root to match the batch's
                        // row count, then transfer the buffers.
                        for (fieldIdx in 0 until schema.fields.size) {
                            val srcVec = batch.getVector(fieldIdx)
                            val dstVec = root.getVector(fieldIdx)
                            // The `VectorSchemaRoot.create` constructor
                            // sized the destination for the *first* batch's
                            // row count; subsequent batches may be larger.
                            // `reAlloc()` grows in place; if the new size
                            // is smaller, Arrow keeps the larger buffer
                            // and `setRowCount` trims the visible range.
                            dstVec.reAlloc()
                            val pair = srcVec.makeTransferPair(dstVec)
                            pair.transfer()
                        }
                        root.rowCount = batch.rowCount
                        writer.writeBatch()
                    }
                    writer.end()
                }
            }
            return out.toByteArray()
        }
    }
}

/** Internal write-state — the receipt returned by [SeaweedEndpoint.begin]
 *  and threaded through `writeBatch` / `commit` / `discard`.
 *
 *  Atomic-write shape (temp-key + copy + delete): [begin] generates
 *  [tempKey]; [commit] serialises one coherent IPC stream, PUTs it to
 *  [tempKey], copies it to [realKey], deletes [tempKey]; [discard]
 *  deletes [tempKey] (the real key is never written until the copy).
 *
 *  The [allocator] is a per-move child of the endpoint's allocator;
 *  it backs the [pendingBatches] roots that [writeBatch] materialises
 *  from the reader's reused root (review-006 B1 / R1.2). [discard] and
 *  [commit]'s `finally` close every owned root + the allocator. */
internal data class S3WriteReceipt(
    val realBucket: String,
    val realKey: String,
    val tempKey: String,
    val schema: Schema,
    val retentionTag: String?,
    val allocator: org.apache.arrow.memory.BufferAllocator,
    val pendingBatches: MutableList<VectorSchemaRoot> = mutableListOf(),
) {
    fun fingerprint(): String = Integrity.fingerprint(schema)

    fun closeAll() {
        pendingBatches.forEach { it.close() }
        pendingBatches.clear()
    }
}

internal data class CommitReceipt(
    val key: String,
)
