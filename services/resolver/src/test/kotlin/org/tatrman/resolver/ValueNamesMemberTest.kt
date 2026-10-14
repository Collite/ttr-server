// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.google.protobuf.util.JsonFormat
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
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
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.LatticeAssembler
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.Mention
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.ResolveContext
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.TargetClass
import org.tatrman.resolver.v1.ValueKind
import org.tatrman.fuzzy.v1.TargetClass as FuzzyTargetClass

/**
 * ttr-server#227 — a lattice VALUE's attribution names a member, never a model object.
 *
 * The proto's contract: a value is a literal the question restricts BY, and its attribution binds a member
 * (`<attribute_ref>#<id>`). A word whose best reading is a model object is a MENTION of that object. Live,
 * the same word went both ways depending on the noun it hung from:
 *
 *  - „Tržby z tržiště po čtvrtletích v roce 2025“ — `roce` hangs from `čtvrtletích`, a declared multi-word
 *    phrase that governs no value. Nothing proposes it, so the mention layer takes it: a mention the
 *    lexicon's `ground:chrono` trigger binds, FILTER, and the year beside it is chrono's.
 *  - „Porovnej tržby z tržiště a z webu v roce 2025“ and „10 nejprodávanějších produktů na tržišti v roce
 *    2025“ — `roce` hangs from `webu` / `produktů`, one-word anchors of entities, which DO govern values. So
 *    span proposal offers it as their governed value, its open sibling asks every category, and the only
 *    reading anywhere is the model object `er.entity.date_dim.year` (`za rok`, matched by the lemma
 *    `rok`). The lattice put it on a VALUE as that object's attribution, the trigger was never asked, and
 *    the year beside it lost its narrowing.
 *  - "marketplace revenu in 2025 by month" — `revenu` is tagged PROPN, so the proper-noun path offers it as
 *    a value, and its best reading is the all-channel revenue MEASURE (a typo of `revenue`).
 *
 * Through the whole pipeline, over the parses hartland's nlp returned for these questions and the rows its
 * lex-matcher answered, quoted from the live wire.
 *
 * **Provenance (2026-10-13, hartland, resolver 0.11.21, cs world).**
 *  - Parses (`value-names-member/<question>.parse.json`): the `parse` of each live `ResolverService/Resolve`
 *    answer, as returned (`traceId` and `elapsedMs` dropped). cs: MorphoDiTa
 *    `czech-morfflex2.0-pdtc1.0-220710` tokens, lemmas and tags, Stanza 1.10.0 dependencies, NameTag 3
 *    `nametag3-czech-cnec2.0-240830` entities. en: Stanza 1.10.0 throughout.
 *  - Matcher rows (`value-names-member/matcher.json`): every row lex-matcher answered to these nine resolves,
 *    from the resolver's DEBUG log of `FuzzyService/BatchMatch` and `FuzzyService/Lookup`, verbatim, keyed by
 *    the folded query. The fake below re-serves them by the categories asked, and answers every one of the
 *    70 live slots with exactly the rows the live matcher did. The one slot the live resolver never asked
 *    is the one this fix adds — a value-shaped span's `ground:` trigger slot; `roce`'s answer is quoted from
 *    the questions that did ask it, and for `revenu`, `letech` and `Brno DC` the compiled lexicon holds no
 *    `ground:` term within a typo of, or sharing a token with, them.
 *  - Registry (`value-names-member/registry.json`): the projection of hartland's compiled lexicon
 *    (`ttr-lexicon-compiled/v5`, the archive the live matcher reported) as `LexiconArchiveRegistrySource`
 *    builds it — every model-object target with its terms as anchors, every member vocabulary, kinds,
 *    owners, reach.
 *  - The grounding kernel: chrono on a four-digit year, anchored on `er.entity.date_dim.cal_date`, as live.
 */
class ValueNamesMemberTest :
    StringSpec({

        // ── the bug ───────────────────────────────────────────────────────────────────────────

        "#227 „Porovnej tržby z tržiště a z webu v roce 2025“ — `roce` is the kernel's mention, as where it works" {
            val matcher = WireMatcher()
            val turn = resolve(POROVNEJ, matcher)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Porovnej → op:compare []",
                    "M tržby z tržiště → $CATALOG_REVENUE [SUBJECT, MEASURE]",
                    "M webu → $WEB_REVENUE [MEASURE]",
                    "M roce → $CHRONO [FILTER]",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
            turn.valuesNameOnlyMembers()
            turn.narrowed("roce", "2025")
            // the trigger slot was asked for the governed word, in the one batch
            matcher.batches
                .single()
                .spansList
                .filter { it.query == "roce" }
                .map { it.categoriesList } shouldContain
                listOf("ground:chrono", "ground:geo", "ground:money")
            // the door tells the lattice's story: no year attribute on `roce`, exactly as in the working question
            turn.doorSpans() shouldContainExactly listOf("Porovnej", "tržby z tržiště", "webu")
            turn.doorAgreesWithLattice()
        }

        "#227 „10 nejprodávanějších produktů na tržišti v roce 2025“ — `roce` is the kernel's mention there too" {
            val turn = resolve(TOP_10)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M nejprodávanějších → er.entity.channel_sales.quantity []",
                    "M produktů → er.entity.item.category [SUBJECT]",
                    "M tržišti → $CATALOG_REVENUE [MEASURE]",
                    "M roce → $CHRONO [FILTER]",
                    "V 10 GROUNDED → ",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
            turn.valuesNameOnlyMembers()
            turn.narrowed("roce", "2025")
        }

        "#227 the same word, the same mention: `roce` reads alike whichever noun it hangs from" {
            val working = resolve(PO_CTVRTLETICH).lattice.mention("roce")
            for (question in listOf(POROVNEJ, TOP_10, BRNO_DC)) {
                withClue(question.text) {
                    resolve(question).lattice.mention("roce").essence() shouldBe working.essence()
                }
            }
        }

        "#227 no `za rok` in the estate: the trigger alone makes `roce` the kernel's mention" {
            // The first cut gave the word to the kernel only when the value lookup happened to read it as a
            // model object. Without that row it stayed an unattributed value, and `2025` lost its narrowing.
            val noZaRok = WireMatcher(absent = { it.candidateId == "lex:$YEAR:cs:za rok" })
            for (question in listOf(POROVNEJ, TOP_10)) {
                withClue(question.text) {
                    val turn = resolve(question, noZaRok)

                    turn.lattice.summary() shouldContain "M roce → $CHRONO [FILTER]"
                    turn.narrowed("roce", "2025")
                    turn.lattice.mention("roce").essence() shouldBe
                        resolve(PO_CTVRTLETICH).lattice.mention("roce").essence()
                    turn.doorSpans().contains("roce") shouldBe false
                    turn.valuesNameOnlyMembers()
                }
            }
        }

        "#227 \"marketplace revenu in 2025 by month\" — a word whose reading is a measure is a mention of it" {
            val turn = resolve(REVENU)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M marketplace → $CATALOG_SALES []",
                    "M revenu → $CHANNEL_REVENUE [SUBJECT, MEASURE]",
                    "M by month → er.entity.date_dim.month [GROUPING]",
                    "V 2025 GROUNDED → $CAL_DATE",
                )
            turn.valuesNameOnlyMembers()
            val revenu =
                turn.lattice
                    .mention("revenu")
                    .bindingsList
                    .single()
            revenu.targetClass shouldBe TargetClass.TARGET_CLASS_MODEL_OBJECT
            // the evidence it had as a value travels with it unchanged: a typo, unanchored
            revenu.evidenceClass shouldBe EvidenceClass.EVIDENCE_CLASS_UNANCHORED_FUZZY_STRONG
            turn.doorAgreesWithLattice()
        }

        "#227 „… v letech 2024 a 2025“ — a model object and no trigger: a mention bound to the object, never dropped" {
            val turn = resolve(LETECH)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Porovnej → op:compare []",
                    "M tržby z webu → $WEB_REVENUE [SUBJECT, MEASURE]",
                    "M tržiště → $CATALOG_SALES []",
                    "M letech → $YEAR [FILTER]",
                    "V 2024 GROUNDED → $CAL_DATE",
                    "V 2025 GROUNDED → $CAL_DATE",
                )
            turn.valuesNameOnlyMembers()
            turn.doorAgreesWithLattice()
        }

        // ── the guards ────────────────────────────────────────────────────────────────────────

        "the working question „Tržby z tržiště po čtvrtletích v roce 2025“ is unchanged" {
            resolve(PO_CTVRTLETICH).lattice.summary() shouldContainExactly
                listOf(
                    "M Tržby z tržiště → $CATALOG_REVENUE [SUBJECT, MEASURE]",
                    "M po čtvrtletích → er.entity.date_dim.quarter [GROUPING]",
                    "M roce → $CHRONO [FILTER]",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
        }

        "a member still binds as a value: „… pro Brno DC“ is `warehouse_name#Brno DC`" {
            val turn = resolve(BRNO_DC)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Tržby z tržiště → $CATALOG_REVENUE [SUBJECT, MEASURE]",
                    "M po měsících → er.entity.date_dim.month [GROUPING]",
                    "M roce → $CHRONO [FILTER]",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                    "V Brno DC LITERAL → er.entity.warehouse.warehouse_name#Brno DC",
                )
            val brno = turn.lattice.valuesList.single { it.span.text == "Brno DC" }
            brno.kind shouldBe ValueKind.VALUE_KIND_LITERAL
            brno.attributionsList.single().attributeRef shouldBe "er.entity.warehouse.warehouse_name"
            brno.attributionsList
                .single()
                .binding.targetClass shouldBe TargetClass.TARGET_CLASS_MEMBER
        }

        "\"Marketplace sales by quarter in 2025\" is unchanged" {
            resolve(SALES_BY_QUARTER).lattice.summary() shouldContainExactly
                listOf(
                    "M Marketplace sales → $CATALOG_REVENUE [SUBJECT, MEASURE]",
                    "M by quarter → er.entity.date_dim.quarter [GROUPING]",
                    "V 2025 GROUNDED → $CAL_DATE",
                )
        }

        "„Tržby z prodejen za 2025“ is unchanged" {
            resolve(Z_PRODEJEN).lattice.summary() shouldContainExactly
                listOf(
                    "M Tržby z prodejen → er.entity.store_sales [SUBJECT]",
                    "V 2025 GROUNDED → $CAL_DATE",
                )
        }

        "\"What share of total revenue came from the marketplace in 2025?\" is unchanged" {
            resolve(WHAT_SHARE).lattice.summary() shouldContainExactly
                listOf(
                    "M What share → op:share-of []",
                    "M total revenue → $CHANNEL_REVENUE [MEASURE]",
                    "M the marketplace → $CATALOG_SALES [SUBJECT]",
                    "V 2025 GROUNDED → $CAL_DATE",
                )
        }

        "a grounded value keeps its kernel and its anchor column" {
            for (question in listOf(POROVNEJ, TOP_10, PO_CTVRTLETICH, REVENU, BRNO_DC, SALES_BY_QUARTER, Z_PRODEJEN)) {
                withClue(question.text) {
                    val year = resolve(question).lattice.valuesList.single { it.span.text == "2025" }
                    year.kind shouldBe ValueKind.VALUE_KIND_GROUNDED
                    year.grounding.kernel shouldBe "chrono"
                    year.grounding.normalizedValue shouldBe "2025-01-01T00:00:00Z/2026-01-01T00:00:00Z"
                    year.attributionsList.map { it.attributeRef } shouldContainExactly listOf(CAL_DATE)
                }
            }
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

        /** The contract itself: every value attribution that carries a binding names a MEMBER. */
        fun valuesNameOnlyMembers() {
            for (value in lattice.valuesList) {
                for (a in value.attributionsList.filter { it.hasBinding() }) {
                    withClue("value '${value.span.text}' → ${a.binding.ref}") {
                        a.binding.targetClass shouldBe TargetClass.TARGET_CLASS_MEMBER
                    }
                }
            }
        }

        /** The grounded value [value] is narrowed to the kernel by the trigger on the mention [mention]. */
        fun narrowed(
            mention: String,
            value: String,
        ) {
            val m = lattice.mention(mention)
            val v = lattice.valuesList.single { it.span.text == value }
            v.grounding.ref shouldBe CHRONO
            lattice.rungLogList
                .filter { it.action == LatticeAssembler.GROUND_NARROW_ACTION }
                .map { it.mentionIdsList to it.valueIdsList } shouldContainExactly listOf(listOf(m.id) to listOf(v.id))
        }

        /** The spans the door (`Resolution.bindings`) bound a domain object on. */
        fun doorSpans(): List<String> =
            response.resolution.bindingsList
                .filter { it.hasDomain() }
                .map { it.span.text }

        /** Every door binding over a span the lattice bound as a mention names what the lattice bound there. */
        fun doorAgreesWithLattice() {
            val mentions =
                lattice.mentionsList
                    .filter { it.bindingsCount > 0 }
                    .associate { (it.span.start to it.span.end) to it.bindingsList.first().ref }
            for (b in response.resolution.bindingsList.filter { it.hasDomain() }) {
                val bound = mentions[b.span.start to b.span.end] ?: continue
                withClue("door binding on '${b.span.text}'") {
                    b.domain.candidatesList
                        .map { it.targetRef }
                        .ifEmpty { listOf(b.domain.entityTypeRef) } shouldContain bound
                }
            }
        }
    }

    /**
     * lex-matcher as hartland ran it, serving the rows it answered (see the class doc). A scoped question
     * answers the rows whose category it asked; an unscoped one answers every row, and a declared row found
     * that way carries `unknown`, the category lex-matcher stamps on a row it has no category for. A class
     * filter keeps the rows of those classes — a member row carries none, so it is never among them.
     */
    private class WireMatcher(
        /** A row the estate does not have: never answered, to any question. */
        private val absent: (FuzzyMatch) -> Boolean = { false },
    ) : FuzzyClient {
        val batches = mutableListOf<BatchMatchRequest>()
        val lookups = mutableListOf<LookupRequest>()

        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            batches += request
            val builder = BatchMatchResponse.newBuilder()
            for (span in request.spansList) {
                val rows = answer(span.query, span.categoriesList, emptyList()).take(limitOf(span.limit, 20))
                builder.addResults(FuzzyMatchResponse.newBuilder().addAllMatches(rows))
            }
            return builder.build()
        }

        override suspend fun lookup(request: LookupRequest): LookupResponse {
            lookups += request
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
                .filterNot(absent)
                .filter { categories.isEmpty() || it.category in categories }
                .filter { classes.isEmpty() || it.targetClass in classes }
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
        const val CATALOG_SALES = "er.entity.catalog_sales"
        const val CATALOG_REVENUE = "er.entity.catalog_sales.ext_sales_price"
        const val WEB_REVENUE = "er.entity.web_sales.ext_sales_price"
        const val CHANNEL_REVENUE = "er.entity.channel_sales.ext_sales_price"
        const val YEAR = "er.entity.date_dim.year"
        const val CAL_DATE = "er.entity.date_dim.cal_date"
        const val CHRONO = "ground:chrono"

        /** What lex-matcher writes as the category of a declared row an unscoped question found. */
        const val NO_CATEGORY = "unknown"

        val POROVNEJ = Question("Porovnej tržby z tržiště a z webu v roce 2025", "cs", "porovnej")
        val TOP_10 = Question("10 nejprodávanějších produktů na tržišti v roce 2025", "cs", "nejprodavanejsich")
        val PO_CTVRTLETICH = Question("Tržby z tržiště po čtvrtletích v roce 2025", "cs", "po-ctvrtletich")
        val REVENU = Question("marketplace revenu in 2025 by month", "en", "marketplace-revenu")
        val LETECH = Question("Porovnej tržby z webu a z tržiště v letech 2024 a 2025", "cs", "v-letech")
        val BRNO_DC = Question("Tržby z tržiště po měsících v roce 2025 pro Brno DC", "cs", "pro-brno-dc")
        val SALES_BY_QUARTER = Question("Marketplace sales by quarter in 2025", "en", "marketplace-sales-by-quarter")
        val Z_PRODEJEN = Question("Tržby z prodejen za 2025", "cs", "z-prodejen-za-2025")
        val WHAT_SHARE =
            Question("What share of total revenue came from the marketplace in 2025?", "en", "what-share")

        val REGISTRY: Registry =
            Registry.newBuilder().also { JsonFormat.parser().merge(resourceText("registry.json"), it) }.build()

        /** The live matcher's rows, by folded query. */
        val ROWS: Map<String, List<FuzzyMatch>> =
            Json.parseToJsonElement(resourceText("matcher.json")).jsonObject.mapValues { (_, rows) ->
                rows.jsonArray.map { row ->
                    FuzzyMatch.newBuilder().also { JsonFormat.parser().merge(row.toString(), it) }.build()
                }
            }

        fun resourceText(name: String): String =
            checkNotNull(ValueNamesMemberTest::class.java.getResource("/value-names-member/$name")) {
                "missing fixture $name"
            }.readText()

        fun fold(v: String): String =
            java.text.Normalizer
                .normalize(v.lowercase().trim(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")

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

        fun ResolutionState.mention(text: String): Mention =
            withClue("one mention spelled '$text' in ${mentionsList.map { it.span.text }}") {
                mentionsList.single { it.span.text == text }
            }

        /** A mention as a consumer reads it: what binds it, with which class and evidence, and its roles. */
        fun Mention.essence(): List<Any> =
            listOf(
                bindingsList.map { Triple(it.ref, it.targetClass, it.evidenceClass) },
                frameRolesList,
                span.text,
            )

        fun resolve(
            question: Question,
            matcher: WireMatcher = WireMatcher(),
        ): Turn {
            val pipeline =
                ResolverPipeline(
                    FakeNlp(question.parse),
                    matcher,
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
                            .setConversationId("value-names-member")
                            .setFresh(FreshQuestion.newBuilder().setText(question.text).setLocale(question.lang))
                            .setContext(ResolveContext.newBuilder().setReferenceDatetime("2026-10-13T18:26:00Z"))
                            .setRegistry(REGISTRY)
                            .build(),
                    )
                }
            return Turn(response)
        }
    }
}
