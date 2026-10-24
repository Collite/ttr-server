// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.staging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.MutableClock
import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.config.RetentionConfig
import org.tatrman.automation.config.StagingConfig
import org.tatrman.automation.ledger.*
import org.tatrman.automation.runner.Table
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import io.lettuce.core.RedisClient

/** Always part of CI's component tier. No opt-in switch; these are real Redis + SeaweedFS containers. */
class RemoteStagingSpec :
    StringSpec({
        val redis =
            // ECR's shared runner data quota refused this pull. The preferred public mirror serves
            // the same official 8.2.2-alpine image (verified index digest in the AU progress record).
            GenericContainer(DockerImageName.parse("mirror.gcr.io/library/redis:8.2.2-alpine"))
                .withExposedPorts(6379)
        val seaweed =
            GenericContainer(DockerImageName.parse("chrislusf/seaweedfs:4.48"))
                .withExposedPorts(8333)
                .withCommand(
                    "server",
                    "-ip=127.0.0.1",
                    "-ip.bind=0.0.0.0",
                    "-dir=/tmp",
                    "-s3",
                    "-filer",
                    "-master.volumeSizeLimitMB=32",
                    "-volume.max=4",
                    "-master.telemetry=false",
                ).waitingFor(
                    Wait
                        .forHttp("/")
                        .forPort(8333)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(90)),
                )
        lateinit var redisUrl: String
        lateinit var s3Url: String
        beforeSpec {
            redis.start()
            seaweed.start()
            redisUrl = "redis://${redis.host}:${redis.getMappedPort(6379)}/0"
            s3Url = "http://${seaweed.host}:${seaweed.getMappedPort(8333)}"
            S3Client
                .builder()
                .endpointOverride(URI(s3Url))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(AnonymousCredentialsProvider.create())
                .build()
                .use {
                    it.createBucket(CreateBucketRequest.builder().bucket("au-staging").build())
                }
            LedgerTestDb.ready()
        }
        afterSpec {
            seaweed.stop()
            redis.stop()
        }
        beforeEach { LedgerTestDb.reset() }

        fun config(
            kind: String,
            prefix: String = "au-test/${UUID.randomUUID()}",
        ) = StagingConfig(
            kind,
            null,
            if (kind == "redis") redisUrl else s3Url,
            bucket = if (kind == "seaweed") "au-staging" else null,
            prefix = prefix,
            anonymous = true,
        )

        for (kind in listOf("redis", "seaweed")) {
            "$kind concurrent puts preserve one immutable byte sequence" {
                val c = config(kind)
                val stores = (1..4).map { StagingStores.open(c) }
                val id = UUID.randomUUID()
                val path = "runs/$id/i.rows.arrow"
                val bytes = ByteArray(1000) { (it % 251).toByte() }
                val pool = Executors.newFixedThreadPool(8)
                try {
                    val results =
                        (0..15)
                            .map { n -> pool.submit<Boolean> { stores[n % stores.size].putIfAbsent(path, bytes) } }
                            .map { it.get(20, TimeUnit.SECONDS) }
                    results.count { it } shouldBe 1
                    stores.first().get(path).toList() shouldBe bytes.toList()
                    shouldThrow<IllegalStateException> { stores.last().putIfAbsent(path, byteArrayOf(0)) }
                    stores.last().get(path).toList() shouldBe bytes.toList()
                    val raced = "runs/$id/race.rows.arrow"
                    val winners =
                        (0..15)
                            .map { n ->
                                pool.submit<Int?> {
                                    try {
                                        if (stores[n % stores.size].putIfAbsent(
                                                raced,
                                                byteArrayOf(n.toByte()),
                                            )
                                        ) {
                                            n
                                        } else {
                                            null
                                        }
                                    } catch (
                                        _: IllegalStateException,
                                    ) {
                                        null
                                    }
                                }
                            }.mapNotNull { it.get(20, TimeUnit.SECONDS) }
                    winners.size shouldBe 1
                    stores.first().get(raced).toList() shouldBe listOf(winners.single().toByte())
                    stores.first().deleteRun(id)
                } finally {
                    pool.shutdownNow()
                    stores.forEach { it.close() }
                }
            }

            "$kind Arrow survives client restart and per-run cleanup preserves sibling namespaces" {
                val c = config(kind)
                val id = UUID.randomUUID()
                val sibling = UUID.randomUUID()
                val table = Table(listOf(ManifestColumn("id", "int")), listOf(listOf(42L)))
                lateinit var output: StagedOutput
                StagingStores.open(c).use { store ->
                    output = Staging(store) {}.put(id, "i", "rows", table)
                    store.putIfAbsent("runs/$id/orphan.arrow", byteArrayOf(2))
                    store.putIfAbsent("runs/$sibling/keep.arrow", byteArrayOf(3))
                }
                StagingStores.open(c).use { store ->
                    Staging(store) {}.read(output).rows shouldBe table.rows
                    store.deleteRun(id)
                    store.deleteRun(id)
                    shouldThrow<Exception> { store.get(output.output.path!!) }
                    store.get("runs/$sibling/keep.arrow").toList() shouldBe listOf<Byte>(3)
                    store.deleteRun(sibling)
                }
            }

            "$kind grace and abandoned retention release real blobs and collect late orphan puts" {
                val c = config(kind)
                val clock = MutableClock(Instant.parse("2026-10-23T12:00:00Z"))
                val ledger = Ledger(Duration.ofHours(2), clock)
                val retention = StagingRetention(RetentionConfig(24, 1, 400), clock)
                val id = ledger.claimRun(RunOpen("r", callerSubject = "owner")).runId
                val path = "runs/$id/i.rows.arrow"
                val sibling = ledger.claimRun(RunOpen("other", callerSubject = "owner")).runId
                StagingStores.open(c).use { store ->
                    store.putIfAbsent(path, byteArrayOf(1))
                    ledger.putOutput(RunOutput(id, "i", "rows", 1, 1, path, "sha256:unused"))
                    // Lifecycle primitive suffices here; the complete preview/close path is covered separately.
                    ledger.markStatus(id, RunStatus.CLOSED)
                    store.putIfAbsent("runs/$sibling/keep.arrow", byteArrayOf(4))
                    val sweeper = StagingSweeper(retention, store) {}
                    clock.now = clock.now.plusSeconds(3599)
                    sweeper.sweep()
                    store.get(path).toList() shouldBe listOf<Byte>(1)
                    clock.now = clock.now.plusSeconds(1)
                    sweeper.sweep()
                    shouldThrow<Exception> { store.get(path) }
                    transaction {
                        RunOutputs.selectAll().where { RunOutputs.run eq id }.single()[RunOutputs.releasedAt] != null
                    } shouldBe true
                    store.putIfAbsent("runs/$id/late.arrow", byteArrayOf(5))
                    sweeper.sweep()
                    shouldThrow<Exception> { store.get("runs/$id/late.arrow") }
                    store.get("runs/$sibling/keep.arrow").toList() shouldBe listOf<Byte>(4)
                    ledger.markStatus(sibling, RunStatus.ABANDONED)
                    sweeper.sweep()
                    shouldThrow<Exception> { store.get("runs/$sibling/keep.arrow") }
                }
            }
        }

        "Redis values have no implicit TTL and prefix deletion scans beyond one page" {
            val c = config("redis")
            val id = UUID.randomUUID()
            val path = "runs/$id/i.rows.arrow"
            StagingStores.open(c).use { store ->
                store.putIfAbsent(path, byteArrayOf(1))
                val client = RedisClient.create(redisUrl)
                try {
                    client.connect().use { connection ->
                        val sync = connection.sync()
                        sync.pttl("${c.prefix}/$path") shouldBe -1L
                        repeat(1100) { sync.set("${c.prefix}/runs/$id/orphan-$it", "x") }
                        sync.set("${c.prefix}/runs/$id-sibling/keep", "safe")
                        store.deleteRun(id)
                        sync.keys("${c.prefix}/runs/$id/*").size shouldBe 0
                        sync.get("${c.prefix}/runs/$id-sibling/keep") shouldBe "safe"
                    }
                } finally {
                    client.shutdown()
                }
            }
        }

        "Seaweed prefix cleanup follows continuation pages without touching neighboring prefixes" {
            val c = config("seaweed")
            val client =
                S3Client
                    .builder()
                    .endpointOverride(URI(s3Url))
                    .region(Region.US_EAST_1)
                    .forcePathStyle(true)
                    .credentialsProvider(AnonymousCredentialsProvider.create())
                    .build()
            val id = UUID.randomUUID()
            TransferStagingStore(S3BlobStore(client, "au-staging", pageSize = 2), c.prefix).use { store ->
                repeat(7) { store.putIfAbsent("runs/$id/orphan-$it.arrow", byteArrayOf(it.toByte())) }
                val other = "runs/${UUID.randomUUID()}/other.arrow"
                store.putIfAbsent(other, byteArrayOf(7))
                store.deleteRun(id)
                repeat(7) { shouldThrow<Exception> { store.get("runs/$id/orphan-$it.arrow") } }
                store.get(other).toList() shouldBe listOf<Byte>(7)
            }
        }
    })
