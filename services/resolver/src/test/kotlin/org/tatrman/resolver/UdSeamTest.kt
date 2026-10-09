// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.slf4j.LoggerFactory
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.pipeline.Binder
import org.tatrman.resolver.pipeline.DomainSpanCandidate
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.model.memberEntityByCategory
import org.tatrman.resolver.model.ownersByRef
import org.tatrman.resolver.model.reachByRef
import org.tatrman.resolver.pipeline.GateSpans
import org.tatrman.resolver.pipeline.GatedSpan
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.pipeline.SpanProposal
import org.tatrman.resolver.pipeline.UniversalBinding
import org.tatrman.resolver.pipeline.UniversalSeam
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.UniversalEntityType
import org.tatrman.resolver.v1.ValueKind

/**
 * UD-P1 (ttr-server#118, option 1) — the seam between a universal NER reading and the member
 * reading proposed over the same characters (UD contracts §4, A-UD-2).
 *
 * The rule: a dual-reading span that found a MEMBER supersedes the universal it was proposed over
 * (the member wins the span); a dual-reading span that found no member is WITHDRAWN (the universal
 * stands alone, exactly as before UD). Either way the lattice carries one finding per span.
 * review-108 amended two words of that: "proposed over" is the ENTITY, not a containment test
 * (A-UD-3, F1), and "member" is a member row, not any contender (✅UD-6, A-UD-4, F3).
 */
class UdSeamTest :
    StringSpec({

        // ── the pure rule ─────────────────────────────────────────────────────────────────────

        val tn = UniversalBinding(10, 12, "TN", UniversalEntityType.LOCATION, "TN", "", "stanza")

        fun gated(
            start: Int = 10,
            end: Int = 12,
            // the entity the candidate was proposed over; `null` = not a dual reading
            of: Pair<Int, Int>? = 10 to 12,
            contenders: Int = 1,
            origin: DomainSpanCandidate.Origin = DomainSpanCandidate.Origin.GOVERNED_VALUE,
            sources: List<SourceTag> = List(contenders) { SourceTag.MEMBER },
        ) = GatedSpan(
            DomainSpanCandidate(
                text = "TN",
                start = start,
                end = end,
                gatedEntityRefs = emptyList(),
                categories = emptyList(),
                anchored = origin == DomainSpanCandidate.Origin.GOVERNED_VALUE,
                origin = origin,
                dualReadingOf = of,
            ),
            sources.mapIndexed { i, source ->
                Binder.ClassedMatch(
                    FuzzyMatch
                        .newBuilder()
                        .setCandidateId("TN")
                        .setCategory("c$i")
                        .setSource(source)
                        .build(),
                    EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS,
                )
            },
            ambiguous = sources.size > 1,
        )

        "a dual reading with a contender over the universal supersedes it" {
            val g = gated()
            val seam = UniversalSeam.supersede(listOf(tn), listOf(g))

            seam.universals.shouldBeEmpty()
            seam.superseded shouldContainExactly listOf(tn to g)
            seam.gated shouldContainExactly listOf(g)
        }

        "an ambiguous dual reading still supersedes — an ask among members is a member reading (⚑UD-2)" {
            val g = gated(contenders = 3)
            UniversalSeam.supersede(listOf(tn), listOf(g)).universals.shouldBeEmpty()
        }

        "a dual reading that found nothing is withdrawn and the universal stands (A-UD-2)" {
            val seam = UniversalSeam.supersede(listOf(tn), listOf(gated(contenders = 0)))

            seam.universals shouldContainExactly listOf(tn)
            seam.superseded.shouldBeEmpty()
            // One span, one finding: kept, it would reach the lattice as an unattributed LITERAL
            // beside the grounded place, with a second G3 over the same characters.
            seam.gated.shouldBeEmpty()
        }

        "an accidental overlap never supersedes: the candidate must be a dual reading" {
            val g = gated(of = null)
            val seam = UniversalSeam.supersede(listOf(tn), listOf(g))

            seam.universals shouldContainExactly listOf(tn)
            seam.gated shouldContainExactly listOf(g)
        }

        "A-UD-3 — a dual reading supersedes the ENTITY it was proposed over, whatever its own extent" {
            // The candidate's extent is the parse's tokens, the universal's is the NER engine's.
            // Narrower (an anchor word inside the name is not part of the value) or wider, the
            // candidate says which entity it reads, and that is the one that goes (review-108 F1).
            val narrower = gated(start = 10, end = 11)
            UniversalSeam.supersede(listOf(tn), listOf(narrower)).universals.shouldBeEmpty()

            // NameTag's overshoot: the entity ends two characters after the tokens do
            val overshot =
                UniversalBinding(13, 28, "Frýdku - Místku", UniversalEntityType.LOCATION, "", "cnec:gu", "nametag3")
            val tokens = gated(start = 13, end = 26, of = 13 to 28)
            UniversalSeam.supersede(listOf(overshot), listOf(tokens)).superseded shouldContainExactly
                listOf(overshot to tokens)
        }

        "A-UD-3 — …and never a universal it was not proposed over, however the spans overlap" {
            // A dual reading of some OTHER entity that happens to contain this one's characters
            val other = gated(start = 0, end = 12, of = 0 to 12)
            UniversalSeam.supersede(listOf(tn), listOf(other)).universals shouldContainExactly listOf(tn)
        }

        "✅UD-6 — a dual reading that found only DECLARED rows does not supersede, and is withdrawn (A-UD-4)" {
            // `Sales in Mobile` with a `mobile` channel alias: the place is spelled like a declared
            // term, which is not the member reading that could take the span from it.
            val declared = gated(contenders = 2, sources = listOf(SourceTag.DECLARED, SourceTag.METADATA))
            val seam = UniversalSeam.supersede(listOf(tn), listOf(declared))

            seam.universals shouldContainExactly listOf(tn)
            seam.superseded.shouldBeEmpty()
            seam.gated.shouldBeEmpty()
        }

        "✅UD-6 — a member among declared rows still speaks" {
            val mixed = gated(contenders = 2, sources = listOf(SourceTag.DECLARED, SourceTag.MEMBER))
            UniversalSeam.supersede(listOf(tn), listOf(mixed)).universals.shouldBeEmpty()
        }

        "✅UD-6 — a declared-only governed half does not silence an open half that found a member" {
            // `resolveOpenSiblings` reads the same rule: without it, the governed half's declared
            // hit dropped the open sibling that held the member, and the seam then withdrew the
            // governed half — the member reading lost to a hit that could not win the span.
            val types = MhMembers.entityTypes()

            fun candidate(
                origin: DomainSpanCandidate.Origin,
                categories: List<String>,
            ) = DomainSpanCandidate(
                text = "Storeville",
                start = 10,
                end = 20,
                gatedEntityRefs = emptyList(),
                categories = categories,
                anchored = origin == DomainSpanCandidate.Origin.GOVERNED_VALUE,
                origin = origin,
                dualReadingOf = 10 to 20,
                // as `SpanProposal` sets it on both halves: the anchor's owners (✅UD-7)
                dualReadingScope = listOf(MhMembers.STORE),
            )

            fun row(
                source: SourceTag,
                category: String,
            ) = FuzzyMatch
                .newBuilder()
                .setCandidate("Storeville")
                .setCandidateId(if (source == SourceTag.MEMBER) "Storeville" else "lex:$category")
                .setTargetRef(if (source == SourceTag.MEMBER) "" else category)
                .setCategory(category)
                .setSource(source)
                .setScore(1.0)
                .setMatchMethod("EXACT")
                .build()

            val governed = candidate(DomainSpanCandidate.Origin.GOVERNED_VALUE, listOf(MhMembers.STORE))
            val open = candidate(DomainSpanCandidate.Origin.OPEN_VALUE, types.flatMap { it.categories })
            val response =
                BatchMatchResponse
                    .newBuilder()
                    .addResults(FuzzyMatchResponse.newBuilder().addMatches(row(SourceTag.DECLARED, MhMembers.STORE)))
                    .addResults(FuzzyMatchResponse.newBuilder().addMatches(row(SourceTag.MEMBER, MhMembers.STORE_NAME)))
                    .build()
            val gated =
                GateSpans.gate(listOf(governed, open), response, types, ResolverThresholds.LIVE, emptyMap(), "").gated
            val storeville = UniversalBinding(10, 20, "Storeville", UniversalEntityType.LOCATION, "", "", "stanza")
            val seam = UniversalSeam.supersede(listOf(storeville), gated)

            seam.superseded
                .single()
                .second.candidate.origin shouldBe DomainSpanCandidate.Origin.OPEN_VALUE
            seam.gated.map { it.candidate.origin } shouldContainExactly listOf(DomainSpanCandidate.Origin.OPEN_VALUE)
        }

        // ── ✅UD-7 — which members a dual reading may answer with (UniversalSeam.inScope) ──────────

        fun matchIn(
            category: String,
            source: SourceTag = SourceTag.MEMBER,
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId("v")
                .setCategory(category)
                .setSource(source)
                .build()

        fun dual(
            scope: List<String>,
            of: Pair<Int, Int>? = 10 to 12,
        ) = DomainSpanCandidate(
            text = "TN",
            start = 10,
            end = 12,
            gatedEntityRefs = emptyList(),
            categories = emptyList(),
            anchored = false,
            origin = DomainSpanCandidate.Origin.OPEN_VALUE,
            dualReadingOf = of,
            dualReadingScope = scope,
        )

        fun inScope(
            candidate: DomainSpanCandidate,
            vararg rows: FuzzyMatch,
        ): List<String> {
            val types = MhMembers.udEntityTypes()
            return UniversalSeam
                .inScope(
                    rows.toList(),
                    candidate,
                    types.ownersByRef(),
                    types.reachByRef(),
                    types.memberEntityByCategory(),
                ).map { it.category }
        }

        "✅UD-7 — a member speaks for a dual reading only when the anchor's owner reaches its entity" {
            val all =
                arrayOf(
                    matchIn(MhMembers.STORE_STATE),
                    matchIn(MhMembers.CA_STATE),
                    matchIn(MhMembers.WAREHOUSE_NAME),
                )

            // the owner itself
            inScope(dual(listOf(MhMembers.STORE)), *all) shouldContainExactly listOf(MhMembers.STORE_STATE)
            // a declared reach FROM the owner: `customer_address` declares `Reach(customer)`
            inScope(dual(listOf(MhMembers.CUSTOMER)), *all) shouldContainExactly listOf(MhMembers.CA_STATE)
            // a fact reads its own reach: `store` declares `Reach(store_sales)`, the others do not
            inScope(dual(listOf(MhMembers.STORE_SALES)), *all) shouldContainExactly listOf(MhMembers.STORE_STATE)
        }

        "✅UD-7 — an attribute owner is lifted to its entity, and declared rows are never the filter's business" {
            val kept =
                inScope(
                    dual(listOf(MhMembers.STORE_NAME)),
                    matchIn(MhMembers.STORE_STATE),
                    matchIn(MhMembers.WAREHOUSE_STATE),
                    matchIn(MhMembers.WAREHOUSE, SourceTag.DECLARED),
                )
            kept shouldContainExactly listOf(MhMembers.STORE_STATE, MhMembers.WAREHOUSE)
        }

        "✅UD-7 — a dual reading with no scope answers with no member; any other candidate is untouched" {
            inScope(dual(emptyList()), matchIn(MhMembers.STORE_STATE)).shouldBeEmpty()
            inScope(dual(emptyList(), of = null), matchIn(MhMembers.WAREHOUSE_NAME)) shouldContainExactly
                listOf(MhMembers.WAREHOUSE_NAME)
        }

        "only what survived the open-sibling collapse counts (§4.5): the open sibling alone supersedes, once" {
            // E13's shape after `resolveOpenSiblings`: the governed half found nothing and is gone,
            // the open half found the members. The gate already chose; the seam counts what is left.
            val open = gated(contenders = 3, origin = DomainSpanCandidate.Origin.OPEN_VALUE)
            val seam = UniversalSeam.supersede(listOf(tn), listOf(open))

            seam.superseded
                .single()
                .second.candidate.origin shouldBe DomainSpanCandidate.Origin.OPEN_VALUE
            seam.superseded
                .single()
                .second.contenders.size shouldBe 3
        }

        "no dual reading anywhere ⇒ the inputs come back untouched (I-2)" {
            val other = gated(start = 0, end = 6, of = null, origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE)
            val seam = UniversalSeam.supersede(listOf(tn), listOf(other))

            seam.universals shouldBe listOf(tn)
            seam.gated shouldBe listOf(other)
            seam.superseded.shouldBeEmpty()
        }

        // ── through the pipeline (MhMembers harness: §8.5 registry, member vocabularies flagged) ──

        fun ResolveResponse.findingsOn(text: String) = resolutionState.valuesList.filter { it.span.text == text }

        fun ResolveResponse.gapsOn(text: String) =
            resolutionState.gapsList.filter { it.span.text == text }.map { it.kind }

        fun place(
            text: String,
            start: Int,
            label: String = "GPE",
        ): List<NerEntity> = listOf(MhMembers.ner(text, start, start + text.length, label))

        "I-3 `Stores in TN` + GPE — the member wins the span: one LITERAL finding, attributed, no place" {
            val r = MhMembers.resolve("Stores in TN", MhMembers.e11En(), entities = place("TN", 10))

            val tnFinding = r.findingsOn("TN").single()
            tnFinding.kind shouldBe ValueKind.VALUE_KIND_LITERAL
            tnFinding.hasGrounding() shouldBe false
            tnFinding.attributionsList.map { it.binding.ref } shouldContainExactly listOf("${MhMembers.STORE_STATE}#TN")
            r.gapsOn("TN").shouldBeEmpty()
        }

        "I-3 — and the door agrees: no universal binding, the member is a domain one" {
            // The COUNT form, because `Stores` alone is the store/store_sales object homonym and asks.
            val r =
                MhMembers.resolve("How many stores in TN", MhMembers.e11Count(), entities = place("TN", 19))

            r.hasAwaiting() shouldBe false
            r.resolution.bindingsList.count { it.hasUniversal() } shouldBe 0
            r.resolution.bindingsList
                .single { it.domain.rawText == "TN" }
                .domain.memberOf shouldBe MhMembers.STORE_STATE
            r.resolution.rationale shouldContain "0 universal"
        }

        "I-4 `Stores in Paris` — no member anywhere: the place stands, alone, with its G3" {
            val tokens =
                arrayOf(
                    MhMembers.tok("Stores", 0, 6, "store", "NOUN", 0, "root"),
                    MhMembers.tok("in", 7, 9, "in", "ADP", 3, "case"),
                    MhMembers.tok("Paris", 10, 15, "Paris", "PROPN", 1, "nmod"),
                )
            val r = MhMembers.resolve("Stores in Paris", tokens, entities = place("Paris", 10))

            val paris = r.findingsOn("Paris").single()
            paris.kind shouldBe ValueKind.VALUE_KIND_GROUNDED
            paris.grounding.kind shouldBe UniversalEntityType.LOCATION.name
            paris.attributionsList.shouldBeEmpty()
            r.gapsOn("Paris") shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)
            // …and the question WAS asked: proposal is unconditional, only the outcome is not
            val parse =
                MhMembers
                    .parse(
                        "Stores in Paris",
                        tokens,
                    ).toBuilder()
                    .addAllEntities(place("Paris", 10))
                    .build()
            SpanProposal
                .proposeDomainSpans(parse, MhMembers.entityTypes())
                .filter { it.text == "Paris" }
                .map { it.origin to it.dualReading } shouldContainExactly
                listOf(DomainSpanCandidate.Origin.GOVERNED_VALUE to true, DomainSpanCandidate.Origin.OPEN_VALUE to true)
        }

        "I-5 `Customers in TN` — the open sibling + M3 bind the customer's address state; the place is superseded" {
            val r = MhMembers.resolve("Customers in TN", MhMembers.e13En(), entities = place("TN", 13))

            val tnFinding = r.findingsOn("TN").single()
            tnFinding.kind shouldBe ValueKind.VALUE_KIND_LITERAL
            tnFinding.attributionsList.map { it.binding.ref } shouldContainExactly listOf("${MhMembers.CA_STATE}#TN")
            r.resolution.bindingsList.count { it.hasUniversal() } shouldBe 0
        }

        "I-6 `Sales in TN` — a fact governs no member, and it reaches ONE owner of the three: that one binds (✅UD-7)" {
            // The open half finds `TN` in three state vocabularies. This registry declares a reach
            // from `store_sales` to `store` only, so the customer's and the warehouse's `TN` are not
            // members the sentence scoped, and the dual reading answers with the store's. (Before
            // ✅UD-7 this asked among all three. With NER off, the plain pair still does: MH M2.)
            val r = MhMembers.resolve("Sales in TN", MhMembers.e12En(), entities = place("TN", 9))

            r.hasAwaiting() shouldBe false
            val tnFinding = r.findingsOn("TN").single()
            tnFinding.kind shouldBe ValueKind.VALUE_KIND_LITERAL
            tnFinding.attributionsList.map { it.binding.ref } shouldContainExactly listOf("${MhMembers.STORE_STATE}#TN")
            r.resolution.bindingsList.count { it.hasUniversal() } shouldBe 0
        }

        "I-7 bare `TN` and `Nashville stores` — nothing governs the place, so it stays a place" {
            val bare = MhMembers.resolve("TN", MhMembers.e12Bare(), entities = place("TN", 0))
            bare.findingsOn("TN").single().kind shouldBe ValueKind.VALUE_KIND_GROUNDED
            bare.gapsOn("TN") shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)

            val preModifier: Array<Token> =
                arrayOf(
                    MhMembers.tok("Nashville", 0, 9, "Nashville", "PROPN", 2, "compound"),
                    MhMembers.tok("stores", 10, 16, "store", "NOUN", 0, "root"),
                )
            val r = MhMembers.resolve("Nashville stores", preModifier, entities = place("Nashville", 0))
            r.findingsOn("Nashville").single().kind shouldBe ValueKind.VALUE_KIND_GROUNDED
        }

        // ── review-108 ──────────────────────────────────────────────────────────────────────────

        "F1 — NameTag's overshooting end: the member still takes the span, and the place goes with it (A-UD-3)" {
            // `Frýdku - Místku` (the three words NameTag found, joined) is 15 characters where the
            // question has 13, so the entity ends at 33 and the tokens at 31. Containment said
            // "not covered": the member kept its finding AND the place kept its own, with a G3 and
            // a universal binding beside the domain one — two values for one filter.
            val tokens =
                arrayOf(
                    MhMembers.tok("Kolik", 0, 5, "kolik", "DET", 2, "det:numgov"),
                    MhMembers.tok("prodejen", 6, 14, "prodejna", "NOUN", 0, "root"),
                    MhMembers.tok("ve", 15, 17, "v", "ADP", 4, "case"),
                    MhMembers.tok("Frýdku", 18, 24, "Frýdek", "PROPN", 2, "nmod"),
                    MhMembers.tok("-", 24, 25, "-", "PUNCT", 6, "punct"),
                    MhMembers.tok("Místku", 25, 31, "Místek", "PROPN", 4, "flat"),
                )
            val r =
                MhMembers.resolve(
                    "Kolik prodejen ve Frýdku-Místku",
                    tokens,
                    lang = "cs",
                    entities = listOf(MhMembers.ner("Frýdku - Místku", 18, 33, "LOCATION", "cnec:gu")),
                )

            val values = r.resolutionState.valuesList.filter { it.span.start >= 18 }
            values.map { it.kind } shouldContainExactly listOf(ValueKind.VALUE_KIND_LITERAL)
            values
                .single()
                .attributionsList
                .map { it.binding.ref } shouldContainExactly listOf("${MhMembers.STORE_NAME}#Frýdek-Místek")
            r.resolutionState.gapsList
                .filter { it.span.start >= 18 }
                .shouldBeEmpty()
            r.hasAwaiting() shouldBe false
            r.resolution.bindingsList.count { it.hasUniversal() } shouldBe 0
            r.resolution.rationale shouldContain "0 universal"
        }

        "F2 — a coarse-MISC number is one grounded value, not two findings on one span" {
            val tokens =
                arrayOf(
                    MhMembers.tok("Stores", 0, 6, "store", "NOUN", 0, "root"),
                    MhMembers.tok("501001", 7, 13, "501001", "NUM", 1, "nummod"),
                )
            val r =
                MhMembers.resolve("Stores 501001", tokens, entities = listOf(MhMembers.ner("501001", 7, 13, "MISC")))

            r.findingsOn("501001").map { it.kind } shouldContainExactly listOf(ValueKind.VALUE_KIND_GROUNDED)
            // …and since #139 a number AFTER the noun it names is owed a lookup: a G3, not silence
            r.gapsOn("501001") shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)
        }

        "F3 — a place spelled like a declared term stays a place (✅UD-6)" {
            // The fixture's matcher finds the `store` anchor in `Storeville` (its stem heuristic),
            // so both halves of the dual reading come back with DECLARED rows and no member. That
            // is a reading of the spelling, not of the value: the place stands, and nobody is
            // asked which OBJECT `Storeville` is.
            val tokens =
                arrayOf(
                    MhMembers.tok("Stores", 0, 6, "store", "NOUN", 0, "root"),
                    MhMembers.tok("in", 7, 9, "in", "ADP", 3, "case"),
                    MhMembers.tok("Storeville", 10, 20, "Storeville", "PROPN", 1, "nmod"),
                )
            val r = MhMembers.resolve("Stores in Storeville", tokens, entities = place("Storeville", 10))

            val storeville = r.findingsOn("Storeville").single()
            storeville.kind shouldBe ValueKind.VALUE_KIND_GROUNDED
            r.gapsOn("Storeville") shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)
            r.awaiting.optionsList.none { it.span.text == "Storeville" } shouldBe true
        }

        "F4 — the seam's INFO line carries ids and counts, never the user's words" {
            val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
            val logger = ctx.getLogger(ResolverPipeline::class.java)
            val previousLevel = logger.level
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.level = Level.DEBUG
            logger.addAppender(appender)
            try {
                MhMembers.resolve("Stores in TN", MhMembers.e11En(), entities = place("TN", 10))

                val seam = appender.list.filter { it.formattedMessage.startsWith("seam:") }
                seam.filter { it.level == Level.INFO }.map { it.formattedMessage } shouldContainExactly
                    listOf("seam: LOCATION [10,12) superseded by GOVERNED_VALUE (1 contenders) conversation_id=mh-m")
                // the words are there for whoever turns DEBUG on, and only for them
                seam.filter { it.level == Level.DEBUG }.map { it.formattedMessage } shouldContainExactly
                    listOf("seam: [10,12) is \"TN\"")
            } finally {
                logger.detachAppender(appender)
                logger.level = previousLevel
                appender.stop()
            }
        }

        // ── C5 live drill → ✅UD-7 (A-UD-7) ────────────────────────────────────────────────────────

        fun dallas(anchor: Token) =
            arrayOf(
                anchor,
                MhMembers.tok("in", anchor.charEnd + 1, anchor.charEnd + 3, "in", "ADP", 3, "case"),
                MhMembers.tok("Dallas", anchor.charEnd + 4, anchor.charEnd + 10, "Dallas", "PROPN", 1, "nmod"),
            )

        // The lattice, not the door: `Stores` alone is the store/store_sales object homonym and asks.
        fun ResolveResponse.standsAsPlace() {
            val finding = findingsOn("Dallas").single()
            finding.kind shouldBe ValueKind.VALUE_KIND_GROUNDED
            finding.grounding.kind shouldBe UniversalEntityType.LOCATION.name
            finding.attributionsList.shouldBeEmpty()
            gapsOn("Dallas") shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)
            awaiting.optionsList.none { it.span.text == "Dallas" } shouldBe true
        }

        "✅UD-7 — `Stores in Dallas`: a warehouse's name is not the store's member, so the place stands" {
            // hartland, live: `Dallas` → `warehouse_name` "Dallas DC" through the OPEN half, alone, so M3
            // (which breaks ties) never saw it and it bound. The store reaches no warehouse.
            val r =
                MhMembers.resolve(
                    "Stores in Dallas",
                    dallas(MhMembers.tok("Stores", 0, 6, "store", "NOUN", 0, "root")),
                    entities = place("Dallas", 10),
                    registry = MhMembers.UD_REGISTRY,
                )
            r.standsAsPlace()
        }

        "✅UD-7 — `Customers in Dallas` and `Sales in Dallas`: neither owner reaches the warehouse either" {
            MhMembers
                .resolve(
                    "Customers in Dallas",
                    dallas(MhMembers.tok("Customers", 0, 9, "customer", "NOUN", 0, "root")),
                    entities = place("Dallas", 13),
                    registry = MhMembers.UD_REGISTRY,
                ).standsAsPlace()
            MhMembers
                .resolve(
                    "Sales in Dallas",
                    dallas(MhMembers.tok("Sales", 0, 5, "sale", "NOUN", 0, "root")),
                    entities = place("Dallas", 9),
                    registry = MhMembers.UD_REGISTRY,
                ).standsAsPlace()
        }

        "✅UD-7 — `Warehouses in Dallas`: the warehouse's own name IS its member, and takes the span" {
            val r =
                MhMembers.resolve(
                    "Warehouses in Dallas",
                    dallas(MhMembers.tok("Warehouses", 0, 10, "warehouse", "NOUN", 0, "root")),
                    entities = place("Dallas", 14),
                    registry = MhMembers.UD_REGISTRY,
                )

            val finding = r.findingsOn("Dallas").single()
            finding.kind shouldBe ValueKind.VALUE_KIND_LITERAL
            finding.attributionsList.map { it.binding.ref } shouldContainExactly
                listOf("${MhMembers.WAREHOUSE_NAME}#Dallas DC")
            r.resolution.bindingsList.count { it.hasUniversal() } shouldBe 0
        }

        // ── ✅UD-8 — a place a MEASURE governs is its fact's (hartland LR S5, live) ─────────────────

        val valueOrigins = setOf(DomainSpanCandidate.Origin.GOVERNED_VALUE, DomainSpanCandidate.Origin.OPEN_VALUE)

        // The live en parse of `Marketplace revenue for Dallas DC` (Stanza, hartland 2026-10-09): the
        // multi-word measure phrase heads the sentence and the DC name hangs off its head.
        fun marketplaceFor(
            place: String,
            placeTokens: (start: Int) -> List<Token>,
        ): Array<Token> =
            arrayOf(
                MhMembers.tok("Marketplace", 0, 11, "marketplace", "NOUN", 2, "compound"),
                MhMembers.tok("revenue", 12, 19, "revenue", "NOUN", 0, "root"),
                MhMembers.tok("for", 20, 23, "for", "ADP", 4, "case"),
            ) + placeTokens(24).also { require(it.first().text == place.substringBefore(' ')) }

        fun dallasDc(start: Int) =
            listOf(
                MhMembers.tok("Dallas", start, start + 6, "Dallas", "PROPN", 2, "nmod"),
                MhMembers.tok("DC", start + 7, start + 9, "DC", "PROPN", 4, "flat"),
            )

        fun ResolveResponse.boundTo(
            text: String,
            ref: String,
        ) {
            val finding = findingsOn(text).single()
            finding.kind shouldBe ValueKind.VALUE_KIND_LITERAL
            finding.hasGrounding() shouldBe false
            finding.attributionsList.map { it.binding.ref } shouldContainExactly listOf(ref)
            gapsOn(text).shouldBeEmpty()
            resolution.bindingsList.count { it.hasUniversal() } shouldBe 0
        }

        "✅UD-8 `Marketplace revenue for Dallas DC` — a multi-word measure's place is its fact's member" {
            // Before ✅UD-8: the place stood with a G3, the lookup round could not attach a member to
            // a span the domain never proposed, and the door answered for every warehouse.
            val r =
                MhMembers.resolve(
                    "Marketplace revenue for Dallas DC",
                    marketplaceFor("Dallas DC", ::dallasDc),
                    entities = place("Dallas DC", 24),
                    registry = MhMembers.UD_REGISTRY,
                )

            r.boundTo("Dallas DC", "${MhMembers.WAREHOUSE_NAME}#Dallas DC")
            r.resolutionState.mentionsList
                .single { it.span.text == "Marketplace revenue" }
                .bindingsList
                .map { it.ref } shouldContainExactly listOf(MhMembers.MARKETPLACE_REVENUE)
        }

        "✅UD-8 — the dual reading is scoped to the measure's FACT, on both halves" {
            val parse =
                MhMembers
                    .parse("Marketplace revenue for Dallas DC", marketplaceFor("Dallas DC", ::dallasDc))
                    .toBuilder()
                    .addAllEntities(place("Dallas DC", 24))
                    .build()

            SpanProposal
                .proposeDomainSpans(parse, MhMembers.udEntityTypes())
                .filter { it.text == "Dallas DC" }
                .map { Triple(it.origin, it.dualReadingOf, it.dualReadingScope) } shouldContainExactly
                listOf(
                    Triple(DomainSpanCandidate.Origin.GOVERNED_VALUE, 24 to 33, listOf(MhMembers.CATALOG_SALES)),
                    Triple(DomainSpanCandidate.Origin.OPEN_VALUE, 24 to 33, listOf(MhMembers.CATALOG_SALES)),
                )
        }

        "✅UD-8 `Tržby z tržiště v roce 2025 pro Dallas DC` — the same in Czech, and `roce` stays nobody's value" {
            // The live cs parse (MorphoDiTa + UDPipe, NameTag `cnec:gu`, hartland 2026-10-09). `roce` is
            // a NOUN `nmod` of the phrase's head exactly as `Dallas` is: a multi-word anchor proposes
            // the place only, or the year phrase would become a governed value of the fact.
            val tokens =
                arrayOf(
                    MhMembers.tok("Tržby", 0, 5, "tržba", "NOUN", 0, "root"),
                    MhMembers.tok("z", 6, 7, "z", "ADP", 3, "case"),
                    MhMembers.tok("tržiště", 8, 15, "tržiště", "NOUN", 1, "nmod"),
                    MhMembers.tok("v", 16, 17, "v", "ADP", 5, "case"),
                    MhMembers.tok("roce", 18, 22, "rok", "NOUN", 1, "nmod"),
                    MhMembers.tok("2025", 23, 27, "2025", "NUM", 5, "nummod"),
                    MhMembers.tok("pro", 28, 31, "pro", "ADP", 8, "case"),
                    MhMembers.tok("Dallas", 32, 38, "Dallas", "PROPN", 1, "dep"),
                    MhMembers.tok("DC", 39, 41, "DC", "NOUN", 8, "flat:foreign"),
                )
            val entities = listOf(MhMembers.ner("Dallas DC", 32, 41, "LOCATION", "cnec:gu"))
            val text = "Tržby z tržiště v roce 2025 pro Dallas DC"

            MhMembers
                .resolve(text, tokens, lang = "cs", entities = entities, registry = MhMembers.UD_REGISTRY)
                .boundTo("Dallas DC", "${MhMembers.WAREHOUSE_NAME}#Dallas DC")

            val parse =
                MhMembers
                    .parse(text, tokens, "cs")
                    .toBuilder()
                    .addAllEntities(entities)
                    .build()
            SpanProposal
                .proposeDomainSpans(parse, MhMembers.udEntityTypes())
                .filter { it.origin in valueOrigins }
                .map { it.text }
                .distinct() shouldContainExactly listOf("Dallas DC")
        }

        "✅UD-8 `Turnover for Dallas DC` — a single-word measure governs its fact's place too" {
            val tokens =
                arrayOf(
                    MhMembers.tok("Turnover", 0, 8, "turnover", "NOUN", 0, "root"),
                    MhMembers.tok("for", 9, 12, "for", "ADP", 3, "case"),
                    MhMembers.tok("Dallas", 13, 19, "Dallas", "PROPN", 1, "nmod"),
                    MhMembers.tok("DC", 20, 22, "DC", "PROPN", 3, "flat"),
                )
            MhMembers
                .resolve(
                    "Turnover for Dallas DC",
                    tokens,
                    entities = place("Dallas DC", 13),
                    registry = MhMembers.UD_REGISTRY,
                ).boundTo("Dallas DC", "${MhMembers.WAREHOUSE_NAME}#Dallas DC")
        }

        "✅UD-8 `Marketplace revenue for Nashville` — a member the fact does not reach leaves the place standing" {
            // `Nashville` is a store's name, and the marketplace fact declares no reach to `store`
            val r =
                MhMembers.resolve(
                    "Marketplace revenue for Nashville",
                    marketplaceFor("Nashville") { start ->
                        listOf(MhMembers.tok("Nashville", start, start + 9, "Nashville", "PROPN", 2, "nmod"))
                    },
                    entities = place("Nashville", 24),
                    registry = MhMembers.UD_REGISTRY,
                )

            val finding = r.findingsOn("Nashville").single()
            finding.kind shouldBe ValueKind.VALUE_KIND_GROUNDED
            finding.attributionsList.shouldBeEmpty()
            r.gapsOn("Nashville") shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)
        }

        "✅UD-8 — a multi-word ENTITY anchor governs a place, and still no ordinary value" {
            // Before ✅UD-8 a multi-word anchor governed nothing at all. It now offers the dual
            // reading (the only way in for a place) and keeps declining everything else.
            val types =
                MhMembers.udEntityTypes().map {
                    if (it.ref == MhMembers.WAREHOUSE) it.copy(anchors = it.anchors + "distribution centre") else it
                }

            fun proposedValues(
                arg: Token,
                entities: List<NerEntity> = emptyList(),
            ): List<Pair<DomainSpanCandidate.Origin, List<String>>> {
                val tokens =
                    listOf(
                        MhMembers.tok("Distribution", 0, 12, "distribution", "NOUN", 2, "compound"),
                        MhMembers.tok("centre", 13, 19, "centre", "NOUN", 0, "root"),
                        MhMembers.tok("for", 20, 23, "for", "ADP", 4, "case"),
                        arg,
                    )
                val parse =
                    MhMembers
                        .parse("", tokens.toTypedArray())
                        .toBuilder()
                        .addAllEntities(entities)
                        .build()
                return SpanProposal
                    .proposeDomainSpans(parse, types)
                    .filter { it.start == 24 }
                    .map { it.origin to it.dualReadingScope }
            }

            proposedValues(
                MhMembers.tok("Dallas", 24, 30, "Dallas", "PROPN", 2, "nmod"),
                place("Dallas", 24),
            ) shouldContainExactly
                listOf(
                    DomainSpanCandidate.Origin.GOVERNED_VALUE to listOf(MhMembers.WAREHOUSE),
                    DomainSpanCandidate.Origin.OPEN_VALUE to listOf(MhMembers.WAREHOUSE),
                )
            proposedValues(MhMembers.tok("books", 24, 29, "book", "NOUN", 2, "nmod")).shouldBeEmpty()
        }

        "✅UD-8 — a measure still governs no ordinary value: only a place gets the reading" {
            // `books` is no place, so nothing under either measure anchor proposes it as a value
            fun proposedValues(
                text: String,
                tokens: Array<Token>,
            ) = SpanProposal
                .proposeDomainSpans(MhMembers.parse(text, tokens), MhMembers.udEntityTypes())
                .filter { it.origin in valueOrigins }

            proposedValues(
                "Marketplace revenue for books",
                marketplaceFor("books") { start ->
                    listOf(MhMembers.tok("books", start, start + 5, "book", "NOUN", 2, "nmod"))
                },
            ).shouldBeEmpty()
            proposedValues(
                "Turnover for books",
                arrayOf(
                    MhMembers.tok("Turnover", 0, 8, "turnover", "NOUN", 0, "root"),
                    MhMembers.tok("for", 9, 12, "for", "ADP", 3, "case"),
                    MhMembers.tok("books", 13, 18, "book", "NOUN", 1, "nmod"),
                ),
            ).shouldBeEmpty()
        }

        // ── ✅UD-9 — the NER names the city, the parse names the place (`Praha DC`) ──────────────────

        // The live cs shape of `… pro Praha DC` (NameTag `cnec:gu` on `Praha` alone, `DC` its `nmod`),
        // spelled with a DC this registry holds.
        fun dcTail(vararg tail: Token): Array<Token> =
            arrayOf(
                MhMembers.tok("Tržby", 0, 5, "tržba", "NOUN", 0, "root"),
                MhMembers.tok("z", 6, 7, "z", "ADP", 3, "case"),
                MhMembers.tok("tržiště", 8, 15, "tržiště", "NOUN", 1, "nmod"),
                MhMembers.tok("pro", 16, 19, "pro", "ADP", 5, "case"),
                MhMembers.tok("Dallas", 20, 26, "Dallas", "PROPN", 1, "dep"),
                *tail,
            )

        val cityOnly = listOf(MhMembers.ner("Dallas", 20, 26, "LOCATION", "cnec:gu"))

        "✅UD-9 `Tržby z tržiště pro Dallas DC`, NER on the city alone — the member takes the whole name" {
            // Before ✅UD-9 the reading was `Dallas`: it bound, and `DC` was left an unbound mention
            // (G1) that the turn then asked about — „Nerozumím výrazu „DC““.
            val r =
                MhMembers.resolve(
                    "Tržby z tržiště pro Dallas DC",
                    dcTail(MhMembers.tok("DC", 27, 29, "DC", "NOUN", 5, "nmod")),
                    lang = "cs",
                    entities = cityOnly,
                    registry = MhMembers.UD_REGISTRY,
                )

            r.boundTo("Dallas DC", "${MhMembers.WAREHOUSE_NAME}#Dallas DC")
            r.resolutionState.mentionsList
                .filter { it.span.text == "DC" }
                .shouldBeEmpty()
            r.hasAwaiting() shouldBe false
        }

        "✅UD-9 — the tail stops at the first word that does not continue the name" {
            fun dualText(tokens: Array<Token>): List<String> =
                SpanProposal
                    .proposeDomainSpans(
                        MhMembers
                            .parse("", tokens, "cs")
                            .toBuilder()
                            .addAllEntities(cityOnly)
                            .build(),
                        MhMembers.udEntityTypes(),
                    ).filter { it.dualReading }
                    .map { it.text }
                    .distinct()

            // a preposition breaks the run, though `lednu` hangs off the city
            dualText(
                dcTail(
                    MhMembers.tok("v", 27, 28, "v", "ADP", 7, "case"),
                    MhMembers.tok("lednu", 29, 34, "leden", "NOUN", 5, "nmod"),
                ),
            ) shouldContainExactly listOf("Dallas")
            // an anchor word is its own mention, never a place's tail (`store` is the store anchor)
            dualText(dcTail(MhMembers.tok("store", 27, 32, "store", "NOUN", 5, "nmod"))) shouldContainExactly
                listOf("Dallas")
            // a word governed by something else is not the place's either
            dualText(dcTail(MhMembers.tok("DC", 27, 29, "DC", "NOUN", 1, "nmod"))) shouldContainExactly
                listOf("Dallas")
        }
    })
