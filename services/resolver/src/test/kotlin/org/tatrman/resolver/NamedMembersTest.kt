// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.pipeline.DomainSpanCandidate
import org.tatrman.resolver.pipeline.NamedCategories
import org.tatrman.resolver.pipeline.NamedCategorySource
import org.tatrman.resolver.pipeline.NamedMembers
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.UniversalEntityType
import org.tatrman.resolver.v1.ValueKind
import java.util.concurrent.atomic.AtomicInteger

/**
 * UD-P3 — named members: a place or a person name nothing governs, offered as a member of the
 * vocabularies whose own values the NER finds such names in (UD contracts §3.2a; invariants I-2, I-4,
 * I-7 amended, I-11 to I-13).
 */
class NamedMembersTest :
    StringSpec({

        val place = "LOCATION" to "cnec:gu"
        val gpe = "GPE" to ""

        fun ResolveResponse.findingsOn(text: String) = resolutionState.valuesList.filter { it.span.text == text }

        fun ResolveResponse.gapsOn(text: String) =
            resolutionState.gapsList.filter { it.span.text == text }.map { it.kind }

        // ── the profile, read ──────────────────────────────────────────────────────────────────

        "a category is named for the types its values' entities classify to, by the question's own classifier" {
            val named =
                NamedCategories.fromStatus(
                    MhMembers.profiles(
                        "Er.Entity.Warehouse.Warehouse_Name" to listOf(place),
                        "er.entity.store.store_name" to listOf(gpe, "ORGANIZATION" to "cnec:if"),
                        "er.entity.user.name" to listOf("PERSON" to "cnec:pf"),
                        "er.entity.item.product_name" to listOf("MISC" to "cnec:op"),
                        "er.entity.sales.sold_date" to listOf("DATE" to "cnec:td"),
                    ),
                )

            named.of(UniversalEntityType.LOCATION) shouldBe
                setOf("er.entity.warehouse.warehouse_name", "er.entity.store.store_name")
            named.of(UniversalEntityType.PERSON) shouldBe setOf("er.entity.user.name")
            // an object (`op`) is not universal at all, and a date has no member reading (⚑UD-5)
            named.of(UniversalEntityType.DATE).shouldBeEmpty()
            named.of(UniversalEntityType.MISC).shouldBeEmpty()
        }

        "no profile, or a profile with no names, names nothing (I-12, I-4)" {
            NamedCategories.fromStatus(FuzzyStatusResponse.getDefaultInstance()).isEmpty shouldBe true
            NamedCategories.fromStatus(MhMembers.profiles(MhMembers.REASON_DESC to emptyList())).isEmpty shouldBe true
        }

        // ── the profile, refreshed ─────────────────────────────────────────────────────────────

        class StatusFuzzy(
            var status: () -> FuzzyStatusResponse,
        ) : FuzzyClient {
            val calls = AtomicInteger()

            override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse =
                BatchMatchResponse.getDefaultInstance()

            override suspend fun getStatus(): FuzzyStatusResponse {
                calls.incrementAndGet()
                return status()
            }
        }

        val dcNamed = MhMembers.profiles(MhMembers.WAREHOUSE_NAME to listOf(place))

        "the profile is read at most once per TTL" {
            var now = 0L
            val fuzzy = StatusFuzzy { dcNamed }
            val source = NamedCategorySource(fuzzy, ttlMs = 1_000, clock = { now })

            runBlocking {
                source.current().of(UniversalEntityType.LOCATION) shouldBe setOf(MhMembers.WAREHOUSE_NAME)
                now = 999
                source.current()
                fuzzy.calls.get() shouldBe 1
                now = 1_000
                source.current()
                fuzzy.calls.get() shouldBe 2
            }
        }

        "a status that fails or is slow keeps the last profile; before the first there is none (I-12)" {
            var now = 0L
            var mode = "fail"
            val fuzzy =
                StatusFuzzy {
                    when (mode) {
                        "fail" -> error("lex-matcher down")
                        else -> dcNamed
                    }
                }
            val source = NamedCategorySource(fuzzy, ttlMs = 10, timeoutMs = 50, clock = { now })
            runBlocking {
                source.current().isEmpty shouldBe true

                mode = "ok"
                now = 10
                source.current().isEmpty shouldBe false

                mode = "fail"
                now = 20
                source.current().of(UniversalEntityType.LOCATION) shouldBe setOf(MhMembers.WAREHOUSE_NAME)
            }

            val slow =
                object : FuzzyClient {
                    override suspend fun batchMatch(request: BatchMatchRequest) =
                        BatchMatchResponse.getDefaultInstance()

                    override suspend fun getStatus(): FuzzyStatusResponse {
                        delay(5_000)
                        return dcNamed
                    }
                }
            runBlocking { NamedCategorySource(slow, timeoutMs = 20).current().isEmpty shouldBe true }
        }

        // ── proposal ───────────────────────────────────────────────────────────────────────────

        /** «Kolik týdnů mělo Brno DC nulové zásoby» — the hartland CZ script's 3.5, shortened. */
        fun brnoDc(): Array<Token> =
            arrayOf(
                MhMembers.tok("Kolik", 0, 5, "kolik", "DET", 2, "det:numgov"),
                MhMembers.tok("týdnů", 6, 11, "týden", "NOUN", 3, "nsubj"),
                MhMembers.tok("mělo", 12, 16, "mít", "VERB", 0, "root"),
                MhMembers.tok("Brno", 17, 21, "Brno", "PROPN", 3, "obl"),
                MhMembers.tok("DC", 22, 24, "DC", "NOUN", 4, "nmod"),
                MhMembers.tok("nulové", 25, 31, "nulový", "ADJ", 7, "amod"),
                MhMembers.tok("zásoby", 32, 38, "zásoba", "NOUN", 3, "obj"),
            )

        val brnoDcText = "Kolik týdnů mělo Brno DC nulové zásoby"

        // NameTag on hartland types the DC name whole in some questions and the town alone in others
        val wholeName = listOf(MhMembers.ner("Brno DC", 17, 24, "LOCATION", "cnec:gu"))
        val townOnly = listOf(MhMembers.ner("Brno", 17, 21, "LOCATION", "cnec:gu"))

        fun proposeOver(
            entities: List<NerEntity>,
            named: NamedCategories,
            tokens: Array<Token> = brnoDc(),
            text: String = brnoDcText,
            proposed: List<DomainSpanCandidate> = emptyList(),
        ) = NamedMembers.candidates(
            MhMembers
                .parse(text, tokens, "cs")
                .toBuilder()
                .addAllEntities(entities)
                .build(),
            proposed,
            named,
            MhMembers.namedEntityTypes(),
        )

        val dcProfile = NamedCategories.fromStatus(dcNamed)

        "a bare place is offered to the named vocabularies only, over the entity it reads" {
            val c = proposeOver(wholeName, dcProfile).single()

            c.origin shouldBe DomainSpanCandidate.Origin.NAMED_MEMBER
            c.text shouldBe "Brno DC"
            c.dualReadingOf shouldBe (17 to 24)
            c.categories shouldContainExactly listOf(MhMembers.WAREHOUSE_NAME)
            c.gatedEntityRefs shouldContainExactly listOf(MhMembers.WAREHOUSE_NAME)
            c.anchored shouldBe false
        }

        "…with the words the parse continues the name with, when the NER typed only the town (✅UD-9)" {
            val c = proposeOver(townOnly, dcProfile).single()

            c.text shouldBe "Brno DC"
            c.start shouldBe 17
            c.end shouldBe 24
            c.dualReadingOf shouldBe (17 to 21)
        }

        "I-11 — no named vocabulary for the entity's type: nothing is proposed" {
            proposeOver(wholeName, NamedCategories.NONE).shouldBeEmpty()
            // a person-named vocabulary is not asked about a place
            proposeOver(
                wholeName,
                NamedCategories(mapOf(UniversalEntityType.PERSON to setOf(MhMembers.WAREHOUSE_NAME))),
            ).shouldBeEmpty()
            // a profiled category the registry does not declare as a member vocabulary is never asked
            proposeOver(
                wholeName,
                NamedCategories(mapOf(UniversalEntityType.LOCATION to setOf("er.entity.ghost.name"))),
            ).shouldBeEmpty()
            // a date is not a dual-reading type
            proposeOver(listOf(MhMembers.ner("Brno DC", 17, 24, "DATE", "cnec:td")), dcProfile).shouldBeEmpty()
        }

        "a governed reading of the same entity wins: nothing is proposed over it (§4.3)" {
            val governed =
                DomainSpanCandidate(
                    "Brno DC",
                    17,
                    24,
                    listOf(MhMembers.WAREHOUSE),
                    listOf(MhMembers.WAREHOUSE_NAME),
                    anchored = true,
                    origin = DomainSpanCandidate.Origin.GOVERNED_VALUE,
                    dualReadingOf = 17 to 24,
                )
            proposeOver(wholeName, dcProfile, proposed = listOf(governed)).shouldBeEmpty()
        }

        "the name never runs into a declared mention" {
            // `Brno sklad`: `sklad` is the warehouse anchor, hung off the town as its nmod by the parse
            val tokens =
                arrayOf(
                    MhMembers.tok("Brno", 0, 4, "Brno", "PROPN", 0, "root"),
                    MhMembers.tok("sklad", 5, 10, "sklad", "NOUN", 1, "nmod"),
                )
            val mention =
                DomainSpanCandidate(
                    "sklad",
                    5,
                    10,
                    listOf(MhMembers.WAREHOUSE),
                    listOf(MhMembers.WAREHOUSE),
                    anchored = true,
                    origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE,
                )
            proposeOver(
                listOf(MhMembers.ner("Brno", 0, 4, "LOCATION", "cnec:gu")),
                dcProfile,
                tokens,
                "Brno sklad",
                listOf(mention),
            ).single().text shouldBe "Brno"
        }

        // ── which rows speak (I-13) ────────────────────────────────────────────────────────────

        "only a member that IS the name speaks — modulo case, diacritics and spacing" {
            val c = proposeOver(wholeName, dcProfile).single()

            fun row(
                value: String,
                category: String = MhMembers.WAREHOUSE_NAME,
                source: SourceTag = SourceTag.MEMBER,
            ) = FuzzyMatch
                .newBuilder()
                .setCandidate(value)
                .setCandidateId(value)
                .setCategory(category)
                .setSource(source)
                .setScore(1.0)
                .build()

            NamedMembers
                .admitted(
                    listOf(
                        row("brno dc"),
                        row("Brno  DC   "),
                        row("Brno DC Sever"),
                        row("Brno"),
                        row("Brno DC", MhMembers.STORE_NAME),
                        row("Brno DC", MhMembers.WAREHOUSE_NAME, SourceTag.DECLARED),
                    ),
                    c,
                ).map { it.candidate } shouldContainExactly listOf("brno dc", "Brno  DC   ")
        }

        // ── through the pipeline ───────────────────────────────────────────────────────────────

        fun resolveBrno(
            entities: List<NerEntity>,
            status: FuzzyStatusResponse?,
            tokens: Array<Token> = brnoDc(),
            text: String = brnoDcText,
        ) = MhMembers.resolve(text, tokens, "cs", MhMembers.NAMED_REGISTRY, entities, status)

        "3.5 `mělo Brno DC` — the warehouse binds: one LITERAL attributed to its member, no place, no G3" {
            val r = resolveBrno(wholeName, dcNamed)

            val finding = r.findingsOn("Brno DC").single()
            finding.kind shouldBe ValueKind.VALUE_KIND_LITERAL
            finding.hasGrounding() shouldBe false
            finding.attributionsList.map { it.binding.ref } shouldContainExactly
                listOf("${MhMembers.WAREHOUSE_NAME}#Brno DC")
            r.gapsOn("Brno DC").shouldBeEmpty()
            r.hasAwaiting() shouldBe false
            r.resolution.bindingsList.count { it.hasUniversal() } shouldBe 0
            r.resolution.bindingsList
                .single { it.domain.rawText == "Brno DC" }
                .domain.memberOf shouldBe MhMembers.WAREHOUSE_NAME
        }

        "3.5 without a profile — the place stands with its G3, exactly as before (I-12)" {
            val r = resolveBrno(wholeName, status = null)

            r.findingsOn("Brno DC").single().kind shouldBe ValueKind.VALUE_KIND_GROUNDED
            r.gapsOn("Brno DC") shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)
        }

        "the town typed alone: the member takes the whole name, and the tail word leaves no finding of its own" {
            val r = resolveBrno(townOnly, dcNamed)

            r
                .findingsOn("Brno DC")
                .single()
                .attributionsList
                .map { it.binding.ref } shouldContainExactly
                listOf("${MhMembers.WAREHOUSE_NAME}#Brno DC")
            r.findingsOn("Brno").shouldBeEmpty()
            r.resolutionState.mentionsList.none { it.span.text == "DC" } shouldBe true
            r.gapsOn("DC").shouldBeEmpty()
            r.resolution.bindingsList.count { it.hasUniversal() } shouldBe 0
        }

        "…and a tail word the parse calls a proper noun, proposed on its own, is taken into the name too" {
            // `DC` tagged PROPN: path (b) proposes it as a value of its own, which asks the matcher,
            // finds nothing and would leave a second finding, with its own gap, inside the warehouse
            val tokens = brnoDc().also { it[4] = MhMembers.tok("DC", 22, 24, "DC", "PROPN", 4, "flat") }
            val r = resolveBrno(townOnly, dcNamed, tokens = tokens)

            r
                .findingsOn("Brno DC")
                .single()
                .attributionsList
                .map { it.binding.ref } shouldContainExactly
                listOf("${MhMembers.WAREHOUSE_NAME}#Brno DC")
            r.resolutionState.valuesList
                .filter {
                    it.span.start >= 17 && it.span.end <= 24
                }.map { it.span.text } shouldContainExactly
                listOf("Brno DC")
            r.gapsOn("DC").shouldBeEmpty()

            // …while without the member, `DC` keeps the reading it always had
            resolveBrno(townOnly, status = null, tokens = tokens).findingsOn("DC").size shouldBe 1
        }

        "a reading that found no member is withdrawn, and the resolve is the one without a profile (I-12)" {
            // `Ostrava DC` is no member here: the reading is asked, finds nothing, and leaves no trace
            val tokens =
                arrayOf(
                    MhMembers.tok("Kolik", 0, 5, "kolik", "DET", 2, "det:numgov"),
                    MhMembers.tok("týdnů", 6, 11, "týden", "NOUN", 3, "nsubj"),
                    MhMembers.tok("mělo", 12, 16, "mít", "VERB", 0, "root"),
                    MhMembers.tok("Ostrava", 17, 24, "Ostrava", "PROPN", 3, "obl"),
                    MhMembers.tok("DC", 25, 27, "DC", "NOUN", 4, "nmod"),
                )
            val text = "Kolik týdnů mělo Ostrava DC"
            val ostrava = listOf(MhMembers.ner("Ostrava", 17, 24, "LOCATION", "cnec:gu"))
            val fuzzy = MhMembers.MemberFuzzy(MhMembers.NAMED_REGISTRY, dcNamed)

            val with = MhMembers.resolve(text, tokens, "cs", MhMembers.NAMED_REGISTRY, ostrava, dcNamed, fuzzy)
            val without = resolveBrno(ostrava, status = null, tokens = tokens, text = text)

            fuzzy.asked.any { it.query == "Ostrava DC" && MhMembers.WAREHOUSE_NAME in it.categoriesList } shouldBe true
            with.resolutionState shouldBe without.resolutionState
            with.resolution shouldBe without.resolution
            with.hasAwaiting() shouldBe without.hasAwaiting()
        }

        // ── F-8 and the precision invariants ───────────────────────────────────────────────────

        fun bare(name: String): Array<Token> =
            arrayOf(
                MhMembers.tok("How", 0, 3, "how", "ADV", 4, "advmod"),
                MhMembers.tok("is", 4, 6, "be", "AUX", 4, "aux"),
                MhMembers.tok(name, 7, 7 + name.length, name, "PROPN", 4, "nsubj"),
                MhMembers.tok("doing", 8 + name.length, 13 + name.length, "do", "VERB", 0, "root"),
            )

        fun ResolveResponse.standsAsPlace(name: String) {
            val finding = findingsOn(name).single()
            finding.kind shouldBe ValueKind.VALUE_KIND_GROUNDED
            finding.attributionsList.shouldBeEmpty()
            gapsOn(name) shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)
        }

        "I-4 — bare `Paris`: the return reasons hold no names, so they are never asked about a place" {
            val fuzzy = MhMembers.MemberFuzzy(MhMembers.NAMED_REGISTRY, dcNamed)
            val r =
                MhMembers.resolve(
                    "How is Paris doing",
                    bare("Paris"),
                    "en",
                    MhMembers.NAMED_REGISTRY,
                    listOf(MhMembers.ner("Paris", 7, 12, "GPE")),
                    dcNamed,
                    fuzzy,
                )

            r.standsAsPlace("Paris")
            fuzzy.asked.none { it.query == "Paris" && MhMembers.REASON_DESC in it.categoriesList } shouldBe true
        }

        "I-13 — a named vocabulary answers only with the whole name: TOKENS `Parts missing`, `Dallas DC` are silent" {
            val everythingNamed =
                MhMembers.profiles(MhMembers.WAREHOUSE_NAME to listOf(gpe), MhMembers.REASON_DESC to listOf(gpe))
            MhMembers
                .resolve(
                    "How is Paris doing",
                    bare("Paris"),
                    "en",
                    MhMembers.NAMED_REGISTRY,
                    listOf(MhMembers.ner("Paris", 7, 12, "GPE")),
                    everythingNamed,
                ).standsAsPlace("Paris")
            MhMembers
                .resolve(
                    "How is Dallas doing",
                    bare("Dallas"),
                    "en",
                    MhMembers.NAMED_REGISTRY,
                    listOf(MhMembers.ner("Dallas", 7, 13, "GPE")),
                    everythingNamed,
                ).standsAsPlace("Dallas")
        }

        "⚑UD-11 (a) — bare `TN`, a whole member of three named vocabularies: asked among the members" {
            val states =
                MhMembers.profiles(
                    MhMembers.STORE_STATE to listOf(gpe),
                    MhMembers.CA_STATE to listOf(gpe),
                    MhMembers.WAREHOUSE_STATE to listOf(gpe),
                )
            val r =
                MhMembers.resolve(
                    "TN",
                    MhMembers.e12Bare(),
                    registry = MhMembers.NAMED_REGISTRY,
                    entities = listOf(MhMembers.ner("TN", 0, 2, "GPE")),
                    status = states,
                )

            r.hasAwaiting() shouldBe true
            r.awaiting.optionsList.map { it.memberOf } shouldContainExactlyInAnyOrder
                listOf(MhMembers.STORE_STATE, MhMembers.CA_STATE, MhMembers.WAREHOUSE_STATE)
            r.findingsOn("TN").none { it.kind == ValueKind.VALUE_KIND_GROUNDED } shouldBe true
        }

        "I-7 amended — a pre-modifier place that is a whole member binds (`Nashville stores`)" {
            val tokens =
                arrayOf(
                    MhMembers.tok("Nashville", 0, 9, "Nashville", "PROPN", 2, "compound"),
                    MhMembers.tok("stores", 10, 16, "store", "NOUN", 0, "root"),
                )
            val r =
                MhMembers.resolve(
                    "Nashville stores",
                    tokens,
                    registry = MhMembers.NAMED_REGISTRY,
                    entities = listOf(MhMembers.ner("Nashville", 0, 9, "GPE")),
                    status = MhMembers.profiles(MhMembers.STORE_NAME to listOf(gpe)),
                )

            val finding = r.findingsOn("Nashville").single()
            finding.kind shouldBe ValueKind.VALUE_KIND_LITERAL
            finding.attributionsList.map { it.binding.ref } shouldContainExactly
                listOf("${MhMembers.STORE_NAME}#Nashville")
        }

        "I-10 — a governed place is decided by its governed reading, profile or not" {
            val profile =
                MhMembers.profiles(
                    MhMembers.STORE_STATE to listOf(gpe),
                    MhMembers.CA_STATE to listOf(gpe),
                    MhMembers.WAREHOUSE_STATE to listOf(gpe),
                )
            val entities = listOf(MhMembers.ner("TN", 10, 12, "GPE"))
            val with = MhMembers.resolve("Stores in TN", MhMembers.e11En(), entities = entities, status = profile)
            val without = MhMembers.resolve("Stores in TN", MhMembers.e11En(), entities = entities)

            with.resolutionState shouldBe without.resolutionState
            with
                .findingsOn("TN")
                .single()
                .attributionsList
                .map { it.binding.ref } shouldContainExactly
                listOf("${MhMembers.STORE_STATE}#TN")
        }

        "I-2 — a question with no place in it never reads the profile" {
            val fuzzy =
                object : FuzzyClient by MhMembers.MemberFuzzy(MhMembers.NAMED_REGISTRY, dcNamed) {
                    val statusCalls = AtomicInteger()

                    override suspend fun getStatus(): FuzzyStatusResponse {
                        statusCalls.incrementAndGet()
                        return dcNamed
                    }
                }
            MhMembers.resolve(
                "How many stores in TN",
                MhMembers.e11Count(),
                registry = MhMembers.NAMED_REGISTRY,
                status = dcNamed,
                fuzzy = fuzzy,
            )
            fuzzy.statusCalls.get() shouldBe 0
        }
    })
