// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.cn

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.concept.candidate.CandidateLoad
import org.tatrman.concept.candidate.CnCandidates
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.veles.cn.CnTestKit.CATALOGUE
import org.tatrman.veles.cn.CnTestKit.EVIDENCE
import org.tatrman.veles.cn.CnTestKit.READER
import org.tatrman.veles.cn.CnTestKit.STEWARD
import org.tatrman.veles.cn.CnTestKit.arr
import org.tatrman.veles.cn.CnTestKit.getAs
import org.tatrman.veles.cn.CnTestKit.ids
import org.tatrman.veles.cn.CnTestKit.json
import org.tatrman.veles.cn.CnTestKit.postAs
import org.tatrman.veles.cn.CnTestKit.str
import org.tatrman.veles.cn.CnTestKit.submit
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * CN-P5 S5.3 T1 — the candidate store over HTTP (cn contracts §6, A-CN-90…95), on the in-memory store and the clinic
 * golden world as the promotion source: submit (valid · invalid · a refused producer) · the queue (filters, the
 * evidence sort) · reject and promote (stewards only) · a merge seen by a model load → `promoted` + `canon_ref` · the
 * same proposal twice → one candidate (A-CN-92) · a non-steward promote → 403.
 */
class CandidateRoutesSpec :
    FunSpec({
        val temps = mutableListOf<Path>()
        afterSpec { temps.forEach { it.toFile().deleteRecursively() } }

        class World(
            val store: InMemoryCandidateStore = InMemoryCandidateStore(),
            val dir: Path = CnTestKit.clinicWorld().also { temps.add(it) },
            deliveries: List<PromotionDelivery> = emptyList(),
            promotion: Boolean = true,
            modelRoot: String = "",
        ) {
            val heard = mutableListOf<String>()
            val service =
                CandidateService(
                    store,
                    CnTestKit.policy,
                    if (promotion) {
                        Promoter(
                            StoragePromotionFiles(LocalFsStorage(id = "clinic", rootPath = dir)),
                            deliveries,
                            modelRoot = modelRoot,
                        )
                    } else {
                        null
                    },
                    { e, _ -> heard += "${e.action} ${e.candidateId}" },
                    CnTestKit.TickingClock(),
                )
            val surface = CnSurface.On(service, CnTestKit.callers)
        }

        fun world(
            w: World = World(),
            block: suspend ApplicationTestBuilder.(World) -> Unit,
        ) = testApplication {
            routing { candidateRoutes(w.surface) }
            block(w)
        }

        val ischemic = CnTestKit.candidate("evidence-phase")
        val transferTerm = CnTestKit.candidate("span-catalogue-term")
        val ischemicId = "9a4c7e21-3f58-4b06-a1d7-e2b5c80f6d19"

        test("no verified bearer is 401; with no store every route is 503 CN_STORE_OFF") {
            world { _ ->
                val r = client.submit(null, ischemic)
                r.status shouldBe HttpStatusCode.Unauthorized
                r.json().str("code") shouldBe CN_UNAUTHENTICATED
                r.headers["WWW-Authenticate"].orEmpty() shouldStartWith "Bearer"
            }
            testApplication {
                routing { candidateRoutes(CnSurface.Off("the candidate store is off: no METADATA_CN_DB_HOST")) }
                for (r in listOf(client.submit(STEWARD, ischemic), client.getAs(STEWARD, "/v1/cn/candidates"))) {
                    r.status shouldBe HttpStatusCode.ServiceUnavailable
                    r.json().str("code") shouldBe CN_STORE_OFF
                    r.json().str("error").orEmpty() shouldContain "METADATA_CN_DB_HOST"
                }
            }
        }

        test("submit: a valid candidate from its producer is stored proposed, with who submitted it, and audited") {
            world { w ->
                val r = client.submit(EVIDENCE, ischemic)
                r.status shouldBe HttpStatusCode.Created
                val body = r.json()
                body.str("state") shouldBe "proposed"
                body.str("candidate", "status") shouldBe "proposed"
                body.str("submitted_by") shouldBe "clinic-evidence"
                body.str("content_hash").orEmpty() shouldStartWith "sha256:"
                // what comes back is a cn-candidate.v1, and the one that went in
                val doc = CnCandidates.read(body.getValue("candidate")) as CandidateLoad.Ok
                doc.candidate shouldBe (CnCandidates.parse(ischemic) as CandidateLoad.Ok).candidate
                w.store.events(ischemicId).map { it.action to it.actor } shouldBe
                    listOf("submitted" to "clinic-evidence")
                w.heard shouldBe listOf("submitted $ischemicId")
            }
        }

        test("submit: not a cn-candidate.v1 is 400 with every violation and its path") {
            world { _ ->
                val r = client.submit(EVIDENCE, ischemic.replace("\"of\": \"cn.Patient\",", ""))
                r.status shouldBe HttpStatusCode.BadRequest
                val body = r.json()
                body.str("code") shouldBe CandidateService.CN_CANDIDATE_INVALID
                body.arr("violations").map {
                    it.jsonObject
                        .getValue("path")
                        .jsonPrimitive.content
                } shouldContain
                    "/proposal/concept/of"
                client.submit(EVIDENCE, "{").status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("submit: the producer registry refuses an importer, a span catalogue's concept, an unregistered id") {
            world { _ ->
                val importer = ischemic.replace("\"clinic:evidence\"", "\"import:icd\"")
                client.submit(CnTestKit.IMPORTER, importer).json().str("code") shouldBe "CN_PRODUCER_IMPORTER"
                val catalogueConcept = ischemic.replace("\"clinic:evidence\"", "\"clinic:span-catalogue\"")
                client.submit(CATALOGUE, catalogueConcept).json().str("code") shouldBe "CN_PRODUCER_TERMS_ONLY"
                val unregistered = ischemic.replace("\"clinic:evidence\"", "\"other:evidence\"")
                val r = client.submit(EVIDENCE, unregistered)
                r.status shouldBe HttpStatusCode.Forbidden
                r.json().str("code") shouldBe "CN_PRODUCER_UNREGISTERED"
            }
        }

        test("submit: a caller speaks only for its own producers — manual:<p> only for the steward p themselves") {
            world { _ ->
                // the catalogue's principal does not submit as the evidence miner
                client.submit(CATALOGUE, ischemic).json().str("code") shouldBe CnPolicy.CN_PRODUCER_NOT_CALLER
                val manual = ischemic.replace("\"clinic:evidence\"", "\"manual:a.steward\"")
                client.submit(EVIDENCE, manual).json().str("code") shouldBe CnPolicy.CN_PRODUCER_NOT_CALLER
                client.submit(CnTestKit.token("a.steward"), manual).json().str("code") shouldBe
                    CnPolicy.CN_PRODUCER_NOT_CALLER
                // a service account named like the steward is not the steward
                client
                    .submit(
                        CnTestKit.token("a.steward", "cn-steward", service = true),
                        manual,
                    ).json()
                    .str("code") shouldBe
                    CnPolicy.CN_PRODUCER_NOT_CALLER
                client.submit(STEWARD, manual).status shouldBe HttpStatusCode.Created
            }
        }

        test("submit: the same proposal twice is one candidate (A-CN-92) — the first, whatever its state") {
            world { w ->
                client.submit(EVIDENCE, ischemic).status shouldBe HttpStatusCode.Created
                // another id, another source, the same proposal
                val again =
                    ischemic
                        .replace(ischemicId, "0b9e2f4c-7d1a-4c3e-8f5b-6a2d1e9c7b30")
                        .replace(
                            "\"doc://clinic/du-48213#s4\",\n    \"confidence\"",
                            "\"doc://clinic/du-99999#s1\",\n    \"confidence\"",
                        )
                val r = client.submit(EVIDENCE, again)
                r.status shouldBe HttpStatusCode.OK
                r.json().str("duplicate") shouldBe "true"
                r.json().str("candidate", "id") shouldBe ischemicId
                w.store.find(CandidateQuery()).size shouldBe 1
                w.store.events(ischemicId).map { it.action } shouldBe listOf("submitted", "duplicate")
                // a rejected proposal stays rejected: resubmitting it answers the rejection
                client.postAs(STEWARD, "/v1/cn/candidates/$ischemicId/reject", """{"note":"not ours"}""")
                client.submit(EVIDENCE, again).json().str("state") shouldBe "rejected"
            }
        }

        test("submit: an id that names another proposal is 409; a candidate is submitted proposed") {
            world { _ ->
                client.submit(EVIDENCE, ischemic)
                val other = CnTestKit.phase("CardiacPatient", ischemicId)
                client.submit(EVIDENCE, other).json().str("code") shouldBe CandidateService.CN_CANDIDATE_ID_TAKEN
                val rejected =
                    CnTestKit.phase("RenalPatient", "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f") {
                        it.replace(
                            "\"status\": \"proposed\"",
                            "\"status\": \"rejected\", \"resolution\": {\"by\": \"x\", \"at\": \"2026-10-23T10:00:00Z\"}",
                        )
                    }
                client.submit(EVIDENCE, rejected).json().str("code") shouldBe CandidateService.CN_CANDIDATE_NOT_PROPOSED
            }
        }

        test("a term's proposed_concept_ref must name a concept candidate in the store") {
            world { _ ->
                val waiting =
                    transferTerm.replace(
                        "\"target_qname\": \"cn.Transfer\"",
                        "\"proposed_concept_ref\": \"$ischemicId\"",
                    )
                client.submit(CATALOGUE, waiting).json().str("code") shouldBe CandidateService.CN_CANDIDATE_UNKNOWN_REF
                client.submit(EVIDENCE, ischemic)
                client.submit(CATALOGUE, waiting).status shouldBe HttpStatusCode.Created
            }
        }

        test("a term for a rejected concept candidate is refused at submit — it could never be promoted (F10)") {
            world { _ ->
                client.submit(EVIDENCE, ischemic)
                client.postAs(STEWARD, "/v1/cn/candidates/$ischemicId/reject").status shouldBe HttpStatusCode.OK
                val waiting =
                    transferTerm.replace(
                        "\"target_qname\": \"cn.Transfer\"",
                        "\"proposed_concept_ref\": \"$ischemicId\"",
                    )
                val r = client.submit(CATALOGUE, waiting)
                r.status shouldBe HttpStatusCode.UnprocessableEntity
                r.json().str("code") shouldBe CandidateService.CN_CANDIDATE_UNKNOWN_REF
                r.json().str("error").orEmpty() shouldContain "rejected"
            }
        }

        test("rejected while its pull request was open, then merged anyway: the load records it promoted (F7)") {
            world { w ->
                client.submit(EVIDENCE, ischemic)
                client.postAs(STEWARD, "/v1/cn/candidates/$ischemicId/promote").status shouldBe HttpStatusCode.OK
                client.postAs(STEWARD, "/v1/cn/candidates/$ischemicId/reject").status shouldBe HttpStatusCode.OK
                gitApply(w.dir, w.store.get(ischemicId)!!.promotion!!)
                w.service.observe(CnTestKit.loadCn(w.dir), "c0ffee4").map { it.id } shouldBe listOf(ischemicId)
                val got = client.getAs(STEWARD, "/v1/cn/candidates/$ischemicId").json()
                got.str("state") shouldBe "promoted"
                got.str("candidate", "resolution", "canon_ref") shouldBe "c0ffee4:cn.IschemicPatient"
                got.arr("history").map {
                    it.jsonObject
                        .getValue("action")
                        .jsonPrimitive.content
                } shouldBe
                    listOf("submitted", "promotion_opened", "rejected", "promoted")
                // a candidate rejected before any promotion is never watched
                w.service.observe(CnTestKit.loadCn(w.dir), "c0ffee5").shouldContainExactly(emptyList())
            }
        }

        test("the queue: filters by status, producer and stereotype; sort=evidence puts the evidence counts first") {
            world { _ ->
                val few = "2a3b4c5d-6e7f-4a8b-9c0d-1e2f3a4b5c6d"
                client.submit(
                    EVIDENCE,
                    CnTestKit.phase("FewPatient", few) {
                        it.replace("\"question_count\": 14", "\"question_count\": 2")
                    },
                )
                client.submit(EVIDENCE, ischemic)
                client.submit(CATALOGUE, transferTerm)
                val termId = "d3e0b6a5-58c1-4e27-9f04-7ab2c61e8d35"

                client.getAs(STEWARD, "/v1/cn/candidates").json().ids() shouldBe listOf(termId, ischemicId, few)
                client.getAs(STEWARD, "/v1/cn/candidates?status=proposed&sort=evidence").json().ids() shouldBe
                    listOf(ischemicId, few, termId)
                client.getAs(STEWARD, "/v1/cn/candidates?stereotype=phase").json().ids() shouldBe
                    listOf(ischemicId, few)
                client.getAs(STEWARD, "/v1/cn/candidates?stereotype=term").json().ids() shouldBe listOf(termId)
                client.getAs(STEWARD, "/v1/cn/candidates?producer=clinic:span-catalogue").json().ids() shouldBe
                    listOf(termId)
                client.getAs(STEWARD, "/v1/cn/candidates?limit=1").json().ids() shouldBe listOf(termId)
                client.postAs(STEWARD, "/v1/cn/candidates/$few/reject")
                client.getAs(STEWARD, "/v1/cn/candidates?status=rejected").json().ids() shouldBe listOf(few)
                client.getAs(STEWARD, "/v1/cn/candidates?status=done").status shouldBe HttpStatusCode.BadRequest
                // a producer principal reads the queue too; anyone else does not
                client.getAs(EVIDENCE, "/v1/cn/candidates").status shouldBe HttpStatusCode.OK
                client.getAs(READER, "/v1/cn/candidates").status shouldBe HttpStatusCode.Forbidden
                client.getAs(READER, "/v1/cn/candidates/$ischemicId").status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("reject: a steward's only; the note and who rejected it are recorded, and the history shows it") {
            world { w ->
                client.submit(EVIDENCE, ischemic)
                val denied = client.postAs(EVIDENCE, "/v1/cn/candidates/$ischemicId/reject", """{"note":"no"}""")
                denied.status shouldBe HttpStatusCode.Forbidden
                denied.json().str("code") shouldBe CnPolicy.CN_FORBIDDEN
                val r =
                    client.postAs(
                        STEWARD,
                        "/v1/cn/candidates/$ischemicId/reject",
                        """{"note":"a code set, not a phase"}""",
                    )
                r.status shouldBe HttpStatusCode.OK
                r.json().str("state") shouldBe "rejected"
                r.json().str("candidate", "status") shouldBe "rejected"
                r.json().str("candidate", "resolution", "by") shouldBe "a.steward"
                r.json().str("candidate", "resolution", "note") shouldBe "a code set, not a phase"
                val again = client.postAs(STEWARD, "/v1/cn/candidates/$ischemicId/reject")
                again.status shouldBe HttpStatusCode.Conflict
                again.json().str("code") shouldBe CandidateService.CN_CANDIDATE_RESOLVED
                val history = client.getAs(STEWARD, "/v1/cn/candidates/$ischemicId").json().arr("history")
                history.map {
                    it.jsonObject
                        .getValue("action")
                        .jsonPrimitive.content
                } shouldBe
                    listOf("submitted", "rejected")
                w.heard.last() shouldBe "rejected $ischemicId"
                client.postAs(STEWARD, "/v1/cn/candidates/0b9e2f4c-7d1a-4c3e-8f5b-6a2d1e9c7b30/reject").status shouldBe
                    HttpStatusCode.NotFound
            }
        }

        test("promote: a non-steward is 403; a steward gets the patch and the pull request, and the candidate waits") {
            world { w ->
                client.submit(EVIDENCE, ischemic)
                client.postAs(EVIDENCE, "/v1/cn/candidates/$ischemicId/promote").status shouldBe
                    HttpStatusCode.Forbidden
                client.postAs(READER, "/v1/cn/candidates/$ischemicId/promote").status shouldBe HttpStatusCode.Forbidden

                val r = client.postAs(STEWARD, "/v1/cn/candidates/$ischemicId/promote")
                r.status shouldBe HttpStatusCode.OK
                val body = r.json()
                body.str("state") shouldBe "pending_merge"
                body.str("candidate", "status") shouldBe "proposed" // the document keeps v1's statuses (A-CN-90)
                body.str("promotion", "qname") shouldBe "cn.IschemicPatient"
                body.str("promotion", "by") shouldBe "a.steward"
                body.str("promotion", "diff").orEmpty() shouldContain "+def phase IschemicPatient {"
                body.str("promotion", "diff").orEmpty() shouldContain "sources: [\"doc://clinic/du-48213#s4\"]"
                body.str("promotion", "pull_request", "title") shouldBe "cn: promote phase IschemicPatient"
                // a phase's pull request carries its er rendering, from the patched model
                val prBody = body.str("promotion", "pull_request", "body").orEmpty()
                prBody shouldContain "### er rendering"
                prBody shouldContain "```sql"
                body.str("promotion", "pull_request_url").shouldBeNull()
                // nothing touched the model: the steward merges
                w.dir.resolve("cn.ttrm").readText() shouldBe
                    CnTestKit
                        .clinicWorld()
                        .also { temps.add(it) }
                        .resolve("cn.ttrm")
                        .readText()

                val twice = client.postAs(STEWARD, "/v1/cn/candidates/$ischemicId/promote")
                twice.status shouldBe HttpStatusCode.Conflict
                twice.json().str("code") shouldBe CandidateService.CN_PROMOTION_PENDING
                w.store.events(ischemicId).map { it.action } shouldBe listOf("submitted", "promotion_opened")
            }
        }

        test("a merge: the next model load carrying the def promotes the candidate, canon_ref = commit:qname") {
            world { w ->
                client.submit(EVIDENCE, ischemic)
                client.submit(CATALOGUE, transferTerm)
                val termId = "d3e0b6a5-58c1-4e27-9f04-7ab2c61e8d35"
                client.postAs(STEWARD, "/v1/cn/candidates/$ischemicId/promote").status shouldBe HttpStatusCode.OK
                client.postAs(STEWARD, "/v1/cn/candidates/$termId/promote").status shouldBe HttpStatusCode.OK

                // a load before the merge decides nothing
                w.service.observe(CnTestKit.loadCn(w.dir), "c0ffee1").shouldContainExactly(emptyList())

                // the steward merges the phase's pull request
                gitApply(w.dir, w.store.get(ischemicId)!!.promotion!!)
                val promoted = w.service.observe(CnTestKit.loadCn(w.dir), "c0ffee2")
                promoted.map { it.id } shouldBe listOf(ischemicId)
                val got = client.getAs(STEWARD, "/v1/cn/candidates/$ischemicId").json()
                got.str("state") shouldBe "promoted"
                got.str("candidate", "status") shouldBe "promoted"
                got.str("candidate", "resolution", "canon_ref") shouldBe "c0ffee2:cn.IschemicPatient"
                got.str("candidate", "resolution", "by") shouldBe "a.steward"
                got
                    .arr("history")
                    .last()
                    .jsonObject
                    .getValue("actor")
                    .jsonPrimitive.content shouldBe
                    CandidateService.MODEL_LOAD
                client.getAs(STEWARD, "/v1/cn/candidates/$termId").json().str("state") shouldBe "pending_merge"

                // then the term's
                gitApply(w.dir, w.store.get(termId)!!.promotion!!)
                w.service.observe(CnTestKit.loadCn(w.dir), "c0ffee3").map { it.id } shouldBe listOf(termId)
                client
                    .getAs(
                        STEWARD,
                        "/v1/cn/candidates/$termId",
                    ).json()
                    .str("candidate", "resolution", "canon_ref") shouldBe
                    "c0ffee3:lexicon.prelozeni"
            }
        }

        test("the gate refuses a promotion whose patched model has a new error; the candidate stays proposed") {
            world { w ->
                // S5.2's lesson: a phase claiming basis: occurrence with no time condition
                val id = "5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9"
                client.submit(
                    EVIDENCE,
                    CnTestKit.phase("OccurringPatient", id) {
                        it.replace("\"of\": \"cn.Patient\",", "\"of\": \"cn.Patient\", \"basis\": \"occurrence\",")
                    },
                )
                val r = client.postAs(STEWARD, "/v1/cn/candidates/$id/promote")
                r.status shouldBe HttpStatusCode.UnprocessableEntity
                r.json().str("code") shouldBe Promoter.CN_PROMOTION_GATE
                r.bodyAsText() shouldContain "cn/basis-mismatch"
                w.store.get(id)!!.state shouldBe CandidateState.PROPOSED
                w.store.events(id).map { it.action } shouldBe listOf("submitted", "promotion_refused")
            }
        }

        test("a patch S5.2 refuses is 422 CN_PROMOTION_REFUSED, its reason in the error") {
            world { _ ->
                val id = "6f7a8b9c-0d1e-4f2a-b3c4-d5e6f7a8b9c0"
                client.submit(EVIDENCE, CnTestKit.phase("OrphanPatient", id) { it.replace("cn.Patient", "cn.Nobody") })
                val r = client.postAs(STEWARD, "/v1/cn/candidates/$id/promote")
                r.status shouldBe HttpStatusCode.UnprocessableEntity
                r.json().str("code") shouldBe Promoter.CN_PROMOTION_REFUSED
                r.json().str("error").orEmpty() shouldContain "cn.Nobody"
            }
        }

        test("with an inbox, a promotion writes <id>.diff and <id>.md there") {
            val inbox = Files.createTempDirectory("cn-inbox").also { temps.add(it) }
            world(World(deliveries = listOf(InboxDelivery(inbox)))) { _ ->
                client.submit(EVIDENCE, ischemic)
                val body = client.postAs(STEWARD, "/v1/cn/candidates/$ischemicId/promote").json()
                body.str("promotion", "inbox") shouldBe inbox.resolve("$ischemicId.diff").toString()
                inbox.resolve("$ischemicId.diff").readText() shouldBe body.str("promotion", "diff")
                inbox.resolve("$ischemicId.md").readText() shouldStartWith "# cn: promote phase IschemicPatient\n\n"
            }
        }

        test(
            "under a sub-path every diff it hands out applies at the repository root, as the pull request does (S5.4)",
        ) {
            // The walk: a model-relative diff applied in the model's directory of a git checkout is SKIPPED by
            // `git apply` without an error, and fails at the root — the record, the API and the inbox carry the root.
            val repo = Files.createTempDirectory("cn-repo").also { temps.add(it) }
            val model = repo.resolve("model")
            CnTestKit
                .clinicWorld()
                .also { temps.add(it) }
                .toFile()
                .copyRecursively(model.toFile())
            git(repo, "init", "-q")
            val inbox = Files.createTempDirectory("cn-inbox").also { temps.add(it) }
            world(World(dir = model, deliveries = listOf(InboxDelivery(inbox)), modelRoot = "/model/")) { w ->
                client.submit(EVIDENCE, ischemic)
                val body = client.postAs(STEWARD, "/v1/cn/candidates/$ischemicId/promote").json()
                val diff = body.str("promotion", "diff").orEmpty()
                diff shouldStartWith "diff --git a/model/"
                diff shouldContain "\n+++ b/model/"
                inbox.resolve("$ischemicId.diff").readText() shouldBe diff
                inbox.resolve("$ischemicId.md").readText() shouldEndWith
                    "Against `${body.str(
                        "promotion",
                        "base",
                    )}`. From the repository root: `git apply $ischemicId.diff`.\n"
                gitApply(repo, w.store.get(ischemicId)!!.promotion!!)
                model.toFile().walk().filter { it.extension == "ttrm" }.any {
                    it.readText().contains("def phase IschemicPatient")
                } shouldBe true
            }
        }

        test("rootedDiff prefixes file headers only — never a hunk line that looks like one, never /dev/null") {
            val diff =
                listOf(
                    "diff --git a/cn.ttrm b/cn.ttrm",
                    "--- a/cn.ttrm",
                    "+++ b/cn.ttrm",
                    "@@ -1,2 +1,2 @@",
                    "--- a/looks-like-a-header",
                    "+++ b/so-does-this",
                    "diff --git a/lexicon-cs.ttrm b/lexicon-cs.ttrm",
                    "--- /dev/null",
                    "+++ b/lexicon-cs.ttrm",
                    "@@ -0,0 +1 @@",
                    "+model lexicon locale cs",
                ).joinToString("\n", postfix = "\n")
            Promoter.rootedDiff(diff, "") shouldBe diff
            Promoter.rootedDiff(diff, "cn-walk/model/").lines() shouldBe
                listOf(
                    "diff --git a/cn-walk/model/cn.ttrm b/cn-walk/model/cn.ttrm",
                    "--- a/cn-walk/model/cn.ttrm",
                    "+++ b/cn-walk/model/cn.ttrm",
                    "@@ -1,2 +1,2 @@",
                    "--- a/looks-like-a-header",
                    "+++ b/so-does-this",
                    "diff --git a/cn-walk/model/lexicon-cs.ttrm b/cn-walk/model/lexicon-cs.ttrm",
                    "--- /dev/null",
                    "+++ b/cn-walk/model/lexicon-cs.ttrm",
                    "@@ -0,0 +1 @@",
                    "+model lexicon locale cs",
                    "",
                )
        }

        test("a delivery failing after another went out: the promotion stands, the failure beside it (F2)") {
            val opened = PromotionDelivery { Delivered(pullRequest = "https://example.test/pull/1") }
            val broken = PromotionDelivery { throw java.io.IOException("No space left on device") }
            world(World(deliveries = listOf(opened, broken))) { _ ->
                client.submit(EVIDENCE, ischemic)
                val r = client.postAs(STEWARD, "/v1/cn/candidates/$ischemicId/promote")
                r.status shouldBe HttpStatusCode.OK
                val body = r.json()
                body.str("state") shouldBe "pending_merge"
                body.str("promotion", "pull_request_url") shouldBe "https://example.test/pull/1"
                body
                    .getValue("promotion")
                    .jsonObject
                    .arr("undelivered")
                    .map { it.jsonPrimitive.content } shouldBe
                    listOf("${Promoter.CN_PROMOTION_DELIVERY}: a delivery failed: No space left on device")
                val opening =
                    client
                        .getAs(STEWARD, "/v1/cn/candidates/$ischemicId")
                        .json()
                        .arr("history")
                        .map { it.jsonObject }
                        .single { it.getValue("action").jsonPrimitive.content == "promotion_opened" }
                opening.getValue("detail").jsonPrimitive.content shouldContain "not delivered"
            }
        }

        test("a delivery failing before any went out refuses the promotion: 502, still proposed, in the history (F2)") {
            val broken = PromotionDelivery { throw java.io.IOException("read-only file system") }
            world(World(deliveries = listOf(broken))) { _ ->
                client.submit(EVIDENCE, ischemic)
                val r = client.postAs(STEWARD, "/v1/cn/candidates/$ischemicId/promote")
                r.status shouldBe HttpStatusCode.BadGateway
                r.json().str("code") shouldBe Promoter.CN_PROMOTION_DELIVERY
                val got = client.getAs(STEWARD, "/v1/cn/candidates/$ischemicId").json()
                got.str("state") shouldBe "proposed"
                got.arr("history").map {
                    it.jsonObject
                        .getValue("action")
                        .jsonPrimitive.content
                } shouldBe
                    listOf("submitted", "promotion_refused")
            }
        }

        test("promotion off (no promotion source) is 503 CN_PROMOTION_OFF; the rest of the store works") {
            world(World(promotion = false)) { _ ->
                client.submit(EVIDENCE, ischemic).status shouldBe HttpStatusCode.Created
                val r = client.postAs(STEWARD, "/v1/cn/candidates/$ischemicId/promote")
                r.status shouldBe HttpStatusCode.ServiceUnavailable
                r.json().str("code") shouldBe CandidateService.CN_PROMOTION_OFF
            }
        }
    })

private fun git(
    dir: Path,
    vararg args: String,
) {
    val proc = ProcessBuilder("git", *args).directory(dir.toFile()).redirectErrorStream(true).start()
    val out = proc.inputStream.bufferedReader().readText()
    check(proc.waitFor(60, TimeUnit.SECONDS) && proc.exitValue() == 0) { "git ${args.joinToString(" ")} failed: $out" }
}

/** The steward merging [p]'s pull request: its stored diff, applied to [dir] with `git apply`. */
private fun gitApply(
    dir: Path,
    p: PromotionRecord,
) {
    val patch = Files.createTempFile("cn-merge", ".diff")
    try {
        patch.writeText(p.diff)
        val proc =
            ProcessBuilder("git", "apply", "--whitespace=nowarn", patch.toString())
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start()
        val out = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor(60, TimeUnit.SECONDS) && proc.exitValue() == 0) { "git apply failed: $out" }
    } finally {
        Files.deleteIfExists(patch)
    }
}
