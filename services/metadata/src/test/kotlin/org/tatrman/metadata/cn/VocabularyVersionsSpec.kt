// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.cn

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.VocabularyVersion
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.metadata.refresh.GuardedRefresher
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.metadata.cn.CnTestKit.IMPORTER
import org.tatrman.metadata.cn.CnTestKit.STEWARD
import org.tatrman.metadata.cn.CnTestKit.arr
import org.tatrman.metadata.cn.CnTestKit.getAs
import org.tatrman.metadata.cn.CnTestKit.json
import org.tatrman.metadata.cn.CnTestKit.postAs
import org.tatrman.metadata.cn.CnTestKit.str
import java.time.Instant

/**
 * CN-P5 S5.3 T5 (A-CN-67, A-CN-94; ttr-server#281 item 1) — `POST /v1/vocabularies/{id}/version` by an importer's
 * principal, `GET` lists; every registration is in the published model's `cn.vocabularyVersions` — through
 * [VocabularySource], one more source on the refresher, so a refresh never loses one.
 */
class VocabularyVersionsSpec :
    FunSpec({
        test("a refresh that fails after a registration is logged — the importer still gets its 201 (review-134 F3)") {
            val service = CandidateService(InMemoryCandidateStore(), CnTestKit.policy, clock = CnTestKit.TickingClock())
            testApplication {
                routing {
                    candidateRoutes(
                        CnSurface.On(service, CnTestKit.callers),
                    ) { error("a model source is down") }
                }
                val r = client.postAs(IMPORTER, "/v1/vocabularies/icd-10/version", """{"version":"2026"}""")
                r.status shouldBe HttpStatusCode.Created
                r.json().str("registered") shouldBe "true"
            }
        }

        test("an importer registers a version once; a steward is not an importer; GET lists in registration order") {
            val store = InMemoryCandidateStore()
            val service = CandidateService(store, CnTestKit.policy, clock = CnTestKit.TickingClock())
            var refreshed = 0
            testApplication {
                routing { candidateRoutes(CnSurface.On(service, CnTestKit.callers)) { refreshed++ } }
                val first =
                    client.postAs(
                        IMPORTER,
                        "/v1/vocabularies/icd-10/version",
                        """{"version":"2026","bundle":"icd-2026-01"}""",
                    )
                first.status shouldBe HttpStatusCode.Created
                first.json().str("vocabulary", "version") shouldBe "2026"
                first.json().str("vocabulary", "bundle") shouldBe "icd-2026-01"
                val again = client.postAs(IMPORTER, "/v1/vocabularies/icd-10/version", """{"version":"2026"}""")
                again.status shouldBe HttpStatusCode.OK
                again.json().str("registered") shouldBe "false"
                again.json().str("vocabulary", "bundle") shouldBe "icd-2026-01" // the registration, not the re-ask
                client.postAs(IMPORTER, "/v1/vocabularies/atc/version", """{"version":"2026.1"}""")
                refreshed shouldBe 2

                client.postAs(STEWARD, "/v1/vocabularies/icd-10/version", """{"version":"2027"}""").status shouldBe
                    HttpStatusCode.Forbidden
                client.postAs(IMPORTER, "/v1/vocabularies/icd-10/version", """{"version":""}""").status shouldBe
                    HttpStatusCode.BadRequest
                client.postAs(IMPORTER, "/v1/vocabularies/icd 10/version", """{"version":"1"}""").status shouldBe
                    HttpStatusCode.BadRequest

                fun versions(json: kotlinx.serialization.json.JsonObject) =
                    json.arr("versions").map {
                        it.jsonObject["vocabulary"]!!.jsonPrimitive.content + " " +
                            it.jsonObject["version"]!!.jsonPrimitive.content
                    }
                versions(client.getAs(STEWARD, "/v1/vocabularies").json()) shouldBe listOf("icd-10 2026", "atc 2026.1")
                versions(client.getAs(STEWARD, "/v1/vocabularies/atc/version").json()) shouldBe listOf("atc 2026.1")
            }
        }

        test("the registrations reach the published model, and a refresh after a new one carries it") {
            val store = InMemoryCandidateStore()
            store.register(
                VocabularyVersion("icd-10", "2026", null, Instant.parse("2026-10-23T12:00:00Z")),
                "icd-importer",
            )
            val registry = MetadataRegistry()
            val reconciler = ModelReconciler(ModelDescriptor(id = "t", name = "t"))
            val vocabularies = VocabularySource(store)
            val refresher =
                GuardedRefresher(
                    sources = listOf(BuiltinStockSource(), vocabularies),
                    sourceIds = listOf(BuiltinStockSource.SOURCE_ID, VocabularySource.SOURCE_ID),
                    reconciler = reconciler,
                    registry = registry,
                    strictSwap = true,
                    configuredIds = emptySet(),
                )
            refresher.refresh(sourceId = "", force = true)

            suspend fun swapped() =
                refresher
                    .refresh(sourceId = VocabularySource.SOURCE_ID, force = false)
                    .single { it.sourceId == VocabularySource.SOURCE_ID }
                    .snapshotSwapped

            fun published() = (registry.read()!!.model.schemas["cn"] as CnSchema).vocabularyVersions.map { it.key }
            published() shouldBe listOf("icd-10" to "2026")

            store.register(
                VocabularyVersion("atc", "2026.1", null, Instant.parse("2026-10-23T12:05:00Z")),
                "icd-importer",
            )
            swapped() shouldBe
                true
            published() shouldBe listOf("icd-10" to "2026", "atc" to "2026.1")
            // nothing new: the source's version is the same, no swap
            swapped() shouldBe
                false
        }

        test("a store that does not answer keeps the last registrations it read") {
            val store = InMemoryCandidateStore()
            store.register(VocabularyVersion("icd-10", "2026", null, Instant.parse("2026-10-23T12:00:00Z")), "x")
            var down = false
            val flaky =
                object : CandidateStore by store {
                    override fun vocabularyVersions(): List<VocabularyVersion> =
                        if (down) error("connection refused") else store.vocabularyVersions()
                }
            val source = VocabularySource(flaky)
            val before = source.load()
            down = true
            val after = source.load()
            after.cn.vocabularyVersions shouldBe before.cn.vocabularyVersions
            after.version shouldBe before.version
        }
    })
