// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.staging

import org.tatrman.charon.core.ImmutableBlobStore
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*

/** Compare-and-create on a conditional-write-capable SeaweedFS/S3 gateway; never temp-copy-overwrite. */
class S3BlobStore(
    private val client: S3Client,
    private val bucket: String,
    private val pageSize: Int = 1000,
    private val closeCredentials: () -> Unit = {},
) : ImmutableBlobStore {
    init {
        require(pageSize in 1..1000)
    }

    override fun putIfAbsent(
        key: String,
        bytes: ByteArray,
    ): Boolean {
        val request =
            PutObjectRequest
                .builder()
                .bucket(bucket)
                .key(key)
                .ifNoneMatch("*")
                .build()
        repeat(3) { attempt ->
            try {
                client.putObject(request, RequestBody.fromBytes(bytes))
                return true
            } catch (e: S3Exception) {
                if (e.statusCode() == 412) {
                    check(get(key).contentEquals(bytes)) { "a staged output cannot change within one pinned run" }
                    return false
                }
                // A concurrent delete may conflict with compare-and-create. Retry the SAME conditional put.
                if (e.statusCode() != 409 || attempt == 2) throw e
            }
        }
        error("conditional put exhausted")
    }

    override fun get(key: String): ByteArray =
        client
            .getObjectAsBytes(
                GetObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(key)
                    .build(),
            ).asByteArray()

    override fun delete(key: String) {
        client.deleteObject(
            DeleteObjectRequest
                .builder()
                .bucket(bucket)
                .key(key)
                .build(),
        )
    }

    override fun deletePrefix(prefix: String) {
        require(prefix.isNotEmpty() && prefix.endsWith('/')) { "invalid deletion prefix" }
        var token: String? = null
        do {
            val page =
                client.listObjectsV2(
                    ListObjectsV2Request
                        .builder()
                        .bucket(
                            bucket,
                        ).prefix(prefix)
                        .maxKeys(pageSize)
                        .continuationToken(token)
                        .build(),
                )
            check(page.contents().all { it.key().startsWith(prefix) }) { "S3 listing escaped its prefix" }
            if (page.contents().isNotEmpty()) {
                val result =
                    client.deleteObjects(
                        DeleteObjectsRequest
                            .builder()
                            .bucket(bucket)
                            .delete(
                                Delete
                                    .builder()
                                    .objects(
                                        page.contents().map { ObjectIdentifier.builder().key(it.key()).build() },
                                    ).build(),
                            ).build(),
                    )
                check(result.errors().isEmpty()) { "S3 did not delete every staged blob; retention will retry" }
            }
            val next = page.nextContinuationToken()
            check(!page.isTruncated || (!next.isNullOrBlank() && next != token)) { "S3 listing did not advance" }
            token = if (page.isTruncated) next else null
        } while (token != null)
    }

    /** Key deletion must reclaim the object, rather than leave hidden historical versions behind. */
    fun verifyUnversionedBucket() {
        val versioning = client.getBucketVersioning(GetBucketVersioningRequest.builder().bucket(bucket).build())
        check(versioning.statusAsString().isNullOrEmpty()) { "staging requires a dedicated unversioned S3 bucket" }
    }

    /**
     * Detect ignored conditional headers before accepting run output. The one-byte capability marker is
     * intentionally permanent per namespace, so concurrent/restarted boots cannot delete each other's probe.
     */
    fun verifyConditionalWrites(prefix: String) {
        val key = "$prefix/_capabilities/conditional-create-v1"
        putIfAbsent(key, byteArrayOf(1))
        val refused =
            try {
                client.putObject(
                    PutObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(key)
                        .ifNoneMatch("*")
                        .build(),
                    RequestBody.fromBytes(byteArrayOf(1)),
                )
                false
            } catch (e: S3Exception) {
                if (e.statusCode() != 412) throw e
                true
            }
        check(refused && get(key).contentEquals(byteArrayOf(1))) {
            "staging S3 gateway does not enforce conditional writes"
        }
    }

    override fun close() {
        try {
            client.close()
        } finally {
            closeCredentials()
        }
    }
}
