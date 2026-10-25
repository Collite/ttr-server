// SPDX-License-Identifier: Apache-2.0
package org.tatrman.charon.core

import org.tatrman.charon.endpoints.SeaweedEndpoint
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import org.tatrman.transfer.v1.DbTable
import org.tatrman.transfer.v1.Location
import org.tatrman.transfer.v1.MoveOptions
import org.tatrman.transfer.v1.SeaweedBlob
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.CopyObjectResponse
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest
import software.amazon.awssdk.services.s3.model.PutObjectTaggingResponse
import software.amazon.awssdk.services.s3.model.S3Object
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * PL-P3.S1.T3 — the FROZEN contract tests. These pin the transplanted Charon API's behaviour as the
 * platform's ④ contract: once green, treat them as the movement spec (do not loosen without a conformance
 * decision — PL-P3's thesis is "movement becomes Charon's without changing a result byte"). The four S1
 * tests (Materialize / byte-faithful Stage-Copy / Evict-idempotent / named-conn-fail) are joined by the
 * S2 run-scoped prefix Evict (3b, FQ-6) — the run-staging reclaim the lifecycle depends on.
 *
 * Component tier: a **Testcontainers Postgres** (postgres:16-alpine, the platform services' image)
 * is the real query source; a **stateful in-memory S3** ([InMemoryS3]) is the object-store staging tier —
 * the "local files" of the plan's task note (the suite deliberately keeps no live SeaweedFS/MinIO; the
 * fake round-trips PUT→GET/HEAD/COPY/DELETE so materialize-then-read-back and no-partial-write are real).
 * Written from the transplanted API's ACTUAL shape (materialize/copy/evict over Seaweed/DbTable Locations).
 */
class CharonContractTest :
    StringSpec({

        val pg = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
        val provider = HikariConnectionProvider()
        lateinit var warehouse: ConnectionHandle

        beforeSpec {
            pg.start()
            warehouse =
                ConnectionHandle(
                    id = "warehouse",
                    dialect = DbDialect.POSTGRES,
                    jdbcUrl = pg.jdbcUrl,
                    username = pg.username,
                    password = pg.password,
                    allow = AllowList(read = true, write = true, schemas = setOf("public")),
                    poolMax = 2,
                )
            provider.open(warehouse).use { conn ->
                conn.createStatement().use { st ->
                    st.execute("CREATE TABLE orders (id BIGINT, region TEXT)")
                    st.execute("INSERT INTO orders VALUES (1,'north'),(2,'south'),(3,'east')")
                }
            }
        }

        afterSpec {
            provider.close()
            pg.stop()
        }

        // --- 1. Materialize: a query result lands as Arrow IPC at the staging ref -------------------

        "Materialize: query result lands as Arrow IPC at the staging ref" {
            val s3 = InMemoryS3()
            val executor =
                CharonMoveExecutor(
                    s3Client = s3.client,
                    redisOps = mockk(relaxed = true),
                    connectionRegistry = ConnectionRegistry.of(listOf(warehouse)),
                    dbProvider = provider,
                )
            val src = dbLoc("warehouse", "public", "orders")
            val staging = seaweedLoc("staging", "orders.arrow")

            val result = executor.materialize(Plan(MoveRpc.MATERIALIZE, src, staging, MoveOptions.getDefaultInstance()))

            result.shouldBeInstanceOf<Either.Right<*>>()
            val moved = (result as Either.Right).value
            withClue("the resolved target is the staging blob") {
                moved.target.hasSeaweed() shouldBe true
                moved.target.seaweed.key shouldBe "orders.arrow"
            }
            withClue("a schema fingerprint (sha-256 hex) is always populated") {
                moved.schemaFingerprint.length shouldBe 64
            }

            RootAllocator().use { alloc ->
                val stored = s3.store["staging/orders.arrow"]
                withClue("bytes actually landed at the staging ref") { stored shouldNotBe null }
                val (fp, rowCount, rows) = decodeIpc(stored!!.bytes, alloc)
                withClue("the staged bytes parse as a valid Arrow IPC stream of the query result") {
                    rowCount shouldBe 3
                    rows.map { it[1] }.toSet() shouldBe setOf("north", "south", "east")
                }
                withClue("the fingerprint of the staged schema matches the reported one") {
                    fp shouldBe moved.schemaFingerprint
                }
                withClue("the fingerprint also rode into the object metadata (Describe reads it back)") {
                    stored.metadata[SeaweedEndpoint.SCHEMA_FP_METADATA_KEY] shouldBe fp
                }
            }
        }

        // --- 2. Stage/Copy: byte-faithful transport (sha256 in == out) -------------------------------

        "Stage/Copy: byte-faithful transport (sha256 in == out)" {
            val s3 = InMemoryS3()
            val executor = CharonMoveExecutor(s3.client, mockk<org.tatrman.charon.endpoints.RedisOps>(relaxed = true))

            RootAllocator().use { alloc ->
                val (_, inBytes) = buildIpc(alloc)
                // Seed the source object exactly as an upstream producer would have staged it.
                s3.store["hall/in.arrow"] = InMemoryS3.Stored(inBytes, emptyMap())
                val inDigest = contentDigest(inBytes, alloc)

                val result =
                    executor.copy(
                        Plan(
                            MoveRpc.COPY,
                            seaweedLoc("hall", "in.arrow"),
                            seaweedLoc("hall", "out.arrow"),
                            MoveOptions.getDefaultInstance(),
                        ),
                    )

                result.shouldBeInstanceOf<Either.Right<*>>()
                val outBytes = s3.store["hall/out.arrow"]?.bytes
                withClue("the moved object exists at the target ref") { outBytes shouldNotBe null }
                // "byte-faithful" = movement is invisible to results: the transported stream carries the
                // same schema (fingerprint) and the same rows, so a content sha-256 is identical in==out.
                // (Charon re-serialises the IPC stream, so raw envelope bytes may differ; the RESULT does not.)
                withClue("the transported content digest is identical (schema fingerprint + all rows)") {
                    contentDigest(outBytes!!, alloc) shouldBe inDigest
                }
            }
        }

        // --- 3. Evict: target gone, idempotent on re-call --------------------------------------------

        "Evict: target gone, idempotent on re-call" {
            val s3 = InMemoryS3()
            val executor = CharonMoveExecutor(s3.client, mockk<org.tatrman.charon.endpoints.RedisOps>(relaxed = true))
            s3.store["hall/evict-me.arrow"] = InMemoryS3.Stored(byteArrayOf(1, 2, 3), emptyMap())
            val loc = Plan(MoveRpc.EVICT, seaweedLoc("hall", "evict-me.arrow"), null, MoveOptions.getDefaultInstance())

            val first = executor.evict(loc)
            first.shouldBeInstanceOf<Either.Right<*>>()
            withClue("first evict reports it existed and removes it") {
                (first as Either.Right).value.existed shouldBe true
                s3.store.containsKey("hall/evict-me.arrow") shouldBe false
            }

            val second = executor.evict(loc)
            second.shouldBeInstanceOf<Either.Right<*>>()
            withClue("re-evicting a gone target is idempotent: existed=false, not an error") {
                (second as Either.Right).value.existed shouldBe false
            }
        }

        // --- 3b. Evict (run-scoped): a '<prefix>/' key reclaims every staged object under it (FQ-6) ----

        "Evict (run-scoped): a '<prefix>/' key removes every object under the prefix, idempotent" {
            val s3 = InMemoryS3()
            val executor = CharonMoveExecutor(s3.client, mockk<org.tatrman.charon.endpoints.RedisOps>(relaxed = true))
            // Two staged objects for run-1, one for a DIFFERENT run that must survive the run-1 reclaim.
            s3.store["run-staging/run-1/extract.arrow"] = InMemoryS3.Stored(byteArrayOf(1), emptyMap())
            s3.store["run-staging/run-1/enrich.arrow"] = InMemoryS3.Stored(byteArrayOf(2), emptyMap())
            s3.store["run-staging/run-2/other.arrow"] = InMemoryS3.Stored(byteArrayOf(3), emptyMap())
            val loc = Plan(MoveRpc.EVICT, seaweedLoc("run-staging", "run-1/"), null, MoveOptions.getDefaultInstance())

            val first = executor.evict(loc)
            first.shouldBeInstanceOf<Either.Right<*>>()
            withClue("the run's whole staging prefix is reclaimed and it reports it existed") {
                (first as Either.Right).value.existed shouldBe true
                s3.store.keys.none { it.startsWith("run-staging/run-1/") } shouldBe true
            }
            withClue("a different run's staging is untouched (the prefix scopes the delete)") {
                s3.store.containsKey("run-staging/run-2/other.arrow") shouldBe true
            }

            val second = executor.evict(loc)
            withClue("re-evicting a gone prefix is idempotent: existed=false, not an error") {
                (second as Either.Right).value.existed shouldBe false
            }
        }

        // --- 4. named-connection resolution failure → structured error, no partial writes -----------

        "named-connection resolution failure → structured error, no partial writes" {
            val s3 = InMemoryS3()
            val executor =
                CharonMoveExecutor(
                    s3Client = s3.client,
                    redisOps = mockk(relaxed = true),
                    // registry has NO 'ghost-warehouse' handle → resolution must fail before any I/O.
                    connectionRegistry = ConnectionRegistry.of(listOf(warehouse)),
                    dbProvider = provider,
                )
            val src = dbLoc("ghost-warehouse", "public", "orders")
            val staging = seaweedLoc("staging", "should-not-appear.arrow")

            val result = executor.materialize(Plan(MoveRpc.MATERIALIZE, src, staging, MoveOptions.getDefaultInstance()))

            result.shouldBeInstanceOf<Either.Left<*>>()
            val err = (result as Either.Left).value
            withClue("the failure is the typed UnknownConnectionId, carrying the offending id") {
                err.shouldBeInstanceOf<CharonError.UnknownConnectionId>()
                err.connectionId shouldBe "ghost-warehouse"
            }
            withClue("no partial write: the staging tier was never touched") {
                s3.store.keys.none { it.startsWith("staging/") } shouldBe true
            }
        }
    })

// --- helpers -------------------------------------------------------------------------------------

private fun dbLoc(
    connectionId: String,
    schema: String,
    table: String,
): Location =
    Location
        .newBuilder()
        .setDbTable(
            DbTable
                .newBuilder()
                .setConnectionId(connectionId)
                .setSchema(schema)
                .setTable(table)
                .build(),
        ).build()

private fun seaweedLoc(
    bucket: String,
    key: String,
): Location =
    Location
        .newBuilder()
        .setSeaweed(
            SeaweedBlob
                .newBuilder()
                .setBucket(bucket)
                .setKey(key)
                .build(),
        ).build()

/** A 3-row {id: int64, region: utf8} Arrow IPC stream — the canonical staged fixture. */
private fun buildIpc(alloc: RootAllocator): Pair<Schema, ByteArray> {
    val schema =
        Schema(
            listOf(
                Field("id", FieldType.notNullable(ArrowType.Int(64, true)), null),
                Field("region", FieldType.notNullable(ArrowType.Utf8()), null),
            ),
        )
    VectorSchemaRoot.create(schema, alloc).use { root ->
        val id = root.getVector("id") as BigIntVector
        val region = root.getVector("region") as VarCharVector
        id.allocateNew(3)
        region.allocateNew()
        listOf("north", "south", "east").forEachIndexed { i, r ->
            id.set(i, (i + 1).toLong())
            region.setSafe(i, r.toByteArray())
        }
        id.valueCount = 3
        region.valueCount = 3
        root.rowCount = 3
        return schema to SeaweedEndpoint.serializeBatchesToIpcStream(schema, listOf(root), alloc)
    }
}

/** Decode an Arrow IPC stream to (schema fingerprint, row count, rows-as-strings). */
private fun decodeIpc(
    bytes: ByteArray,
    alloc: RootAllocator,
): Triple<String, Int, List<List<String>>> {
    ArrowStreamReader(ByteArrayInputStream(bytes), alloc).use { reader ->
        val fp = Integrity.fingerprint(reader.vectorSchemaRoot.schema)
        val rows = mutableListOf<List<String>>()
        while (reader.loadNextBatch()) {
            val root = reader.vectorSchemaRoot
            for (r in 0 until root.rowCount) {
                rows.add(root.fieldVectors.map { it.getObject(r)?.toString() ?: "null" })
            }
        }
        return Triple(fp, rows.size, rows)
    }
}

/** A content digest over the transported RESULT (schema fingerprint + all rows) — the byte-faithful
 *  invariant that survives Charon's re-serialisation (raw envelope bytes may vary; the result does not). */
private fun contentDigest(
    bytes: ByteArray,
    alloc: RootAllocator,
): String {
    val (fp, n, rows) = decodeIpc(bytes, alloc)
    val canonical = "$fp|$n|" + rows.joinToString(";") { it.joinToString(",") }
    return MessageDigest
        .getInstance("SHA-256")
        .digest(canonical.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

/**
 * A stateful in-memory S3 stand-in for the staging tier — the object-store side of the contract tests
 * without a live SeaweedFS/MinIO. Round-trips the six calls Charon's SeaweedEndpoint makes
 * (PUT/GET/HEAD/COPY/DELETE + tagging), keyed by "bucket/key".
 */
private class InMemoryS3 {
    data class Stored(
        val bytes: ByteArray,
        val metadata: Map<String, String>,
    )

    val store = ConcurrentHashMap<String, Stored>()
    val client: S3Client = mockk()

    private fun key(
        bucket: String,
        k: String,
    ): String = "$bucket/$k"

    init {
        every { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } answers {
            val req = firstArg<PutObjectRequest>()
            val sink = ByteArrayOutputStream()
            secondArg<RequestBody>().contentStreamProvider().newStream().use { it.copyTo(sink) }
            store[key(req.bucket(), req.key())] = Stored(sink.toByteArray(), req.metadata() ?: emptyMap())
            PutObjectResponse.builder().build()
        }
        every { client.getObject(any<GetObjectRequest>()) } answers {
            val req = firstArg<GetObjectRequest>()
            val s =
                store[key(req.bucket(), req.key())] ?: throw NoSuchKeyException.builder().message("no such key").build()
            val resp =
                GetObjectResponse
                    .builder()
                    .metadata(s.metadata)
                    .contentLength(s.bytes.size.toLong())
                    .build()
            ResponseInputStream(resp, ByteArrayInputStream(s.bytes))
        }
        every { client.headObject(any<HeadObjectRequest>()) } answers {
            val req = firstArg<HeadObjectRequest>()
            val s =
                store[key(req.bucket(), req.key())] ?: throw NoSuchKeyException.builder().message("no such key").build()
            HeadObjectResponse
                .builder()
                .metadata(s.metadata)
                .contentLength(s.bytes.size.toLong())
                .build()
        }
        every { client.copyObject(any<CopyObjectRequest>()) } answers {
            val req = firstArg<CopyObjectRequest>()
            val s =
                store[key(req.sourceBucket(), req.sourceKey())]
                    ?: throw NoSuchKeyException.builder().message("copy source missing").build()
            store[key(req.destinationBucket(), req.destinationKey())] = s
            CopyObjectResponse.builder().build()
        }
        every { client.deleteObject(any<DeleteObjectRequest>()) } answers {
            val req = firstArg<DeleteObjectRequest>()
            store.remove(key(req.bucket(), req.key()))
            DeleteObjectResponse.builder().build()
        }
        every { client.listObjectsV2(any<ListObjectsV2Request>()) } answers {
            val req = firstArg<ListObjectsV2Request>()
            val bucketRoot = "${req.bucket()}/"
            // Return the object keys (bucket-relative) under `bucket/prefix`. Single page (isTruncated=false)
            // — the staging prefixes the FQ-6 sweep evicts are far below the 1000-key page size.
            val matched =
                store.keys
                    .filter { it.startsWith(bucketRoot + req.prefix()) }
                    .map { S3Object.builder().key(it.removePrefix(bucketRoot)).build() }
            ListObjectsV2Response
                .builder()
                .contents(matched)
                .isTruncated(false)
                .build()
        }
        every { client.putObjectTagging(any<PutObjectTaggingRequest>()) } returns
            PutObjectTaggingResponse.builder().build()
    }
}
