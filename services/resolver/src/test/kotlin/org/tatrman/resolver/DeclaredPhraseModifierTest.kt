// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.google.protobuf.util.JsonFormat
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.LookupResponse
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.grounding.v1.DateTimeInterval
import org.tatrman.grounding.v1.FilterRecipe
import org.tatrman.grounding.v1.GetStatusResponse
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingResult
import org.tatrman.grounding.v1.Normalized
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Capability
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.GroundingClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.FrameRolePreps
import org.tatrman.resolver.pipeline.FrameRoles
import org.tatrman.resolver.pipeline.Literals
import org.tatrman.resolver.pipeline.MentionLayer
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.pipeline.SpanProposal
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.ResolveContext
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.fuzzy.v1.TargetClass as FuzzyTargetClass
import org.tatrman.resolver.v1.TargetClass as LatticeTargetClass

/**
 * ttr-server#237 — **an adjective in front of a declared phrase is never lost.**
 *
 * A declared multi-word phrase names its own extent (`tržby z tržiště`, `marketplace revenue`), so the
 * adjective in front of it was in no span: not the phrase's, and not a leftover's, since the mention
 * layer headed its leftovers at nouns. „Měsíční tržby z tržiště v roce 2025“ came back with the measure
 * and the year — no mention of „Měsíční“, no gap — and the caller answered one total for a question
 * asking for months. „Akční …“ and "Promotional …" lost their restriction the same way.
 *
 * Now such an adjective is a mention of its own. The lookup round asks it like any leftover, so a
 * Czech grain adjective binds the calendar attribute the estate declared for its unit (`měsíčně`,
 * `po měsících`) and R6" reads it as the GROUPING, as it reads `Monthly`; anything nothing binds is a
 * G1. A word on the `silent-modifiers` list (`celkové`, `total`) still restricts nothing.
 *
 * **Provenance (2026-10-14).**
 *  - Parses (`declared-phrase-modifier/<question>.parse.json`): hartland's nlp, as returned (`traceId`
 *    and `elapsedMs` dropped). cs: MorphoDiTa `czech-morfflex2.0-pdtc1.0-220710` tokens, lemmas and
 *    tags, Stanza dependencies, NameTag 3 `nametag3-czech-cnec2.0-240830` entities. en: Stanza.
 *  - Registry (`registry.json`): the projection `LexiconArchiveRegistrySource` builds from the hartland
 *    lexicon archive the demo estate runs (`sha256:25a38870…`). It declares the calendar grains as
 *    adverbs and phrases (`měsíčně`, `po měsících`, `monthly`, …) and no Czech grain adjective.
 *  - Matcher rows (`matcher.json`): every row lex-matcher 0.11.3 over that archive answered to these
 *    questions, keyed by the folded query, recorded with this change and without it.
 *  - The grounding kernel: chrono on a four-digit year, anchored on `er.entity.date_dim.cal_date`.
 */
class DeclaredPhraseModifierTest :
    StringSpec({

        // ── the issue: a grain adjective before a declared measure phrase ─────────────────────────

        "„Měsíční tržby z tržiště v roce 2025“ — „Měsíční“ is the month GROUPING, not dropped (the issue's row)" {
            resolve(MESICNI_TRZISTE_2025).lattice.summary() shouldContainExactly
                listOf(
                    "M Měsíční → $MONTH [GROUPING]",
                    "M tržby z tržiště → $MARKETPLACE_REVENUE [SUBJECT, MEASURE]",
                    "M roce → $CHRONO [FILTER]",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
        }

        "„Měsíční tržby z tržiště“ — the same without a year" {
            val turn = resolve(MESICNI_TRZISTE)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Měsíční → $MONTH [GROUPING]",
                    "M tržby z tržiště → $MARKETPLACE_REVENUE [SUBJECT, MEASURE]",
                )
            turn.answers()
        }

        "„Vývoj měsíčních tržeb z tržiště v roce 2025“ — an inflected form reads the same" {
            resolve(MESICNICH_VYVOJ).lattice.summary() shouldContainExactly
                listOf(
                    "M Vývoj → $TREND []",
                    "M měsíčních → $MONTH [GROUPING]",
                    "M tržeb z tržiště → $MARKETPLACE_REVENUE [SUBJECT, MEASURE]",
                    "M roce → $CHRONO [FILTER]",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
        }

        "every calendar adjective the estate has a grain for reads as that grain" {
            resolve(CTVRTLETNI).lattice.mention("Čtvrtletní") shouldBe "M Čtvrtletní → $QUARTER [GROUPING]"
            resolve(ROCNI).lattice.mention("Roční") shouldBe "M Roční → $YEAR [GROUPING]"
            resolve(TYDENNI).lattice.mention("Týdenní") shouldBe "M Týdenní → $WEEK [GROUPING]"
        }

        "„Čtvrtletní“ is no time-kernel trigger: a trigger row would bind it with nothing to ground" {
            // `čtvrtletní` reads like the chrono trigger `čtvrtletí`; as a trigger it would be "bound" and
            // never asked again, and the question would lose its breakdown once more
            val turn = resolve(CTVRTLETNI)
            turn.lattice.mentionsList
                .single { it.span.text == "Čtvrtletní" }
                .bindingsList
                .map { it.ref } shouldContainExactly listOf(QUARTER)
            turn.answers()
        }

        "a calendar adjective the estate has no grain for is a G1, not dropped" {
            for (question in listOf(DENNI, POLOLETNI)) {
                val word = question.text.substringBefore(' ')
                val turn = resolve(question)
                withClue(question.text) {
                    turn.lattice.mention(word) shouldBe "M $word →  []"
                    turn.lattice.gapsList.map { it.kind to it.span.text } shouldContainExactly
                        listOf(GapKind.GAP_KIND_G1_UNBOUND to word)
                }
            }
        }

        // ── any adjective, any declared phrase ────────────────────────────────────────────────────

        "„Akční tržby z tržiště v roce 2025“ — an adjective that restricts is reported, never silently dropped" {
            val turn = resolve(AKCNI)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Akční →  []",
                    "M tržby z tržiště → $MARKETPLACE_REVENUE [SUBJECT, MEASURE]",
                    "M roce → $CHRONO [FILTER]",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
            turn.lattice.gapsList.map { it.kind to it.span.text } shouldContainExactly
                listOf(GapKind.GAP_KIND_G1_UNBOUND to "Akční")
        }

        "\"Promotional marketplace revenue in 2025\" — the same in English" {
            val turn = resolve(PROMOTIONAL)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Promotional →  []",
                    "M marketplace revenue → $MARKETPLACE_REVENUE [SUBJECT, MEASURE]",
                    "V 2025 GROUNDED → $CAL_DATE",
                )
            turn.lattice.gapsList.map { it.kind to it.span.text } shouldContainExactly
                listOf(GapKind.GAP_KIND_G1_UNBOUND to "Promotional")
        }

        "„Tržby podle hlavních distribučních center v roce 2025“ — beside a declared entity phrase too" {
            val turn = resolve(HLAVNICH)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Tržby → $REVENUE [SUBJECT, MEASURE]",
                    "M hlavních →  []",
                    "M distribučních center → $WAREHOUSE [GROUPING]",
                    "M roce → $CHRONO [FILTER]",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
            turn.lattice.gapsList.map { it.kind to it.span.text } shouldContainExactly
                listOf(GapKind.GAP_KIND_G1_UNBOUND to "hlavních")
        }

        "„Průměrné tržby z tržiště v roce 2025“ — an operator word the estate has no operator for is reported" {
            // a lookup round asks a left-out adjective in the operator class too, so an estate that
            // declares an average binds it; this one does not, and the word is not silently summed away
            resolve(PRUMERNE).lattice.gapsList.map { it.kind to it.span.text } shouldContainExactly
                listOf(GapKind.GAP_KIND_G1_UNBOUND to "Průměrné")
        }

        "„Dnešní tržby z prodejen“ — a left-out adjective never binds a second object: the stock row is refused" {
            // the matcher reads `Dnešní` as part of `není skladem`, a term of the stock MEASURE; an
            // adjective names a property of what it modifies or an operator over it, so that row is a
            // hypothesis the round declined and the word stays a G1
            val turn = resolve(DNESNI)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Dnešní →  []",
                    "M tržby z prodejen → $STORE_SALES [SUBJECT]",
                )
            turn.lattice.gapsList.single().let { gap ->
                (gap.kind to gap.span.text) shouldBe (GapKind.GAP_KIND_G1_UNBOUND to "Dnešní")
                gap.hypothesesTriedList.map { it.ref } shouldContainExactly listOf("V:$STOCK")
            }
        }

        "„Celkové tržby z tržiště“ / \"Total marketplace revenue\" — a silent modifier stays silent" {
            for (question in listOf(CELKOVE, TOTAL)) {
                val turn = resolve(question)
                withClue(question.text) {
                    turn.lattice.mentionsList.map { it.span.text } shouldContainExactly
                        (if (question === CELKOVE) listOf("tržby z tržiště", "roce") else listOf("marketplace revenue"))
                    turn.answers()
                }
            }
        }

        // ── the controls: unchanged ─────────────────────────────────────────────────────────────

        "control: „Tržby z tržiště po měsících v roce 2025“ — the declared grain phrase, as before" {
            val turn = resolve(PO_MESICICH)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Tržby z tržiště → $MARKETPLACE_REVENUE [SUBJECT, MEASURE]",
                    "M po měsících → $MONTH [GROUPING]",
                    "M roce → $CHRONO [FILTER]",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
            turn.answers()
        }

        "control: \"Monthly marketplace revenue in 2025\" — the declared adjective, as before" {
            val turn = resolve(MONTHLY)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Monthly → $MONTH [GROUPING]",
                    "M marketplace revenue → $MARKETPLACE_REVENUE [SUBJECT, MEASURE]",
                    "V 2025 GROUNDED → $CAL_DATE",
                )
            turn.answers()
        }

        "control: „Měsíční tržby“ — with no declared phrase the adjective is in its noun's mention, as before" {
            val turn = resolve(MESICNI_TRZBY)

            turn.lattice.mentionsList.map { it.span.text } shouldContainExactly listOf("Měsíční tržby")
            turn.lattice.gapsList.map { it.kind to it.span.text } shouldContainExactly
                listOf(GapKind.GAP_KIND_G1_UNBOUND to "Měsíční tržby")
        }

        // ── the rule's edges, on built parses ───────────────────────────────────────────────────

        "a declared multi-word phrase with an adjective in front never loses the adjective" {
            // the shape, engine-free: an `amod` on the head of a declared phrase, whose span is the
            // estate's words and nothing else
            val parse =
                MhMembers.parse(
                    "promotional marketplace revenue",
                    arrayOf(
                        MhMembers.tok("promotional", 0, 11, "promotional", "ADJ", 3, "amod"),
                        MhMembers.tok("marketplace", 12, 23, "marketplace", "NOUN", 3, "compound"),
                        MhMembers.tok("revenue", 24, 31, "revenue", "NOUN", 0, "root"),
                    ),
                    "en",
                )
            val gated = SpanProposal.proposeDomainSpans(parse, listOf(MARKETPLACE_REVENUE_TYPE))
            gated.map { it.text } shouldContainExactly listOf("marketplace revenue")

            leftOut(parse, gated) shouldContainExactly listOf("promotional")
        }

        "only a word a gated span left out: an adjective inside a single-word anchor's phrase stays in it" {
            val parse =
                MhMembers.parse(
                    "promotional revenue",
                    arrayOf(
                        MhMembers.tok("promotional", 0, 11, "promotional", "ADJ", 2, "amod"),
                        MhMembers.tok("revenue", 12, 19, "revenue", "NOUN", 0, "root"),
                    ),
                    "en",
                )
            val gated = SpanProposal.proposeDomainSpans(parse, listOf(REVENUE_TYPE))
            gated.map { it.text } shouldContainExactly listOf("promotional revenue")

            leftOut(parse, gated).shouldBeEmpty()
        }

        "only beside a gated span: an adjective of a leftover noun is in that noun's mention already" {
            val parse =
                MhMembers.parse(
                    "promotional widgets",
                    arrayOf(
                        MhMembers.tok("promotional", 0, 11, "promotional", "ADJ", 2, "amod"),
                        MhMembers.tok("widgets", 12, 19, "widget", "NOUN", 0, "root"),
                    ),
                    "en",
                )
            val gated = SpanProposal.proposeDomainSpans(parse, listOf(MARKETPLACE_REVENUE_TYPE))
            val mentions = MentionLayer.propose(parse, gated)
            mentions.map { it.text } shouldContainExactly listOf("promotional widgets")

            leftOut(parse, gated, mentions).shouldBeEmpty()
        }

        "silent in the question's language only: `celkové` is silent in Czech, `total` in English" {
            fun parse(
                word: String,
                lang: String,
            ) = MhMembers.parse(
                "$word marketplace revenue",
                arrayOf(
                    MhMembers.tok(word, 0, word.length, word.lowercase(), "ADJ", 3, "amod"),
                    MhMembers.tok(
                        "marketplace",
                        word.length + 1,
                        word.length + 12,
                        "marketplace",
                        "NOUN",
                        3,
                        "compound",
                    ),
                    MhMembers.tok("revenue", word.length + 13, word.length + 20, "revenue", "NOUN", 0, "root"),
                ),
                lang,
            )

            for ((word, lang) in listOf("Total" to "en", "Celkové" to "cs", "Overall" to "en")) {
                val p = parse(word, lang)
                leftOut(p, SpanProposal.proposeDomainSpans(p, listOf(MARKETPLACE_REVENUE_TYPE))).shouldBeEmpty()
            }
            // `celkové` restricts nothing in Czech; in an English question it is a word nothing knows
            val p = parse("Celkové", "en")
            leftOut(p, SpanProposal.proposeDomainSpans(p, listOf(MARKETPLACE_REVENUE_TYPE))) shouldContainExactly
                listOf("Celkové")
        }

        "a left-out adjective is never the SUBJECT, even when nothing else could be" {
            // R9 picks the shallowest mention left without a FILTER or a GROUPING; an adjective
            // modifies what the question is about, so it is not a candidate
            val parse =
                MhMembers.parse(
                    "promotional widgets",
                    arrayOf(
                        MhMembers.tok("promotional", 0, 11, "promotional", "ADJ", 2, "amod"),
                        MhMembers.tok("widgets", 12, 19, "widget", "NOUN", 0, "root"),
                    ),
                    "en",
                )

            fun roles(subject: Boolean) =
                FrameRoles
                    .derive(
                        listOf(
                            FrameRoles.Input(
                                "adj",
                                0,
                                0,
                                LatticeTargetClass.TARGET_CLASS_UNSPECIFIED,
                                "",
                                anchorsValue = false,
                                charEnd = 11,
                                subject = subject,
                            ),
                        ),
                        parse,
                        "en",
                        FrameRolePreps.shipped(),
                    ).getValue("adj")

            roles(subject = true) shouldContainExactly listOf(FrameRole.FRAME_ROLE_SUBJECT)
            roles(subject = false).shouldBeEmpty()
        }

        "a date's adjective is the date's: a universal-typed word is never a mention of its own" {
            val parse =
                MhMembers
                    .parse(
                        "Last marketplace revenue",
                        arrayOf(
                            MhMembers.tok("Last", 0, 4, "last", "ADJ", 3, "amod"),
                            MhMembers.tok("marketplace", 5, 16, "marketplace", "NOUN", 3, "compound"),
                            MhMembers.tok("revenue", 17, 24, "revenue", "NOUN", 0, "root"),
                        ),
                        "en",
                    ).toBuilder()
                    .addEntities(MhMembers.ner("Last", 0, 4, "DATE"))
                    .build()

            leftOut(parse, SpanProposal.proposeDomainSpans(parse, listOf(MARKETPLACE_REVENUE_TYPE))).shouldBeEmpty()
        }
    }) {
    private class Question(
        val text: String,
        val lang: String,
        resource: String,
    ) {
        val parse: AnalyzeResponse =
            AnalyzeResponse
                .newBuilder()
                .also { JsonFormat.parser().merge(resourceText("$resource.parse.json"), it) }
                .build()
                .also { p ->
                    p.tokensList.forEach { t ->
                        check(text.substring(t.charStart, t.charEnd) == t.text) { "offsets of '${t.text}' in '$text'" }
                    }
                }
    }

    private class Turn(
        val response: ResolveResponse,
    ) {
        val lattice: ResolutionState get() = response.resolutionState

        /** Nothing is asked and nothing is left open. */
        fun answers() {
            withClue("gaps ${lattice.gapsList.map { it.kind to it.span.text }}") {
                response.hasAwaiting() shouldBe false
                lattice.gapsList shouldBe emptyList()
            }
        }
    }

    /**
     * lex-matcher as recorded (see the class doc). A scoped question answers the rows whose category it
     * asked; an unscoped one answers every row, and a declared row found that way carries `unknown`, the
     * category lex-matcher stamps on a row it has no category for. A class filter keeps the rows of those
     * classes. Rows come best first, cut at the limit asked.
     */
    private class WireMatcher : FuzzyClient {
        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            val builder = BatchMatchResponse.newBuilder()
            for (span in request.spansList) {
                val rows = answer(span.query, span.categoriesList, emptyList()).take(limitOf(span.limit, 20))
                builder.addResults(FuzzyMatchResponse.newBuilder().addAllMatches(rows))
            }
            return builder.build()
        }

        override suspend fun lookup(request: LookupRequest): LookupResponse {
            val rows =
                answer(request.term, request.categoriesList, request.targetClassesList)
                    .take(limitOf(request.maxCandidates, 10))
            return LookupResponse.newBuilder().addAllCandidates(rows).build()
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()

        private fun limitOf(
            asked: Int,
            default: Int,
        ) = if (asked > 0) asked else default

        private fun answer(
            query: String,
            categories: List<String>,
            classes: List<FuzzyTargetClass>,
        ): List<FuzzyMatch> =
            ROWS[fold(query)]
                .orEmpty()
                .filter { categories.isEmpty() || it.category in categories }
                .filter { classes.isEmpty() || it.targetClass in classes }
                .sortedByDescending { it.score }
                .map {
                    if (categories.isEmpty() && it.source != SourceTag.MEMBER) {
                        it.toBuilder().setCategory(NO_CATEGORY).build()
                    } else {
                        it
                    }
                }
    }

    /** chrono on a four-digit year: the calendar year, on the estate's event-date column, as live. */
    private class YearKernel : GroundingClient {
        override suspend fun ground(request: GroundRequest): GroundResponse {
            val year =
                request.spanText
                    .trim()
                    .toIntOrNull()
                    ?.takeIf { it in 1900..2999 }
                    ?: return GroundResponse.newBuilder().setStatus(GroundResponse.Status.UNGROUNDABLE).build()
            return GroundResponse
                .newBuilder()
                .setStatus(GroundResponse.Status.OK)
                .setResult(
                    GroundingResult
                        .newBuilder()
                        .setNormalized(
                            Normalized.newBuilder().setInterval(
                                DateTimeInterval
                                    .newBuilder()
                                    .setStart("$year-01-01T00:00:00Z")
                                    .setEnd("${year + 1}-01-01T00:00:00Z"),
                            ),
                        ).setFilter(
                            FilterRecipe.newBuilder().setAnchorColumn(
                                QualifiedName
                                    .newBuilder()
                                    .setPackage("hartland")
                                    .setSchemaCode(SchemaCode.ER)
                                    .setNamespace("entity")
                                    .setName("date_dim.cal_date"),
                            ),
                        ),
                ).build()
        }

        override suspend fun getStatus(): GetStatusResponse = GetStatusResponse.getDefaultInstance()
    }

    private class FakeNlp(
        private val parse: AnalyzeResponse,
    ) : NlpClient {
        override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse = parse

        override suspend fun getStatus(): StatusResponse =
            StatusResponse
                .newBuilder()
                .setReady(true)
                .addCapabilities(Capability.newBuilder().setLanguage(parse.language).setOp(NlpOp.NER))
                .addCapabilities(Capability.newBuilder().setLanguage(parse.language).setOp(NlpOp.DEP_PARSE))
                .build()
    }

    private companion object {
        const val MARKETPLACE_REVENUE = "er.entity.catalog_sales.ext_sales_price"
        const val MONTH = "er.entity.date_dim.month"
        const val QUARTER = "er.entity.date_dim.quarter"
        const val YEAR = "er.entity.date_dim.year"
        const val WEEK = "er.entity.date_dim.week_seq"
        const val CAL_DATE = "er.entity.date_dim.cal_date"
        const val CHRONO = "ground:chrono"
        const val TREND = "op:trend"
        const val REVENUE = "er.entity.channel_sales.ext_sales_price"
        const val WAREHOUSE = "er.entity.warehouse"
        const val STORE_SALES = "er.entity.store_sales"
        const val STOCK = "er.entity.inventory.quantity_on_hand"

        /** What lex-matcher writes as the category of a declared row an unscoped question found. */
        const val NO_CATEGORY = "unknown"

        val MARKETPLACE_REVENUE_TYPE =
            ResolverEntityType(
                MARKETPLACE_REVENUE,
                listOf(MARKETPLACE_REVENUE),
                listOf("marketplace revenue", "tržby z tržiště"),
                objectKind = "measure",
                ownerRef = "er.entity.catalog_sales",
            )
        val REVENUE_TYPE =
            ResolverEntityType(
                "er.entity.channel_sales.ext_sales_price",
                listOf("er.entity.channel_sales.ext_sales_price"),
                listOf("revenue", "tržby"),
                objectKind = "measure",
                ownerRef = "er.entity.channel_sales",
            )

        val MESICNI_TRZISTE_2025 = Question("Měsíční tržby z tržiště v roce 2025", "cs", "mesicni-trziste-2025")
        val MESICNI_TRZISTE = Question("Měsíční tržby z tržiště", "cs", "mesicni-trziste")
        val MESICNICH_VYVOJ = Question("Vývoj měsíčních tržeb z tržiště v roce 2025", "cs", "mesicnich-vyvoj")
        val CTVRTLETNI = Question("Čtvrtletní tržby z tržiště v roce 2025", "cs", "ctvrtletni")
        val ROCNI = Question("Roční tržby z tržiště", "cs", "rocni")
        val TYDENNI = Question("Týdenní tržby z tržiště v roce 2025", "cs", "tydenni")
        val DENNI = Question("Denní tržby z tržiště v roce 2025", "cs", "denni")
        val POLOLETNI = Question("Pololetní tržby z tržiště v roce 2025", "cs", "pololetni")
        val AKCNI = Question("Akční tržby z tržiště v roce 2025", "cs", "akcni")
        val CELKOVE = Question("Celkové tržby z tržiště v roce 2025", "cs", "celkove")
        val PO_MESICICH = Question("Tržby z tržiště po měsících v roce 2025", "cs", "po-mesicich")
        val MESICNI_TRZBY = Question("Měsíční tržby", "cs", "mesicni-trzby")
        val MONTHLY = Question("Monthly marketplace revenue in 2025", "en", "monthly")
        val PROMOTIONAL = Question("Promotional marketplace revenue in 2025", "en", "promotional")
        val TOTAL = Question("Total marketplace revenue in 2025", "en", "total")
        val HLAVNICH = Question("Tržby podle hlavních distribučních center v roce 2025", "cs", "hlavnich")
        val PRUMERNE = Question("Průměrné tržby z tržiště v roce 2025", "cs", "prumerne")
        val DNESNI = Question("Dnešní tržby z prodejen", "cs", "dnesni")

        val REGISTRY: Registry =
            Registry.newBuilder().also { JsonFormat.parser().merge(resourceText("registry.json"), it) }.build()

        /** The recorded matcher rows, by folded query. */
        val ROWS: Map<String, List<FuzzyMatch>> =
            Json.parseToJsonElement(resourceText("matcher.json")).jsonObject.mapValues { (_, rows) ->
                rows.jsonArray.map { row ->
                    FuzzyMatch.newBuilder().also { JsonFormat.parser().merge(row.toString(), it) }.build()
                }
            }

        fun resourceText(name: String): String =
            checkNotNull(DeclaredPhraseModifierTest::class.java.getResource("/declared-phrase-modifier/$name")) {
                "missing fixture $name"
            }.readText()

        fun fold(v: String): String =
            java.text.Normalizer
                .normalize(v.lowercase().trim(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")

        /** The texts of the left-out adjectives of [parse], beside [gated] and the noun leftovers. */
        fun leftOut(
            parse: AnalyzeResponse,
            gated: List<org.tatrman.resolver.pipeline.DomainSpanCandidate>,
            mentions: List<org.tatrman.resolver.pipeline.DomainSpanCandidate> = MentionLayer.propose(parse, gated),
        ): List<String> =
            MentionLayer
                .leftOutModifiers(parse, gated, mentions, Literals.NONE, FrameRolePreps.shipped(), parse.language)
                .map { it.text }

        /** One line per mention (`M text → refs [roles]`) and per value (`V text KIND → attributions ⟵ narrowing`). */
        fun ResolutionState.summary(): List<String> =
            mentionsList.map { m ->
                "M ${m.span.text} → ${m.bindingsList.joinToString { it.ref }} " +
                    m.frameRolesList.map { it.name.removePrefix("FRAME_ROLE_") }
            } +
                valuesList.map { v ->
                    "V ${v.span.text} ${v.kind.name.removePrefix("VALUE_KIND_")} → " +
                        v.attributionsList.joinToString { if (it.hasBinding()) it.binding.ref else it.attributeRef } +
                        v.grounding.ref
                            .ifBlank { null }
                            ?.let { " ⟵ $it" }
                            .orEmpty()
                }

        /** The summary line of the one mention over [text]. */
        fun ResolutionState.mention(text: String): String = summary().single { it.startsWith("M $text →") }

        fun resolve(question: Question): Turn {
            val pipeline =
                ResolverPipeline(
                    FakeNlp(question.parse),
                    WireMatcher(),
                    SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), ResolverThresholds.LIVE),
                    emptyMap(),
                    ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                    grounding = YearKernel(),
                    defaultPackage = "hartland",
                )
            val response =
                runBlocking {
                    pipeline.resolve(
                        ResolveRequest
                            .newBuilder()
                            .setConversationId("declared-phrase-modifier")
                            .setFresh(FreshQuestion.newBuilder().setText(question.text).setLocale(question.lang))
                            .setContext(ResolveContext.newBuilder().setReferenceDatetime("2026-10-14T10:00:00Z"))
                            .setRegistry(REGISTRY)
                            .build(),
                    )
                }
            return Turn(response)
        }
    }
}
