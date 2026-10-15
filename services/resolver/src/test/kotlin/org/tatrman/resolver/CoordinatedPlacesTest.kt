// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.google.protobuf.util.JsonFormat
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.CategoryStatus
import org.tatrman.fuzzy.v1.EntityCount
import org.tatrman.fuzzy.v1.EntityProfile
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
import org.tatrman.resolver.pipeline.NamedCategorySource
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.ResolveContext
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.fuzzy.v1.TargetClass as FuzzyTargetClass

/**
 * ttr-demo#62, the coordination ruling (2026-10-14), over member VALUES — through the whole pipeline.
 *
 * Two places joined by „a“ / „nebo“ / "and" / "or", one ambiguous and the other not: the ambiguous one
 * takes the reading where the two intersect, and nothing is asked (`CoordinatedValues`). On hartland's
 * Czech world `Brno` is the store "Brno" AND the distribution centre "Brno DC", one tie band apart;
 * `Liberec` is a store and nothing else; `Plzeň` is a store and "Plzeň DC". No customer city is a
 * member vocabulary on hartland, so these two vocabularies are every reading a town has.
 *
 * Under `Tržby` / "Revenue" — the all-channel revenue, whose fact reaches the warehouse and not the
 * store — „Tržby pro Brno a Liberec v roce 2025“ read `Brno` as "Brno DC" and left `Liberec` a bare
 * place (G3): the conjunct hangs on `Brno`, not on `Tržby`, so it had no scope of its own, and the one
 * it would have had does not reach the store. Now the conjunct is governed by what governs the
 * coordination, and the two meet at the store: both bind `store.store_name`.
 *
 * **Provenance (2026-10-15).**
 *  - Parses (`coordinated-places/<question>.parse.json`): hartland's nlp, as its resolver returned them
 *    live on 2026-10-14 (`traceId` and `elapsedMs` dropped). cs: MorphoDiTa
 *    `czech-morfflex2.0-pdtc1.0-220710` tokens, lemmas and tags, Stanza 1.10.0 dependencies, NameTag 3
 *    `nametag3-czech-cnec2.0-240830` entities. en: Stanza 1.10.0. ONE parse is constructed:
 *    „Tržby pro Liberec a Brno v roce 2025“ failed live before it returned one (an UNKNOWN, the regex
 *    overflow ttr-server#228 fixed), so its parse is the parse of „Tržby pro Brno a Liberec v roce 2025“
 *    with the two conjuncts exchanged — Liberec the `nmod` in the accusative, Brno the `conj` in the
 *    nominative — and NameTag's two `cnec:gu` entities placed on them.
 *  - Registry (`registry.json`): the projection `LexiconArchiveRegistrySource` builds from ttr-demo
 *    master's lexicon archive (`be2f0e0`, 600 entries, `sha256:6f6bcb9b…`).
 *  - Matcher rows (`matcher.json`): what lex-matcher (this repository's, in process) answered over that
 *    archive and hartland's Czech member vocabularies, rebuilt from ttr-demo's own data (`data/localize-cz`
 *    store, warehouse and reason values, the `kraj` codes, `data/catalog/cz.sql` product names) under the
 *    model's methods (`store_name` TYPOS(1), `warehouse_name` TOKENS, the states EXACT, …), lemmatised as
 *    hartland's nlp lemmatises a lower-cased token: common words to their lemma, a place name as written.
 *    It returns every live answer of 2026-10-14 to the character — `Brno DC` at 1.006772748873239 under
 *    `Tržby`, `Brně` → `Brno DC` at 0.8568, the G3 on `Liberec`, `Liberci`, `Plzeň`. Rows are keyed by
 *    the folded query, `scoped` for a request that named categories and `global` for one that named none
 *    (a cross-category lookup weighs its tokens over every vocabulary, so its scores differ). Each
 *    question was run with and without this change, and with and without a named-member profile, so the
 *    file holds what any of those asks.
 *  - The grounding kernel: chrono on a four-digit year, anchored on `er.entity.date_dim.cal_date`, as live.
 */
class CoordinatedPlacesTest :
    StringSpec({

        // ── the ruling: one ambiguous, one not — the reading where they intersect ─────────────────

        "„Tržby pro Brno a Liberec v roce 2025“ — Brno (store or DC) meets Liberec (a store) at the STORE, no ask" {
            val turn = resolve(BRNO_A_LIBEREC)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Tržby → $REVENUE [SUBJECT, MEASURE]",
                    "M roce → $CHRONO [FILTER]",
                    "V Brno LITERAL → $STORE_NAME#Brno",
                    "V Liberec LITERAL → $STORE_NAME#Liberec",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
            turn.answers()
            // the door tells the lattice's story: two store members, no warehouse
            turn.doorMembers() shouldContainExactly
                listOf("Brno" to "$STORE · $STORE_NAME", "Liberec" to "$STORE · $STORE_NAME")
        }

        "„Tržby pro Liberec a Brno v roce 2025“ — the other order reads the same" {
            val turn = resolve(LIBEREC_A_BRNO)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Tržby → $REVENUE [SUBJECT, MEASURE]",
                    "M roce → $CHRONO [FILTER]",
                    "V Liberec LITERAL → $STORE_NAME#Liberec",
                    "V Brno LITERAL → $STORE_NAME#Brno",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
            turn.answers()
        }

        "„Tržby pro Brno nebo Liberec v roce 2025“ — „nebo“ coordinates as „a“ does" {
            val turn = resolve(BRNO_NEBO_LIBEREC)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Tržby → $REVENUE [SUBJECT, MEASURE]",
                    "M roce → $CHRONO [FILTER]",
                    "V Brno LITERAL → $STORE_NAME#Brno",
                    "V Liberec LITERAL → $STORE_NAME#Liberec",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
            turn.answers()
        }

        "\"Revenue for Brno and Liberec in 2025\" — the same in English" {
            val turn = resolve(REVENUE_BRNO_AND_LIBEREC)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Revenue → $REVENUE [SUBJECT, MEASURE]",
                    "V Brno LITERAL → $STORE_NAME#Brno",
                    "V Liberec LITERAL → $STORE_NAME#Liberec",
                    "V 2025 GROUNDED → $CAL_DATE",
                )
            turn.answers()
        }

        // ── a governor that names the entity: its scope reads both conjuncts, as before ───────────

        "„Tržby prodejen Brno a Liberec v roce 2025“ — the store word governs both: the dimension, two store members" {
            // The store word governs `Brno`, so ttr-server#236's governed-value rule reads it as the store
            // dimension and `Brno` as its member. `Liberec` was a bare place (G3); as a conjunct it is
            // governed by the store word too.
            val turn = resolve(PRODEJEN_BRNO_A_LIBEREC)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Tržby → $REVENUE [SUBJECT, MEASURE]",
                    "M prodejen → $STORE []",
                    "M roce → $CHRONO [FILTER]",
                    "V Brno LITERAL → $STORE_NAME#Brno",
                    "V Liberec LITERAL → $STORE_NAME#Liberec",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
            turn.answers()
        }

        // ── nothing to intersect: today's reading stands ────────────────────────────────────────

        "„Tržby pro Brno a Plzeň v roce 2025“ — both are a store AND a DC: the scope reads both as DCs, no gap" {
            // `Plzeň` is the store "Plzeň" and "Plzeň DC", exactly as `Brno` is both: the two share both
            // readings, so the coordination decides nothing, and the measure's scope (its fact reaches the
            // warehouse) reads each as it reads a lone place. `Plzeň`, a conjunct, used to be a G3.
            val turn = resolve(BRNO_A_PLZEN)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Tržby → $REVENUE [SUBJECT, MEASURE]",
                    "M roce → $CHRONO [FILTER]",
                    "V Brno LITERAL → $WAREHOUSE_NAME#Brno DC",
                    "V Plzeň LITERAL → $WAREHOUSE_NAME#Plzeň DC",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
            turn.answers()
        }

        "„Tržby pro Brno v roce 2025“ — a lone Brno is unchanged: the DC, by the measure's scope" {
            val turn = resolve(BRNO)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Tržby → $REVENUE [SUBJECT, MEASURE]",
                    "M roce → $CHRONO [FILTER]",
                    "V Brno LITERAL → $WAREHOUSE_NAME#Brno DC",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
            turn.answers()
        }

        "„Tržby v Brně a Liberci v roce 2025“ — no store row reaches „Liberci“, so there is nothing to intersect" {
            // `store_name` is matched TYPOS(1) on the WRITTEN form, and „Liberci“ is two edits from
            // "Liberec": lex-matcher returns no row for it at all, whatever its lemma axis scores. With no
            // reading, the conjunct takes no part, and „Brně“ (the store at 0.86, "Brno DC" at 0.8568, one
            // band) keeps the measure's scope. Pinned as it reads today, so that a fix to how a declined
            // name reaches a TYPOS vocabulary changes it on purpose.
            val turn = resolve(V_BRNE_A_LIBERCI)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Tržby → $REVENUE [SUBJECT, MEASURE]",
                    "M roce → $CHRONO [FILTER]",
                    "V Brně LITERAL → $WAREHOUSE_NAME#Brno DC",
                    "V Liberci GROUNDED → ",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
            turn.lattice.gapsList.map { it.kind to it.span.text } shouldContainExactly
                listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED to "Liberci")
            ROWS.getValue(SCOPED).getValue("liberci") shouldBe emptyList()
        }

        // ── a place coordinated with a word the anchor does not take ─────────────────────────────

        "„Tržby za elektroniku a Brno“ — a conjunct of a word `Tržby` does not take: a bare place, as before" {
            // `elektroniku` hangs on `Tržby`, but a measure governs no ordinary value, so it is proposed as no
            // value of `Tržby`, and its conjunct is none either: `Brno` stays the bare place (a G3) it always
            // was. It is not the measure's governed place, so it is not read "Brno DC" by the measure's scope.
            // With the named-member profile on, the bare place is the profile's to read, as before (by the
            // question's fact: "Brno DC").
            val questions = listOf(ELEKTRONIKU_A_BRNO to "elektroniku", ELECTRONICS_AND_BRNO to "electronics")
            for ((question, word) in questions) {
                withClue(question.text) {
                    val plain = resolve(question).lattice
                    plain.summary().filter { it.startsWith("V ") } shouldContainExactly listOf("V Brno GROUNDED → ")
                    plain.gapsList.map { it.kind to it.span.text } shouldContainExactly
                        listOf(GapKind.GAP_KIND_G1_UNBOUND to word, GapKind.GAP_KIND_G3_UNATTRIBUTED to "Brno")

                    val named = resolve(question, named = true).lattice
                    named.summary().filter { it.startsWith("V ") } shouldContainExactly
                        listOf("V Brno LITERAL → $WAREHOUSE_NAME#Brno DC")
                }
            }
        }

        // ── with a named-member profile (store and warehouse names are places) ───────────────────

        "with lex-matcher's named-member profile on, every question reads as without it" {
            // UD-P3: a bare place is offered to the vocabularies whose values the NER calls places. A
            // conjunct is no longer bare — it is the governed value of the coordination's governor — so
            // the profile changes none of these. (Before this change it did: `Liberec` became a
            // NAMED_MEMBER store beside "Brno DC", a mixed answer.)
            for (question in ALL) {
                withClue(question.text) {
                    resolve(question, named = true).lattice.summary() shouldBe resolve(question).lattice.summary()
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

        /** Nothing is asked and nothing is left open. */
        fun answers() {
            withClue("gaps ${lattice.gapsList.map { it.kind to it.span.text }}") {
                response.hasAwaiting() shouldBe false
                lattice.gapsList shouldBe emptyList()
            }
        }

        /** The door's member bindings: span → `entity · attribute`. */
        fun doorMembers(): List<Pair<String, String>> =
            response.resolution.bindingsList
                .filter { it.hasDomain() && it.domain.memberOf.isNotBlank() }
                .map { it.span.text to "${it.domain.entityTypeRef} · ${it.domain.memberOf}" }
    }

    /**
     * lex-matcher as recorded (see the class doc): a request that names categories is answered from the
     * `scoped` rows of its folded query, kept to those categories; one that names none from the `global`
     * rows, as they came. A class filter keeps the rows of those classes. Rows come best first, cut at
     * the limit asked. A query the recording never saw is an error, not an empty answer: this file can
     * only answer what was asked of the real matcher.
     */
    private class WireMatcher(
        private val status: FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance(),
    ) : FuzzyClient {
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

        override suspend fun getStatus(): FuzzyStatusResponse = status

        private fun limitOf(
            asked: Int,
            default: Int,
        ) = if (asked > 0) asked else default

        private fun answer(
            query: String,
            categories: List<String>,
            classes: List<FuzzyTargetClass>,
        ): List<FuzzyMatch> {
            val recorded = ROWS.getValue(if (categories.isEmpty()) GLOBAL else SCOPED)
            return checkNotNull(recorded[fold(query)]) {
                "no recorded rows for '$query' (${if (categories.isEmpty()) GLOBAL else SCOPED}) — re-record matcher.json"
            }.filter { categories.isEmpty() || it.category in categories }
                .filter { classes.isEmpty() || it.targetClass in classes }
                .sortedByDescending { it.score }
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
        const val STORE = "er.entity.store"
        const val STORE_NAME = "er.entity.store.store_name"
        const val WAREHOUSE_NAME = "er.entity.warehouse.warehouse_name"
        const val REVENUE = "er.entity.channel_sales.ext_sales_price"
        const val CAL_DATE = "er.entity.date_dim.cal_date"
        const val CHRONO = "ground:chrono"
        const val SCOPED = "scoped"
        const val GLOBAL = "global"

        val BRNO_A_LIBEREC = Question("Tržby pro Brno a Liberec v roce 2025", "cs", "trzby-pro-brno-a-liberec")
        val LIBEREC_A_BRNO = Question("Tržby pro Liberec a Brno v roce 2025", "cs", "trzby-pro-liberec-a-brno")
        val V_BRNE_A_LIBERCI = Question("Tržby v Brně a Liberci v roce 2025", "cs", "trzby-v-brne-a-liberci")
        val BRNO_A_PLZEN = Question("Tržby pro Brno a Plzeň v roce 2025", "cs", "trzby-pro-brno-a-plzen")
        val BRNO = Question("Tržby pro Brno v roce 2025", "cs", "trzby-pro-brno")
        val REVENUE_BRNO_AND_LIBEREC =
            Question("Revenue for Brno and Liberec in 2025", "en", "revenue-for-brno-and-liberec")
        val PRODEJEN_BRNO_A_LIBEREC =
            Question("Tržby prodejen Brno a Liberec v roce 2025", "cs", "trzby-prodejen-brno-a-liberec")
        val BRNO_NEBO_LIBEREC = Question("Tržby pro Brno nebo Liberec v roce 2025", "cs", "trzby-pro-brno-nebo-liberec")
        val ELEKTRONIKU_A_BRNO = Question("Tržby za elektroniku a Brno", "cs", "trzby-za-elektroniku-a-brno")
        val ELECTRONICS_AND_BRNO =
            Question("Revenue for electronics and Brno", "en", "revenue-for-electronics-and-brno")

        val ALL =
            listOf(
                BRNO_A_LIBEREC,
                LIBEREC_A_BRNO,
                V_BRNE_A_LIBERCI,
                BRNO_A_PLZEN,
                BRNO,
                REVENUE_BRNO_AND_LIBEREC,
                PRODEJEN_BRNO_A_LIBEREC,
                BRNO_NEBO_LIBEREC,
            )

        val REGISTRY: Registry =
            Registry.newBuilder().also { JsonFormat.parser().merge(resourceText("registry.json"), it) }.build()

        /** The recorded matcher rows: `scoped` / `global` → folded query → rows. */
        val ROWS: Map<String, Map<String, List<FuzzyMatch>>> =
            Json.parseToJsonElement(resourceText("matcher.json")).jsonObject.mapValues { (_, byQuery) ->
                byQuery.jsonObject.mapValues { (_, rows) ->
                    rows.jsonArray.map { row ->
                        FuzzyMatch.newBuilder().also { JsonFormat.parser().merge(row.toString(), it) }.build()
                    }
                }
            }

        /** lex-matcher's `GetStatus` profile: the store and warehouse names hold places (cs and en NER). */
        val PROFILE: FuzzyStatusResponse =
            FuzzyStatusResponse
                .newBuilder()
                .setReady(true)
                .addAllCategories(
                    listOf(STORE_NAME, WAREHOUSE_NAME).map { category ->
                        CategoryStatus
                            .newBuilder()
                            .setCategory(category)
                            .setSource(SourceTag.MEMBER)
                            .setEntityProfile(
                                EntityProfile
                                    .newBuilder()
                                    .setMembers(2)
                                    .setNerModel("nametag3-czech-cnec2.0-240830")
                                    .addEntities(
                                        EntityCount
                                            .newBuilder()
                                            .setLabel("LOCATION")
                                            .setCode("cnec:gu")
                                            .setMembers(1),
                                    ).addEntities(EntityCount.newBuilder().setLabel("GPE").setMembers(1)),
                            ).build()
                    },
                ).build()

        fun resourceText(name: String): String =
            checkNotNull(CoordinatedPlacesTest::class.java.getResource("/coordinated-places/$name")) {
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

        fun resolve(
            question: Question,
            named: Boolean = false,
        ): Turn {
            val matcher = WireMatcher(if (named) PROFILE else FuzzyStatusResponse.getDefaultInstance())
            val pipeline =
                ResolverPipeline(
                    FakeNlp(question.parse),
                    matcher,
                    SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), ResolverThresholds.LIVE),
                    emptyMap(),
                    ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                    grounding = YearKernel(),
                    defaultPackage = "hartland",
                    namedCategories = if (named) NamedCategorySource(matcher) else null,
                )
            val response =
                runBlocking {
                    pipeline.resolve(
                        ResolveRequest
                            .newBuilder()
                            .setConversationId("coordinated-places")
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
