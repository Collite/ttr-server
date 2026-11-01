// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.spyk
import io.mockk.coEvery
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.eclipse.jgit.api.Git
import org.tatrman.meta.v1.GetStatusRequest
import org.tatrman.meta.v1.RefreshRequest
import org.tatrman.meta.v1.OverallStatus
import org.tatrman.metadata.grpc.MetadataServiceImpl
import org.tatrman.metadata.health.modelHealthRoutes
import org.tatrman.metadata.read.servedLexicons
import org.tatrman.metadata.read.templateCatalogs
import org.tatrman.metadata.read.GitTemplateSource
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.metadata.cn.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class Review193Spec :
    StringSpec({
        "N1 status transports return cached state during bootstrap and refresh with matching counts" {
            listOf(false, true).forEach { loaded ->
                BranchFixture().use { f ->
                    coroutineScope {
                        val entered = CompletableDeferred<Unit>()
                        val release = CountDownLatch(1)
                        val block = AtomicBoolean(false)
                        val source = blockedSource(f, block, entered, release)
                        val ref = f.refWith(source)
                        if (loaded) ref.refresh("", true)
                        val service =
                            MetadataServiceImpl(
                                f.registry,
                                packageIndex = f.index,
                                loadBoard = f.board,
                                refresher = ref,
                            )
                        block.set(true)
                        val pending = async(Dispatchers.IO) { ref.refreshOutcome("", true, "preview/one") }
                        entered.await()
                        try {
                            withTimeout(500) {
                                ref.status().serving?.commit shouldBe if (loaded) f.main else null
                                ref.status().results shouldBe emptyList()
                                val rpc = service.getStatus(GetStatusRequest.getDefaultInstance())
                                rpc.modelLoaded shouldBe loaded
                                rpc.hasServing() shouldBe loaded
                                if (loaded) rpc.serving.commit shouldBe f.main
                                rpc.sourcesList.single().source shouldBe "git"
                                rpc.sourcesList.single().commit shouldBe if (loaded) f.main else ""
                            }
                            testApplication {
                                application { routing { modelHealthRoutes(f.registry, f.board, ref) } }
                                startApplication()
                                val rpc = service.getStatus(GetStatusRequest.getDefaultInstance())
                                val json =
                                    withTimeout(500) {
                                        Json.parseToJsonElement(client.get("/status").bodyAsText()).jsonObject
                                    }
                                json["model_loaded"]!!.jsonPrimitive.boolean shouldBe loaded
                                json["serving"] shouldBe if (loaded) json["serving"] else JsonNull
                                if (loaded) {
                                    json["serving"]!!.jsonObject["commit"]!!.jsonPrimitive.content shouldBe
                                        f.main
                                }
                                json["sources"]!!.jsonArray.single().jsonObject.apply {
                                    get("source_type")!!.jsonPrimitive.content shouldBe "git"
                                    get("source")!!.jsonPrimitive.content shouldBe
                                        "git ${f.root.toUri()} (branch main, sub-path model)"
                                }
                                json["objects_by_kind"]!!.jsonObject.mapValues {
                                    it.value.jsonPrimitive.int
                                } shouldBe
                                    rpc.objectsByKindMap
                                json["tags"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe rpc.tagsList
                            }
                        } finally {
                            release.countDown()
                            pending.await()
                        }
                        ref.status().serving!!.commit shouldBe f.preview
                    }
                }
            }
        }

        "N2 preview and full SHA content cannot promote a pending CN candidate until the default is accepted" {
            BranchFixture().use { f ->
                val clinic = CnTestKit.clinicWorld()
                try {
                    Files.walk(clinic).use { paths ->
                        paths.filter { Files.isRegularFile(it) }.forEach { file ->
                            val target = f.root.resolve("model").resolve(clinic.relativize(file))
                            Files.createDirectories(target.parent)
                            Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                    f.commit()
                    val store = InMemoryCandidateStore()
                    val candidates =
                        CandidateService(
                            store,
                            CnTestKit.policy,
                            Promoter(
                                StoragePromotionFiles(
                                    org.tatrman.ttr.metadata.source
                                        .LocalFsStorage("clinic", f.root.resolve("model")),
                                ),
                                modelRoot = "model",
                            ),
                        )
                    val id =
                        candidates
                            .submit(
                                CnCaller("clinic-evidence", false, emptySet()),
                                CnTestKit.candidate("evidence-phase"),
                            ).candidate.id
                    val pending = candidates.promote(CnCaller("steward", true, setOf("cn-steward")), id)
                    Git.open(f.root.toFile()).use { git ->
                        git
                            .checkout()
                            .setCreateBranch(true)
                            .setName("cn/pending")
                            .call()
                        git
                            .apply()
                            .setPatch(
                                java.io.ByteArrayInputStream(pending.promotion!!.diff.toByteArray()),
                            ).call()
                    }
                    val preview = f.commit()
                    val ref = f.refWith(strict = false)
                    ref.addPublicationListener("world") { outcome, complete ->
                        ref.promotionCommit("world", outcome)?.let { version ->
                            candidates.observe(outcome.snapshot!!.model.schemas["cn"] as CnSchema, version)
                        }
                        complete(true)
                    }
                    ref.refreshOutcome("", true, "cn/pending").status shouldBe "ok"
                    store.get(id)!!.state shouldBe CandidateState.PENDING_MERGE
                    ref.refreshOutcome("", true, preview).status shouldBe "ok"
                    store.get(id)!!.state shouldBe CandidateState.PENDING_MERGE
                    Git.open(f.root.toFile()).use { git ->
                        git.checkout().setName("main").call()
                        git.merge().include(git.repository.resolve(preview)).call()
                    }
                    // The model SHA is unchanged; accepting its default branch must still observe it.
                    ref.refreshOutcome("", true, "main").status shouldBe "ok"
                    store.get(id)!!.state shouldBe CandidateState.PROMOTED
                    store
                        .get(id)!!
                        .candidate.resolution!!
                        .canonRef shouldBe "$preview:cn.IschemicPatient"
                } finally {
                    clinic.toFile().deleteRecursively()
                }
            }
        }

        "N3 deleted tracked branch fails with retained publication while a local full SHA survives pruning" {
            listOf(false, true).forEach { pinned ->
                BranchFixture().use { f ->
                    f.ref.refresh("", true)
                    val accepted = f.ref.refreshOutcome("", true, if (pinned) f.preview else "preview/one")
                    Git.open(f.root.toFile()).use {
                        it
                            .branchDelete()
                            .setBranchNames("preview/one")
                            .setForce(true)
                            .call()
                    }
                    val result = f.ref.refreshOutcome("", true)
                    result.status shouldBe if (pinned) "ok" else "failed"
                    result.keptPrevious shouldBe !pinned
                    result.serving shouldBe accepted.serving
                    f.storage().branch shouldBe if (pinned) f.preview else "preview/one"
                    if (!pinned) {
                        result.lastAttempt!!.errors.joinToString() shouldContain "git_ref_not_found"
                        result.sources.single().consecutiveFailures shouldBe 1
                    }
                }
            }
        }

        "N4 malformed refs are HTTP 400 refusals with healthy source and identical RPC behavior" {
            BranchFixture().use { f ->
                f.ref.refresh("", true)
                val before = f.registry.read()
                val result = f.ref.refreshOutcome("", true, "foo..bar")
                result.httpStatus shouldBe 400
                result.status shouldBe "rejected"
                result.lastAttempt!!.errors.single() shouldContain "invalid_git_ref"
                result.sources.single().consecutiveFailures shouldBe 0
                (f.registry.read() === before) shouldBe true
                f.head() shouldBe f.main
                val service =
                    MetadataServiceImpl(f.registry, packageIndex = f.index, loadBoard = f.board, refresher = f.ref)
                service
                    .refresh(
                        RefreshRequest
                            .newBuilder()
                            .setForce(true)
                            .setBranch("foo..bar")
                            .build(),
                    ).status shouldBe
                    "rejected"
                service
                    .getStatus(GetStatusRequest.getDefaultInstance())
                    .sourcesList
                    .single()
                    .status shouldBe
                    OverallStatus.OK
                f.ref.status().lastAttempt shouldBe
                    result.lastAttempt?.copy(
                        at =
                            f.ref
                                .status()
                                .lastAttempt!!
                                .at,
                    )
            }
        }

        "N5 initial failure with no configured default exposes rollback failure" {
            BranchFixture().use { f ->
                Git.open(f.root.toFile()).use {
                    it.checkout().setName("broken").call()
                    it
                        .branchDelete()
                        .setBranchNames("main")
                        .setForce(true)
                        .call()
                }
                val failed = f.ref.refreshOutcome("", true, "broken")
                failed.status shouldBe "failed"
                failed.lastAttempt!!.errors.joinToString() shouldContain "rollback_failed"
                f.storage().branch shouldBe "main"
            }
        }

        "N7 internal vocabulary publication preserves content attempt success and retention reports" {
            BranchFixture().use { f ->
                val store = InMemoryCandidateStore()
                val vocabulary = VocabularySource(store)
                val ref = f.refWith(extra = vocabulary)
                ref.refresh("", true)
                val refused = ref.refreshOutcome("", true, "broken")
                val oldSnapshot = refused.snapshot
                store.register(
                    org.tatrman.ttr.metadata.model.VocabularyVersion(
                        "test",
                        "1",
                        registeredAt = java.time.Instant.parse("2026-10-31T00:00:00Z"),
                    ),
                    "review",
                )
                val refreshed = ref.refreshOutcome(VocabularySource.SOURCE_ID, true)
                refreshed.lastAttempt shouldBe refused.lastAttempt
                refreshed.lastSuccess shouldBe refused.lastSuccess
                refreshed.keptPrevious shouldBe true
                refreshed.status shouldBe "ok"
                ref.status().lastAttempt shouldBe refused.lastAttempt
                ref.status().lastSuccess!!.commit shouldBe f.main
                // The content reports survive even when the vocabulary contribution republishes.
                (refreshed.snapshot !== oldSnapshot) shouldBe true
                testApplication {
                    application { routing { modelHealthRoutes(f.registry, f.board, ref) } }
                    val status = Json.parseToJsonElement(client.get("/status").bodyAsText()).jsonObject
                    status["kept_previous"]!!.jsonPrimitive.boolean shouldBe true
                    status["last_attempt"]!!.jsonObject["branch"]!!.jsonPrimitive.content shouldBe "broken"
                    status["last_success"]!!.jsonObject["commit"]!!.jsonPrimitive.content shouldBe f.main
                }
            }
        }

        "F4 internal refresh succeeds after an unknown-id refusal without replacing content reports" {
            BranchFixture().use { f ->
                val ref = f.refWith(extra = VocabularySource(InMemoryCandidateStore()))
                ref.refresh("", true)
                val refused = ref.refreshOutcome("nosuch", true)
                refused.status shouldBe "rejected"
                val refreshed = ref.refreshOutcome(VocabularySource.SOURCE_ID, true)
                refreshed.status shouldBe "ok"
                refreshed.lastAttempt shouldBe refused.lastAttempt
                refreshed.lastSuccess shouldBe refused.lastSuccess
                ref.status().lastAttempt shouldBe refused.lastAttempt
                testApplication {
                    application { routing { modelHealthRoutes(f.registry, f.board, ref) } }
                    val status = Json.parseToJsonElement(client.get("/status").bodyAsText()).jsonObject
                    status["last_attempt"]!!.jsonObject["source_id"]!!.jsonPrimitive.content shouldBe "nosuch"
                }
            }
        }

        "F5 idle and in-flight unknown source refusals validate ids and have identical error bodies" {
            BranchFixture().use { f ->
                coroutineScope {
                    val entered = CompletableDeferred<Unit>()
                    val release = CountDownLatch(1)
                    val block = AtomicBoolean(false)
                    val ref = f.refWith(blockedSource(f, block, entered, release))
                    ref.refresh("", true)
                    testApplication {
                        application {
                            routing {
                                modelHealthRoutes(f.registry, f.board, ref)
                                metadataRefreshRoutes(ref)
                            }
                        }
                        startApplication()

                        suspend fun refusal(id: String): JsonObject {
                            val response = client.post("/refresh") { url { parameters.append("source_id", id) } }
                            response.status.value shouldBe 400
                            return Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        }
                        val idle = listOf("<x y>", "nosuch", "a".repeat(129)).associateWith { refusal(it) }
                        idle
                            .getValue(
                                "<x y>",
                            )["last_attempt"]!!
                            .jsonObject["source_id"]!!
                            .jsonPrimitive.content shouldBe
                            ""
                        idle
                            .getValue("<x y>")["errors"]!!
                            .jsonArray
                            .single()
                            .jsonPrimitive.content shouldBe
                            "invalid_source_id"
                        idle
                            .getValue("nosuch")["errors"]!!
                            .jsonArray
                            .single()
                            .jsonPrimitive.content shouldBe
                            "unknown_source_id: nosuch"
                        ref.status().lastAttempt!!.sourceId shouldBe ""
                        block.set(true)
                        val pending = async(Dispatchers.IO) { ref.refreshOutcome("", true) }
                        entered.await()
                        try {
                            idle.forEach { (id, expected) ->
                                val busy = withTimeout(1000) { refusal(id) }
                                val rpc = withTimeout(1000) { ref.refreshOutcome(id, false) }
                                rpc.httpStatus shouldBe 400
                                rpc.lastAttempt!!.errors shouldBe
                                    expected["errors"]!!.jsonArray.map { it.jsonPrimitive.content }
                                rpc.lastAttempt!!.sourceId shouldBe
                                    expected["last_attempt"]!!.jsonObject["source_id"]!!.jsonPrimitive.content
                                // Completion timestamps are intentionally fresh; the response contract otherwise agrees.
                                (busy - "last_attempt") shouldBe (expected - "last_attempt")
                                (busy.getValue("last_attempt").jsonObject - "at") shouldBe
                                    (expected.getValue("last_attempt").jsonObject - "at")
                            }
                            ref.status().lastAttempt!!.sourceId shouldBe ""
                        } finally {
                            release.countDown()
                            pending.await()
                        }
                    }
                }
            }
        }

        "F9 cancellation immediately after load skips reconciliation" {
            BranchFixture().use { f ->
                coroutineScope {
                    val entered = CompletableDeferred<Unit>()
                    val release = CountDownLatch(1)
                    val block = AtomicBoolean(false)
                    val calls =
                        java.util.concurrent.atomic
                            .AtomicInteger()
                    val reconciler = spyk(ModelReconciler(ModelDescriptor("test", "test")))
                    every { reconciler.reconcile(any()) } answers {
                        calls.incrementAndGet()
                        callOriginal()
                    }
                    val ref = f.refWith(blockedSource(f, block, entered, release), reconciler)
                    ref.refresh("", true)
                    calls.set(0)
                    block.set(true)
                    val pending = async(Dispatchers.IO) { ref.refreshOutcome("", true, "preview/one") }
                    entered.await()
                    pending.cancel()
                    release.countDown()
                    pending.join()
                    calls.get() shouldBe 0
                    f.head() shouldBe f.main
                }
            }
        }

        "F10 no-change publications skip observation and failed asynchronous observation retries" {
            BranchFixture().use { f ->
                var calls = 0
                var fail = false
                var completePending: ((Boolean) -> Unit)? = null
                f.ref.addPublicationListener("world") { outcome, complete ->
                    if (f.ref.promotionCommit("world", outcome) == null) {
                        complete(true)
                    } else {
                        calls++
                        if (fail) completePending = complete else complete(true)
                    }
                }
                f.ref.refresh("", true)
                calls shouldBe 1
                repeat(3) { f.ref.refresh("", true) }
                calls shouldBe 1
                f.ref.refreshOutcome("", true, "preview/one")
                calls shouldBe 1
                Git.open(f.root.toFile()).use { git ->
                    git.checkout().setName("main").call()
                    git.merge().include(git.repository.resolve(f.preview)).call()
                }
                fail = true
                f.ref.refreshOutcome("", true, "main")
                calls shouldBe 2
                f.ref.refresh("", true) // Do not duplicate a database operation still in flight.
                calls shouldBe 2
                completePending!!(false)
                fail = false
                f.ref.refresh("", true)
                calls shouldBe 3
                repeat(3) { f.ref.refresh("", true) }
                calls shouldBe 3
            }
        }

        "F10 a thrown observation retries on the next no-change refresh" {
            BranchFixture().use { f ->
                var calls = 0
                f.ref.addPublicationListener("world") { _, complete ->
                    calls++
                    if (calls == 1) error("database unavailable")
                    complete(true)
                }
                f.ref.refresh("", true)
                calls shouldBe 1
                f.ref.refresh("", true)
                calls shouldBe 2
                repeat(3) { f.ref.refresh("", true) }
                calls shouldBe 2
            }
        }

        "N9 cancellation between reconcile and publication cannot swap or retain a preview checkout" {
            BranchFixture().use { f ->
                coroutineScope {
                    val entered = CompletableDeferred<Unit>()
                    val release = CountDownLatch(1)
                    val block = AtomicBoolean(false)
                    val reconciler = spyk(ModelReconciler(ModelDescriptor("test", "test")))
                    every { reconciler.reconcile(any()) } answers {
                        val result = callOriginal()
                        if (block.compareAndSet(true, false)) {
                            entered.complete(Unit)
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                        result
                    }
                    val ref = f.refWith(reconciler = reconciler)
                    ref.refresh("", true)
                    val before = f.registry.read()
                    block.set(true)
                    val pending = async(Dispatchers.IO) { ref.refreshOutcome("", true, "preview/one") }
                    entered.await()
                    pending.cancel()
                    release.countDown()
                    pending.join()
                    (f.registry.read() === before) shouldBe true
                    f.head() shouldBe f.main
                    f.storage().branch shouldBe "main"
                }
            }
        }

        "N9 scheduler ticks refuse in-flight loads without recording additional source attempts" {
            BranchFixture().use { f ->
                coroutineScope {
                    val entered = CompletableDeferred<Unit>()
                    val release = CountDownLatch(1)
                    val block = AtomicBoolean(false)
                    val ref = f.refWith(blockedSource(f, block, entered, release))
                    ref.refresh("", true)
                    val before = f.board.states()
                    val schedulerRef = spyk(ref)
                    val ticks = java.util.concurrent.CopyOnWriteArrayList<List<GuardedRefresher.SourceResult>>()
                    val ticked = CompletableDeferred<Unit>()
                    coEvery { schedulerRef.refresh("", false) } coAnswers {
                        callOriginal().also {
                            ticks += it
                            if (ticks.size >= 3) ticked.complete(Unit)
                        }
                    }
                    block.set(true)
                    val pending = async(Dispatchers.IO) { ref.refreshOutcome("", true, "preview/one") }
                    entered.await()
                    val scheduler = RefreshScheduler(schedulerRef, java.time.Duration.ofMillis(20)).start()
                    try {
                        withTimeout(3000) { ticked.await() }
                        ticks.size.let { it >= 3 } shouldBe true
                        ticks.all { results ->
                            results.isNotEmpty() &&
                                results.all { it.errorMessage == "refresh_in_flight" }
                        } shouldBe
                            true
                        f.board.states() shouldBe before
                    } finally {
                        scheduler.cancelAndJoin()
                        release.countDown()
                        pending.await()
                    }
                }
            }
        }

        "N9 model template and lexicon sidecars remain bound to the resolved commit even if HEAD moves" {
            BranchFixture().use { f ->
                val versions = linkedMapOf<String, String>()
                Git.open(f.root.toFile()).use { git ->
                    listOf("main", "preview/one").forEach { branch ->
                        git.checkout().setName(branch).call()
                        val archive = f.root.resolve("generated/lexicon.tar.zst")
                        Files.createDirectories(archive.parent)
                        Files.writeString(archive, "archive $branch")
                        val report = f.root.resolve("model/reports/sales/report.yaml")
                        Files.createDirectories(report.parent)
                        Files.writeString(report, "title: {en: '$branch'}\n")
                        Files.write(report.resolveSibling("template.xlsx"), byteArrayOf(1, 2, 3))
                        versions[branch] = f.commit()
                    }
                    git.checkout().setName("main").call()
                }
                versions.forEach { (branch, version) ->
                    f.ref.refreshOutcome("", true, branch).status shouldBe "ok"
                    f.registry.readOrError().servedLexicons().single().apply {
                        this.version shouldBe version
                        String(
                            java.util.Base64
                                .getDecoder()
                                .decode(archive),
                        ) shouldBe "archive $branch"
                    }
                    f.registry.readOrError().templateCatalogs().single().apply {
                        this.version shouldBe version
                        reports.single().yaml shouldContain branch
                    }
                    // Reader binding must survive a concurrently moved HEAD too (kills the HEAD-reader mutation).
                    val snapshot = (f.slots.single { it.id == "world" }.source as ManagedSource).attempted!!
                    val other = versions.getValue(if (branch == "main") "preview/one" else "main")
                    Git.open(f.checkout.toFile()).use {
                        it
                            .reset()
                            .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                            .setRef(other)
                            .call()
                    }
                    val sidecar = GitTemplateSource(ModelSource { snapshot }, f.checkout, "model", "world", 100).load()
                    val catalog =
                        org.tatrman.metadata.read.TemplateCatalog
                            .serializer()
                    Json
                        .decodeFromString(
                            catalog,
                            sidecar.exportSidecars.getValue(
                                org.tatrman.metadata.read.TEMPLATE_SIDECAR_PREFIX + "world",
                            ),
                        ).apply {
                            this.version shouldBe version
                            reports.single().yaml shouldContain branch
                        }
                }
            }
        }
    })

private fun BranchFixture.refWith(
    world: ModelSource = slots.single { it.id == "world" }.source,
    reconciler: ModelReconciler = ModelReconciler(ModelDescriptor("test", "test")),
    extra: ModelSource? = null,
    strict: Boolean = true,
): GuardedRefresher =
    GuardedRefresher(
        slots.map { if (it.id == "world") world else it.source } + listOfNotNull(extra),
        slots.map { it.id } + if (extra != null) listOf(VocabularySource.SOURCE_ID) else emptyList(),
        reconciler,
        registry,
        strict,
        setOf("world"),
        board,
        index,
        true,
    )

private fun blockedSource(
    f: BranchFixture,
    block: AtomicBoolean,
    entered: CompletableDeferred<Unit>,
    release: CountDownLatch,
): ManagedSource {
    val inner = f.slots.single { it.id == "world" }.source
    return ManagedSource(
        "world",
        "git",
        ModelSource {
            inner.load().also {
                if (block.compareAndSet(true, false)) {
                    entered.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
        },
        f.storage(),
    )
}
