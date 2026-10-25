// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.cn

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.postgresql.ds.PGSimpleDataSource
import org.tatrman.concept.candidate.CandidateLoad
import org.tatrman.concept.candidate.CnCandidate
import org.tatrman.concept.candidate.CnCandidates
import org.tatrman.concept.candidate.Resolution
import org.tatrman.concept.candidate.Status
import org.tatrman.ttr.metadata.model.VocabularyVersion
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant

/**
 * CN-P5 S5.3 T2 (A-CN-90) — the candidate store on a real Postgres (`postgres:18` = the clusters' CNPG major): the
 * service's own Flyway configuration migrates it into schema `metadata`; a candidate round-trips to the same
 * `cn-candidate.v1` document (resolution, promotion record and all); the content hash dedupes; `replace` is a
 * compare-and-set on the state; the queue filters and sorts like the in-memory store; audit rows and vocabulary versions
 * keep their order.
 */
class PostgresCandidateStoreSpec :
    FunSpec({
        val pg =
            PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                .withDatabaseName("metadata")
                .withUsername("metadata")
                .withPassword("metadata")
                .also { it.start() }
        afterSpec { pg.stop() }
        val store =
            PostgresCandidateStore(
                PGSimpleDataSource().apply {
                    setURL(pg.jdbcUrl)
                    user = "metadata"
                    password = "metadata"
                },
            )

        fun doc(
            id: String,
            name: String,
            questions: Int?,
        ): CnCandidate {
            val text =
                """
                {"id":"$id","proposal":{"concept":{"stereo":"phase","name":"$name","label":{"en":"$name","cs":"$name"},
                 "of":"cn.Patient","definition_text":"exists ConditionRecord involving this where code = 'X21'"}},
                 "provenance":{"producer":"clinic:evidence","source_ref":"doc://clinic/du-1#s1",
                 "confidence":{"value":0.8,"producer":"clinic:evidence","method":"m"},
                 ${if (questions == null) "" else "\"evidence\":{\"question_count\":$questions,\"examples\":[]},"}
                 "created_at":"2026-10-23T09:31:07Z"},"status":"proposed"}
                """.trimIndent()
            return (CnCandidates.parse(text) as CandidateLoad.Ok).candidate
        }

        fun stored(
            c: CnCandidate,
            at: String,
        ) = StoredCandidate(
            c,
            CandidateState.PROPOSED,
            StoredCandidate.contentHash(c),
            "clinic-evidence",
            Instant.parse(at),
            Instant.parse(at),
        )

        val a = stored(doc("11111111-1111-4111-8111-111111111111", "APatient", 3), "2026-10-23T12:00:01Z")
        val b = stored(doc("22222222-2222-4222-8222-222222222222", "BPatient", 9), "2026-10-23T12:00:02Z")
        val c = stored(doc("33333333-3333-4333-8333-333333333333", "CPatient", null), "2026-10-23T12:00:03Z")

        test("insert, then a duplicate proposal and a taken id") {
            store.insert(a).shouldBeInstanceOf<Inserted.Created>()
            store.insert(b).shouldBeInstanceOf<Inserted.Created>()
            store.insert(c).shouldBeInstanceOf<Inserted.Created>()
            val sameProposal = a.copy(candidate = a.candidate.copy(id = "44444444-4444-4444-8444-444444444444"))
            store
                .insert(sameProposal)
                .shouldBeInstanceOf<Inserted.Duplicate>()
                .first.id shouldBe a.id
            val sameId = stored(doc(a.id, "OtherPatient", 1), "2026-10-23T12:00:09Z")
            store
                .insert(sameId)
                .shouldBeInstanceOf<Inserted.IdTaken>()
                .other.id shouldBe a.id
            store.get(a.id) shouldBe a
            store.get("not-a-uuid") shouldBe null
        }

        test("the queue: newest first, or the evidence counts first; filtered by state, producer, stereotype") {
            store.find(CandidateQuery()).map { it.id } shouldContainExactly listOf(c.id, b.id, a.id)
            store.find(CandidateQuery(sort = CandidateSort.EVIDENCE)).map { it.id } shouldContainExactly
                listOf(b.id, a.id, c.id)
            store.find(CandidateQuery(kind = "phase", limit = 2)).map { it.id } shouldContainExactly listOf(c.id, b.id)
            store.find(CandidateQuery(kind = "term")) shouldBe emptyList()
            store.find(CandidateQuery(producer = "kleio")) shouldBe emptyList()
            // both stores order alike
            val memory = InMemoryCandidateStore().also { m -> listOf(a, b, c).forEach { m.insert(it) } }
            for (sort in CandidateSort.entries) {
                memory.find(CandidateQuery(sort = sort)).map { it.id } shouldBe
                    store.find(CandidateQuery(sort = sort)).map { it.id }
            }
        }

        test("replace is a compare-and-set on the state, and a promotion record round-trips") {
            val record =
                PromotionRecord(
                    qname = "cn.APatient",
                    diff = "--- a/cn.ttrm\n+++ b/cn.ttrm\n",
                    title = "cn: promote phase APatient",
                    body = "Promotes …",
                    base = "c0ffee",
                    by = "a.steward",
                    at = Instant.parse("2026-10-23T12:10:00Z"),
                    pullRequest = "https://github.com/clinic/model/pull/7",
                    findings = listOf("cn/x warning cn.APatient"),
                    undelivered = listOf("CN_PROMOTION_DELIVERY: a delivery failed: No space left on device"),
                )
            val pending = a.copy(state = CandidateState.PENDING_MERGE, promotion = record, updatedAt = record.at)
            store.replace(pending, CandidateState.REJECTED) shouldBe false
            store.replace(pending, CandidateState.PROPOSED) shouldBe true
            store.get(a.id) shouldBe pending
            val promoted =
                pending.copy(
                    candidate =
                        pending.candidate.copy(
                            status = Status.PROMOTED,
                            resolution =
                                Resolution(
                                    "a.steward",
                                    "2026-10-23T12:20:00Z",
                                    canonRef = "c0ffee2:cn.APatient",
                                ),
                        ),
                    state = CandidateState.PROMOTED,
                )
            store.replace(promoted, CandidateState.PROPOSED) shouldBe false
            store.replace(promoted, CandidateState.PENDING_MERGE) shouldBe true
            store.get(a.id) shouldBe promoted
            store.find(CandidateQuery(state = CandidateState.PROMOTED)).map { it.id } shouldBe listOf(a.id)
        }

        test("audit rows and vocabulary versions keep their order") {
            store.record(CandidateEvent(b.id, "submitted", "clinic-evidence", Instant.parse("2026-10-23T12:00:02Z")))
            store.record(CandidateEvent(b.id, "rejected", "a.steward", Instant.parse("2026-10-23T12:30:00Z"), "no"))
            store.events(b.id).map { it.action to it.detail } shouldBe listOf("submitted" to "", "rejected" to "no")
            store.events(c.id) shouldBe emptyList()

            val icd = VocabularyVersion("icd-10", "2026", "icd-2026-01", Instant.parse("2026-10-23T13:00:00Z"))
            val atc = VocabularyVersion("atc", "2026.1", null, Instant.parse("2026-10-23T13:01:00Z"))
            store.register(icd, "icd-importer") shouldBe true
            store.register(atc, "atc-importer") shouldBe true
            store.register(icd.copy(bundle = "other"), "icd-importer") shouldBe false
            store.vocabularyVersions() shouldBe listOf(icd, atc)
        }
    })
