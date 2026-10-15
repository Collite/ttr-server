// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.Provenance
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.fuzzy.v1.TokenHit
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.model.kindsByRef
import org.tatrman.resolver.model.memberEntityByCategory
import org.tatrman.resolver.model.ownersByRef
import org.tatrman.resolver.model.reachByRef
import org.tatrman.resolver.pipeline.CoordinatedValues
import org.tatrman.resolver.pipeline.DomainSpanCandidate
import org.tatrman.resolver.pipeline.DomainSpanCandidate.Origin
import org.tatrman.resolver.pipeline.FrameRolePreps
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.pipeline.SlotHint
import org.tatrman.resolver.pipeline.SlotHints
import org.tatrman.resolver.pipeline.SpanProposal
import org.tatrman.resolver.v1.Registry

/**
 * ttr-demo#62, the coordination ruling (2026-10-14), over member values — the three parts in isolation:
 * span proposal reads a conjunct as governed by what governs its coordination, `SlotHints` names the
 * coordination, and `CoordinatedValues` decides the reading the conjuncts share. The whole pipeline over
 * hartland's live parses is `CoordinatedPlacesTest`, whose recorded parses and registry these reuse.
 */
class CoordinatedValuesTest :
    StringSpec({

        // ── span proposal: a conjunct is governed by what governs the coordination ───────────────

        "proposal — „Tržby pro Brno a Liberec“: Liberec is the measure's governed place too, scoped to its fact" {
            val proposed = SpanProposal.proposeDomainSpans(parse("trzby-pro-brno-a-liberec"), TYPES)

            val pair =
                listOf(Origin.GOVERNED_VALUE to listOf(CHANNEL_SALES), Origin.OPEN_VALUE to listOf(CHANNEL_SALES))
            for (place in listOf("Brno", "Liberec")) {
                val readings = proposed.filter { it.text == place }.map { it.origin to it.dualReadingScope }
                readings shouldContainExactlyInAnyOrder pair
            }
        }

        "proposal — under the store word the conjunct takes the store word's scope" {
            val proposed = SpanProposal.proposeDomainSpans(parse("trzby-prodejen-brno-a-liberec"), TYPES)

            proposed
                .filter { it.text == "Liberec" }
                .map { it.dualReadingScope.toSet() }
                .distinct() shouldContainExactly listOf(setOf(STORE, STORE_SALES))
        }

        "proposal — a conjunct the NER did not type is not a governed place: it keeps its own path" {
            // Without its LOCATION entity `Liberec` is an untyped proper noun: proposed as one (gated to
            // everything, as always), never as the measure's governed pair.
            val untyped =
                parse("trzby-pro-brno-a-liberec")
                    .toBuilder()
                    .clearEntities()
                    .addAllEntities(parse("trzby-pro-brno-a-liberec").entitiesList.filterNot { it.text == "Liberec" })
                    .build()

            SpanProposal
                .proposeDomainSpans(untyped, TYPES)
                .filter { it.text == "Liberec" }
                .map { it.origin } shouldContainExactly listOf(Origin.PROPER_NOUN)
        }

        "proposal — a lone place proposes exactly what it did" {
            SpanProposal
                .proposeDomainSpans(parse("trzby-pro-brno"), TYPES)
                .filter { it.origin in setOf(Origin.GOVERNED_VALUE, Origin.OPEN_VALUE) }
                .map { it.text } shouldContainExactly listOf("Brno", "Brno")
        }

        // ── SlotHints: the conjuncts of one coordination share its group ─────────────────────────

        "slots — both halves of both conjuncts carry the first conjunct's head; no governor under a fact" {
            val stamped = stamp("trzby-pro-brno-a-liberec")

            stamped
                .filter { it.origin in setOf(Origin.GOVERNED_VALUE, Origin.OPEN_VALUE) }
                .map { Triple(it.text, it.slot.coordGroup, it.slot.governorRef) } shouldContainExactlyInAnyOrder
                listOf(
                    Triple("Brno", 2, ""),
                    Triple("Brno", 2, ""),
                    Triple("Liberec", 2, ""),
                    Triple("Liberec", 2, ""),
                )
        }

        "slots — under the store word the group carries the store as its governor" {
            stamp("trzby-prodejen-brno-a-liberec")
                .filter { it.origin == Origin.GOVERNED_VALUE }
                .map { it.text to it.slot } shouldContainExactlyInAnyOrder
                listOf(
                    "Brno" to SlotHint(governorRef = STORE, coordGroup = 2),
                    "Liberec" to SlotHint(governorRef = STORE, coordGroup = 2),
                )
        }

        "slots — a value in no coordination has no group" {
            stamp("trzby-pro-brno")
                .filter { it.origin in setOf(Origin.GOVERNED_VALUE, Origin.OPEN_VALUE) }
                .map { it.slot.coordGroup }
                .distinct() shouldContainExactly listOf(-1)
        }

        // ── the rule ────────────────────────────────────────────────────────────────────────────

        "rule — one ambiguous (store, DC), one not (store): both conjuncts keep the STORE, either order" {
            for (order in listOf(listOf(BRNO, LIBEREC), listOf(LIBEREC, BRNO))) {
                val group = coordination(*order.toTypedArray())
                decide(group) shouldBe group.candidates.indices.associateWith { STORE }
            }
        }

        "rule — both ambiguous with the same readings (Brno a Plzeň): nothing is decided, the scope stands" {
            decide(coordination(BRNO, PLZEN)).shouldBeEmpty()
        }

        "rule — both ambiguous, even where they meet at one entity: the ruling is about one ambiguous conjunct" {
            val storeOrReason = Place("Brod", member("Brod", STORE_NAME_CAT), member("Brod", REASON_CAT))
            decide(coordination(BRNO, storeOrReason)).shouldBeEmpty()
        }

        "rule — both unambiguous: nothing to resolve, even where they agree" {
            decide(coordination(LIBEREC, Place("Olomouc", member("Olomouc", STORE_NAME_CAT)))).shouldBeEmpty()
        }

        "rule — the intersection may be the reading the scope would have taken anyway" {
            // a DC-only conjunct (its reason row is below the bind floor): both keep the warehouse
            val hradec =
                Place(
                    "Hradec Králové",
                    member("Hradec Králové DC", WAREHOUSE_NAME_CAT),
                    member("Hradec", REASON_CAT, 0.4),
                )
            decide(coordination(BRNO, hradec)).values.toSet() shouldBe setOf(WAREHOUSE)
        }

        "rule — the unambiguous reading is not among the ambiguous one's: the intersection is empty" {
            decide(coordination(BRNO, Place("Kladno", member("Kladno", REASON_CAT)))).shouldBeEmpty()
        }

        "rule — three conjuncts: the unambiguous ones agree, so every ambiguous one that can takes it" {
            val three = coordination(BRNO, LIBEREC, PLZEN)
            decide(three) shouldBe three.candidates.indices.associateWith { STORE }
            // …and when the unambiguous ones disagree, nothing is decided
            val dcOnly = Place("Hradec Králové", member("Hradec Králové DC", WAREHOUSE_NAME_CAT))
            decide(coordination(BRNO, LIBEREC, dcOnly)).shouldBeEmpty()
        }

        "rule — a reading the gate would not admit is no reading: WEAK and out-of-band rows do not count" {
            // the DC row below the class floor (WEAK) → `Brno` has one reading, so both are unambiguous
            val weakDc =
                Place("Brno", member("Brno", STORE_NAME_CAT), member("Brno DC", WAREHOUSE_NAME_CAT, 0.6, "typo"))
            decide(coordination(weakDc, LIBEREC)).shouldBeEmpty()
            // the DC row a class below the store's → the same
            val lowerDc =
                Place("Brno", member("Brno", STORE_NAME_CAT), member("Brno DC", WAREHOUSE_NAME_CAT, 0.9, "typo"))
            decide(coordination(lowerDc, LIBEREC)).shouldBeEmpty()
        }

        "rule — a conjunct with no member reading takes no part" {
            decide(coordination(BRNO, Place("Liberci"))).shouldBeEmpty()
        }

        "rule — an ENTITY governor decides by its own scope: the coordination never overrides it" {
            decide(coordination(BRNO, LIBEREC, governor = WAREHOUSE)).shouldBeEmpty()
        }

        "rule — values in no coordination are not touched" {
            val loose = coordination(BRNO, LIBEREC)
            decide(Coord(loose.candidates.map { it.copy(slot = SlotHint.NONE) }, loose.rows)).shouldBeEmpty()
        }

        "keep — drops the member rows of every other entity and nothing else" {
            val declared =
                FuzzyMatch
                    .newBuilder()
                    .setCandidateId("lex:brno")
                    .setCandidate("brno")
                    .setCategory(STORE)
                    .setTargetRef(STORE)
                    .setSource(SourceTag.DECLARED)
                    .setScore(1.0)
                    .build()
            val rows = BRNO.rows + declared
            CoordinatedValues.keep(rows, STORE, OWNERS, MEMBER_OWNERS).map { it.candidateId } shouldContainExactly
                listOf("Brno", "lex:brno")
        }
    }) {
    /** A place and the member rows the matcher answers for it over every vocabulary. */
    private class Place(
        val text: String,
        vararg rows: FuzzyMatch,
    ) {
        val rows: List<FuzzyMatch> = rows.toList()
    }

    /** One coordination's candidates, and the rows the batch answered for each (by index). */
    private class Coord(
        val candidates: List<DomainSpanCandidate>,
        val rows: List<List<FuzzyMatch>>,
    )

    private companion object {
        const val CHANNEL_SALES = "er.entity.channel_sales"
        const val STORE = "er.entity.store"
        const val STORE_SALES = "er.entity.store_sales"
        const val WAREHOUSE = "er.entity.warehouse"
        const val STORE_NAME_CAT = "er.entity.store.store_name"
        const val WAREHOUSE_NAME_CAT = "er.entity.warehouse.warehouse_name"
        const val REASON_CAT = "er.entity.reason.reason_desc"

        val REGISTRY: Registry =
            Registry
                .newBuilder()
                .also { JsonFormat.parser().merge(resource("registry.json"), it) }
                .build()
        val TYPES = ResolverPipeline.fromProto(REGISTRY, ResolverThresholds.LIVE).entityTypes
        val OWNERS = TYPES.ownersByRef()
        val KINDS = TYPES.kindsByRef()
        val REACH = TYPES.reachByRef()
        val MEMBER_OWNERS = TYPES.memberEntityByCategory()

        /** A member row as v2 lex-matcher writes one: [kind] is every token hit's kind. */
        fun member(
            value: String,
            category: String,
            score: Double = 1.01,
            kind: String = "exact",
        ): FuzzyMatch {
            val token = value.lowercase()
            return FuzzyMatch
                .newBuilder()
                .setCandidateId(value)
                .setCandidate(value)
                .setCategory(category)
                .setSource(SourceTag.MEMBER)
                .setScore(score)
                .setMatchMethod(if (category == STORE_NAME_CAT) "TYPOS(1)" else "TOKENS")
                .setProvenance(
                    Provenance
                        .newBuilder()
                        .setProducer("fuzzy")
                        .setMethod("TATRMAN_V2")
                        .setRawScore(score)
                        .setCoverage(1.0)
                        .addTokenHits(
                            TokenHit
                                .newBuilder()
                                .setQueryToken(token)
                                .setCandidateToken(token)
                                .setKind(kind),
                        ),
                ).build()
        }

        // the rows hartland's matcher answers (CoordinatedPlacesTest's matcher.json)
        val BRNO =
            Place("Brno", member("Brno", STORE_NAME_CAT), member("Brno DC", WAREHOUSE_NAME_CAT, 1.006772748873239))
        val LIBEREC = Place("Liberec", member("Liberec", STORE_NAME_CAT))
        val PLZEN =
            Place("Plzeň", member("Plzeň", STORE_NAME_CAT), member("Plzeň DC", WAREHOUSE_NAME_CAT, 1.006772748873239))

        /**
         * The governed pair of each place, as span proposal makes it under a measure (scope: its fact)
         * and SlotHints stamps it: one coordination, grouped at the first conjunct's head.
         */
        fun coordination(
            vararg places: Place,
            governor: String = "",
        ): Coord {
            val pairs =
                places.withIndex().flatMap { (i, p) ->
                    val start = i * 20
                    listOf(Origin.GOVERNED_VALUE, Origin.OPEN_VALUE).map { origin ->
                        val governed = origin == Origin.GOVERNED_VALUE
                        DomainSpanCandidate(
                            text = p.text,
                            start = start,
                            end = start + p.text.length,
                            gatedEntityRefs = if (governed) listOf(CHANNEL_SALES) else emptyList(),
                            categories = emptyList(),
                            anchored = governed,
                            origin = origin,
                            headToken = 2 + 2 * i,
                            slot = SlotHint(governorRef = governor, coordGroup = 2),
                            dualReadingOf = start to start + p.text.length,
                            dualReadingScope = listOf(CHANNEL_SALES),
                        ) to p.rows
                    }
                }
            return Coord(pairs.map { it.first }, pairs.map { it.second })
        }

        /** The rule over [coord]'s candidates, each answered with its place's rows (both halves alike). */
        fun decide(coord: Coord): Map<Int, String> =
            CoordinatedValues.intersect(
                coord.candidates,
                { coord.rows[it] },
                ResolverThresholds.LIVE,
                OWNERS,
                KINDS,
                REACH,
                MEMBER_OWNERS,
            )

        fun resource(name: String): String =
            checkNotNull(CoordinatedValuesTest::class.java.getResource("/coordinated-places/$name")) {
                "missing fixture $name"
            }.readText()

        fun parse(name: String): AnalyzeResponse =
            AnalyzeResponse
                .newBuilder()
                .also { JsonFormat.parser().merge(resource("$name.parse.json"), it) }
                .build()

        fun stamp(name: String): List<DomainSpanCandidate> {
            val p = parse(name)
            return SlotHints.stamp(
                p,
                SpanProposal.proposeDomainSpans(p, TYPES),
                KINDS,
                OWNERS,
                p.language,
                FrameRolePreps.shipped(),
            )
        }
    }
}
