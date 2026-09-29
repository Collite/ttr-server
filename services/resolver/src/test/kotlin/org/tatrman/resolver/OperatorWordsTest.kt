// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.grpc.Status
import io.grpc.StatusException
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.LookupResponse
import org.tatrman.fuzzy.v1.Provenance
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Capability
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.LookupRoundConfig
import org.tatrman.resolver.pipeline.LookupRounds
import org.tatrman.resolver.pipeline.OperatorWords
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.EntityType
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.Mention
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.TargetClass
import org.tatrman.resolver.v1.ValueKind
import org.tatrman.fuzzy.v1.TargetClass as FuzzyTargetClass

/**
 * ttr-server#58 — an operator word the tagger tags correctly reaches the lattice.
 *
 * The registry here is shaped like the ARCHIVE channel's: model objects with anchors, and no
 * operator at all — `LexiconArchiveRegistrySource` declares none, which is the whole bug. The
 * matcher knows the operators (as lex-matcher does, from the compiled skills), so every operator
 * binding below had to come from asking it.
 *
 * The parses are the Stanza trees hartland returned for these questions (2026-09-29), trimmed to
 * the fields the resolver reads.
 */
class OperatorWordsTest :
    StringSpec({

        fun ResolveResponse.mentionsOn(text: String): List<Mention> =
            resolutionState.mentionsList.filter { it.span.text == text }

        /** The refs bound on the one mention spelled [text]. */
        fun ResolveResponse.refsOn(text: String): List<String> = mentionsOn(text).single().bindingsList.map { it.ref }

        fun ResolveResponse.operatorMentions(): List<Mention> =
            resolutionState.mentionsList.filter {
                it.bindingsList.firstOrNull()?.targetClass == TargetClass.TARGET_CLASS_OPERATOR
            }

        fun ResolveResponse.gapsOn(text: String): List<GapKind> =
            resolutionState.gapsList.filter { it.span.text == text }.map { it.kind }

        fun ResolveResponse.valueOn(text: String) = resolutionState.valuesList.single { it.span.text == text }

        // ── #58's four cases ──────────────────────────────────────────────────────────────────

        "#58 — the English imperative `Show` is a mention bound to op:show" {
            val fuzzy = OpFuzzy()
            val r = resolve(SHOW_ME_REVENUE, fuzzy)

            val show = r.mentionsOn("Show").single()
            show.bindingsList.map { it.ref } shouldContainExactly listOf("op:show")
            show.bindingsList.single().targetClass shouldBe TargetClass.TARGET_CLASS_OPERATOR
            // asked once, in the operator class and nothing else
            val asked = fuzzy.lookups.single { it.term == "Show" }
            asked.targetClassesList shouldContainExactly listOf(FuzzyTargetClass.TARGET_CLASS_OPERATOR)
            asked.categoriesList.shouldBeEmpty()
            // and the rest of the question is what it was: the measure and the grain still bind
            r.refsOn("revenue") shouldContainExactly listOf(REVENUE)
            r.gapsOn("Show").shouldBeEmpty()
        }

        "#58 — a verb that is not a command is not asked about: `I bought a car`" {
            val fuzzy = OpFuzzy()
            val r = resolve(I_BOUGHT_A_CAR, fuzzy)

            fuzzy.lookups.filter { it.term == "bought" }.shouldBeEmpty()
            r.mentionsOn("bought").shouldBeEmpty()
            r.operatorMentions().shouldBeEmpty()
            r.gapsOn("bought").shouldBeEmpty()
        }

        "#58 — a command the matcher does not know is asked about and dropped: no mention, no gap" {
            val fuzzy = OpFuzzy()
            val r = resolve(BUY_A_CAR, fuzzy)

            // the gate is the matcher's miss, not the proposal: the word WAS asked about
            fuzzy.lookups.single { it.term == "Buy" }.targetClassesList shouldContainExactly
                listOf(FuzzyTargetClass.TARGET_CLASS_OPERATOR)
            r.mentionsOn("Buy").shouldBeEmpty()
            r.operatorMentions().shouldBeEmpty()
            r.gapsOn("Buy").shouldBeEmpty()
        }

        "#58 — Czech is unchanged: `Zobraz náklady` has exactly one operator mention, mis-tagged or not" {
            for (question in listOf(ZOBRAZ_NAKLADY_NOUN, ZOBRAZ_NAKLADY_VERB)) {
                val r = resolve(question, OpFuzzy())

                r.operatorMentions().map { it.span.text } shouldContainExactly listOf("Zobraz")
                r.refsOn("Zobraz") shouldContainExactly listOf("op:show")
                r.refsOn("náklady") shouldContainExactly listOf(COST)
            }
        }

        // ── the count slot ────────────────────────────────────────────────────────────────────

        "`nejlepších 10 prodejen` — the adjective is op:top-n and `10` is its argument, not a store" {
            val r = resolve(NEJLEPSICH_10_PRODEJEN, OpFuzzy())

            val topN = r.mentionsOn("nejlepších").single()
            topN.bindingsList.map { it.ref } shouldContainExactly listOf("op:top-n")
            val ten = r.valueOn("10")
            ten.kind shouldBe ValueKind.VALUE_KIND_LITERAL
            ten.anchorMentionId shouldBe topN.id
            r.gapsOn("10").shouldBeEmpty()
            // the noun is its own mention again, no longer `nejlepších prodejen`
            r.refsOn("prodejen") shouldContainExactly listOf(STORE)
        }

        "the count slot holds whichever word the parser hangs the adjective on, and in either order" {
            for (question in listOf(ZOBRAZ_NEJLEPSICH_10_PRODEJEN, TEN_NEJLEPSICH_PRODEJEN)) {
                val r = resolve(question, OpFuzzy())

                val topN = r.mentionsOn("nejlepších").single()
                topN.bindingsList.map { it.ref } shouldContainExactly listOf("op:top-n")
                r.valueOn("10").anchorMentionId shouldBe topN.id
                r.gapsOn("10").shouldBeEmpty()
            }
        }

        "the English count, where Stanza hangs the NUMBER on the adjective: `the 5 largest stores`" {
            val r = resolve(THE_5_LARGEST_STORES, OpFuzzy())

            val topN = r.mentionsOn("largest").single()
            topN.bindingsList.map { it.ref } shouldContainExactly listOf("op:top-n")
            r.valueOn("5").anchorMentionId shouldBe topN.id
            r.gapsOn("5").shouldBeEmpty()
        }

        "`dalších 10 prodejen` — asked, not an operator, and `10` is the store's as before" {
            val fuzzy = OpFuzzy()
            val r = resolve(DALSICH_10_PRODEJEN, fuzzy)

            fuzzy.lookups.single { it.term == "dalších" }.targetClassesList shouldContainExactly
                listOf(FuzzyTargetClass.TARGET_CLASS_OPERATOR)
            r.mentionsOn("dalších").shouldBeEmpty()
            r.valueOn("10").anchorMentionId shouldBe r.mentionsOn("prodejen").single().id
            r.gapsOn("10") shouldContainExactly listOf(GapKind.GAP_KIND_G4_METHOD_MISS)
        }

        "a predicate adjective before a count is not a slot: `Tržby byly nejvyšší 3 roky po sobě`" {
            OperatorWords.slots(NEJVYSSI_3_ROKY.parse).shouldBeEmpty()
        }

        // ── what the new path may and may not do ──────────────────────────────────────────────

        "a command word scopes no literal: `Show 501001` leaves the code exactly as it was" {
            val r = resolve(SHOW_501001, OpFuzzy())
            val without = resolve(SHOW_501001, OpFuzzy(), LookupRoundConfig.DISABLED)

            val show = r.mentionsOn("Show").single()
            show.bindingsList.map { it.ref } shouldContainExactly listOf("op:show")
            // A code is not the verb's argument: nothing is anchored to the command, and the values
            // and gaps are the ones the question had before the verb was a mention at all.
            r.resolutionState.valuesList.none { it.anchorMentionId == show.id } shouldBe true
            r.resolutionState.valuesList shouldBe without.resolutionState.valuesList
            r.resolutionState.gapsList shouldBe without.resolutionState.gapsList
        }

        "a matcher that ignores the class filter cannot turn a command into a model object" {
            val r = resolve(SHOW_ME_REVENUE, OpFuzzy(rogue = true))

            r.mentionsOn("Show").shouldBeEmpty()
            r.operatorMentions().shouldBeEmpty()
        }

        "an unreachable matcher leaves the question as it was without the lookup" {
            val failing = resolve(SHOW_ME_REVENUE, OpFuzzy(failLookups = true))
            val off = resolve(SHOW_ME_REVENUE, OpFuzzy(), LookupRoundConfig.DISABLED)

            failing.mentionsOn("Show").shouldBeEmpty()
            off.mentionsOn("Show").shouldBeEmpty()
            failing.resolutionState.mentionsList.map { it.span.text } shouldContainExactly
                off.resolutionState.mentionsList.map { it.span.text }
        }

        "the recorded corpus: exactly these words are asked about, and nothing else" {
            // The RV-P0.2 frame-role corpus — real Stanza parses of 47 Czech and English questions,
            // entities included. This pins the new path's REACH: every word below costs one
            // operator-class lookup, and any change to the slot rules shows up here as a diff.
            //
            // Against the compiled stdlib skills, 18 of the 20 are operator words (show: Show, Ukaž,
            // List · compare: Compare, Porovnej · top-n: top, Top, prvních) and the other two
            // (`posledních`, `čerpacích`) are misses the matcher drops: zero spurious operators, and
            // no bind of any other class is possible on this path. `Zobraz` is absent because
            // Stanza tags it NOUN — the mention layer's word, as before.
            val asked =
                corpus().mapNotNull { parse ->
                    val words = OperatorWords.slots(parse).map { (i, slot) -> "${parse.getTokens(i).text}:$slot" }
                    if (words.isEmpty()) null else parse.tokensList.joinToString(" ") { it.text } to words
                }
            asked.sortedBy { it.first }.map { (q, w) -> "$q -> $w" } shouldContainExactly
                listOf(
                    "Compare web and store revenue for 2025 -> [Compare:COMMAND]",
                    "List the products with the highest returns in 2025 -> [List:COMMAND]",
                    "Porovnej to s loňskem -> [Porovnej:COMMAND]",
                    "Porovnej tržby Elektroniky a Knih podle měsíce -> [Porovnej:COMMAND]",
                    "Porovnej tržby z e - shopu a z prodejen za rok 2025 -> [Porovnej:COMMAND]",
                    "Show me the costs of account 501001 in 2025 by period . -> [Show:COMMAND]",
                    "Show revenue by category and month -> [Show:COMMAND]",
                    "Show revenue by store -> [Show:COMMAND]",
                    "Show the top 10 products by revenue -> [Show:COMMAND, top:COUNT]",
                    "Top 10 gas stations in Prague by revenue . -> [Top:COUNT]",
                    "Ukaž mi tržby za obuv podle čtvrtletí -> [Ukaž:COMMAND]",
                    "Ukaž tržby podle kategorie a měsíce -> [Ukaž:COMMAND]",
                    "Ukaž tržby značky Nike podle měsíce -> [Ukaž:COMMAND]",
                    "Ukaž vratky podle důvodu vrácení -> [Ukaž:COMMAND]",
                    "Ukaž vývoj nákladů střediska 220 za posledních 12 měsíců a porovnej s plánem . -> " +
                        "[Ukaž:COMMAND, posledních:COUNT]",
                    "Zobraz prvních 10 produktů podle tržby -> [prvních:COUNT]",
                    "Zobraz prvních 10 čerpacích stanic v Praze podle tržby za 12 měsíců . -> " +
                        "[prvních:COUNT, čerpacích:COUNT]",
                )
        }

        "a word inside a quoted literal is never a slot" {
            val q = SHOW_ME_REVENUE
            val quoted = "\"Show\" me revenue per month"
            val parse =
                q.parse
                    .toBuilder()
                    .apply {
                        for (i in 0 until tokensCount) {
                            val t = getTokens(i)
                            setTokens(i, t.toBuilder().setCharStart(t.charStart + 1).setCharEnd(t.charEnd + 1))
                        }
                    }.build()
            val literals =
                org.tatrman.resolver.pipeline.Literals
                    .of(quoted, parse)
            OperatorWords.slots(parse, literals).shouldBeEmpty()
        }
    }) {
    /** A question with its parse; [tokens] are `(surface, lemma, upos, head, relation, feats)`. */
    private class Question(
        val text: String,
        val lang: String,
        vararg tokens: Tok,
    ) {
        val parse: AnalyzeResponse =
            run {
                var from = 0
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage(lang)
                    .setDetectedLanguage(lang)
                    .setTraceId("op-words")
                    .addAllTokens(
                        tokens.map { t ->
                            val start = text.indexOf(t.text, from)
                            require(start >= 0) { "'${t.text}' not in '$text' after $from" }
                            from = start + t.text.length
                            Token
                                .newBuilder()
                                .setText(t.text)
                                .setCharStart(start)
                                .setCharEnd(from)
                                .setLemma(t.lemma)
                                .setUpos(t.upos)
                                .setDepHead(t.head)
                                .setDepRelation(t.relation)
                                .putAllFeats(t.feats)
                                .build()
                        },
                    ).build()
            }
    }

    private data class Tok(
        val text: String,
        val lemma: String,
        val upos: String,
        val head: Int,
        val relation: String,
        val feats: Map<String, String> = emptyMap(),
    )

    /**
     * The operator rows of the compiled skills (a subset), keyed by folded surface, and one DECLARED
     * row per model object whose anchor shares a stem with the query. Answers only the categories
     * a span asked for, and a `Lookup` only in the classes it asked for — unless [rogue], when it
     * answers every `Lookup` with a model-object row regardless.
     */
    private class OpFuzzy(
        private val rogue: Boolean = false,
        private val failLookups: Boolean = false,
    ) : FuzzyClient {
        val lookups: MutableList<LookupRequest> = mutableListOf()

        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            val builder = BatchMatchResponse.newBuilder()
            for (span in request.spansList) {
                val asked = span.categoriesList.toSet()
                builder.addResults(
                    FuzzyMatchResponse.newBuilder().addAllMatches(rows(span.query).filter { it.category in asked }),
                )
            }
            return builder.build()
        }

        override suspend fun lookup(request: LookupRequest): LookupResponse {
            lookups += request
            if (failLookups) throw StatusException(Status.UNAVAILABLE.withDescription("lex-matcher is down"))
            val rows =
                if (rogue) {
                    listOf(row(request.term, STORE, FuzzyTargetClass.TARGET_CLASS_MODEL_OBJECT))
                } else {
                    rows(request.term)
                        .filter { request.categoriesCount == 0 || it.category in request.categoriesList }
                        .filter { request.targetClassesCount == 0 || it.targetClass in request.targetClassesList }
                }
            return LookupResponse.newBuilder().addAllCandidates(rows).build()
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()

        private fun rows(query: String): List<FuzzyMatch> {
            val q = fold(query)
            val out = mutableListOf<FuzzyMatch>()
            OPERATORS[q]?.let { out += row(query, it, FuzzyTargetClass.TARGET_CLASS_OPERATOR) }
            for (etype in REGISTRY.entityTypesList) {
                if (etype.anchorsList.any { stem(fold(it), q) }) {
                    out += row(query, etype.ref, FuzzyTargetClass.TARGET_CLASS_MODEL_OBJECT)
                }
            }
            return out
        }

        private fun stem(
            anchor: String,
            query: String,
        ): Boolean {
            if (anchor == query) return true
            val shared = anchor.commonPrefixWith(query).length
            return shared >= 4 && shared >= minOf(anchor.length, query.length) - 2
        }

        private fun row(
            query: String,
            ref: String,
            targetClass: FuzzyTargetClass,
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId("lex:$ref:${fold(query)}")
                .setCandidate(query)
                .setScore(1.0)
                .setCategory(ref)
                .setSource(SourceTag.DECLARED)
                .setTargetRef(ref)
                .setTargetClass(targetClass)
                .setMatchMethod("EXACT")
                .setProvenance(
                    Provenance
                        .newBuilder()
                        .setProducer("lex-matcher")
                        .setMethod("TATRMAN")
                        .setRawScore(1.0),
                ).build()
    }

    private class FakeNlp(
        private val parse: AnalyzeResponse,
    ) : NlpClient {
        override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse = parse

        override suspend fun getStatus(): StatusResponse =
            StatusResponse
                .newBuilder()
                .setReady(true)
                .addCapabilities(
                    Capability
                        .newBuilder()
                        .setLanguage(parse.language)
                        .setOp(NlpOp.DEP_PARSE)
                        .setEngine("stanza"),
                ).build()
    }

    private companion object {
        const val STORE = "er.entity.store"
        const val REVENUE = "md.measure.revenue"
        const val COST = "md.measure.cost"
        const val MONTH = "er.entity.date_dim.month"

        /** A subset of the compiled `lexicon-stdlib/skills` triggers, as lex-matcher serves them. */
        val OPERATORS =
            mapOf(
                "show" to "op:show",
                "zobraz" to "op:show",
                "nejlepsich" to "op:top-n",
                "prvnich" to "op:top-n",
                "top" to "op:top-n",
                "largest" to "op:top-n",
            )

        /** The archive channel's shape: model objects only — no operator is declared. */
        val REGISTRY: Registry =
            Registry
                .newBuilder()
                .addEntityTypes(etype(STORE, "entity", "prodejna", "store"))
                .addEntityTypes(etype(REVENUE, "measure", "tržba", "revenue"))
                .addEntityTypes(etype(COST, "measure", "náklad"))
                .addEntityTypes(etype(MONTH, "attribute", "month"))
                .addLocales("cs")
                .addLocales("en")
                .setSnapshotHash("op-words")
                .build()

        fun etype(
            ref: String,
            kind: String,
            vararg anchors: String,
        ): EntityType =
            EntityType
                .newBuilder()
                .setRef(ref)
                .addCategories(ref)
                .addAllAnchors(anchors.toList())
                .setObjectKind(kind)
                .build()

        fun fold(v: String): String =
            java.text.Normalizer
                .normalize(v.lowercase(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")

        val IMP = mapOf("Mood" to "Imp", "VerbForm" to "Fin")
        val IND = mapOf("Mood" to "Ind", "Tense" to "Past", "VerbForm" to "Fin")

        val SHOW_ME_REVENUE =
            Question(
                "Show me revenue per month",
                "en",
                Tok("Show", "show", "VERB", 0, "root", IMP),
                Tok("me", "I", "PRON", 1, "iobj"),
                Tok("revenue", "revenue", "NOUN", 1, "obj"),
                Tok("per", "per", "ADP", 5, "case"),
                Tok("month", "month", "NOUN", 3, "nmod"),
            )

        val I_BOUGHT_A_CAR =
            Question(
                "I bought a car",
                "en",
                Tok("I", "I", "PRON", 2, "nsubj"),
                Tok("bought", "buy", "VERB", 0, "root", IND),
                Tok("a", "a", "DET", 4, "det"),
                Tok("car", "car", "NOUN", 2, "obj"),
            )

        val BUY_A_CAR =
            Question(
                "Buy a car",
                "en",
                Tok("Buy", "buy", "VERB", 0, "root", IMP),
                Tok("a", "a", "DET", 3, "det"),
                Tok("car", "car", "NOUN", 1, "obj"),
            )

        val SHOW_501001 =
            Question(
                "Show 501001",
                "en",
                Tok("Show", "show", "VERB", 0, "root", IMP),
                Tok("501001", "501001", "NUM", 1, "obj"),
            )

        /** What Czech Stanza does with the imperative: NOUN, the root (P0.2 report). */
        val ZOBRAZ_NAKLADY_NOUN =
            Question(
                "Zobraz náklady",
                "cs",
                Tok("Zobraz", "zobraz", "NOUN", 0, "root"),
                Tok("náklady", "náklad", "NOUN", 1, "nmod"),
            )

        /** …and what a correct tagger does with it. */
        val ZOBRAZ_NAKLADY_VERB =
            Question(
                "Zobraz náklady",
                "cs",
                Tok("Zobraz", "zobrazit", "VERB", 0, "root", mapOf("Mood" to "Imp", "Person" to "2")),
                Tok("náklady", "náklad", "NOUN", 1, "obj"),
            )

        /** Root noun phrase: Stanza hangs the adjective on the NUMBER. */
        val NEJLEPSICH_10_PRODEJEN =
            Question(
                "nejlepších 10 prodejen",
                "cs",
                Tok("nejlepších", "lepší", "ADJ", 2, "amod", mapOf("Degree" to "Sup")),
                Tok("10", "10", "NUM", 3, "nummod:gov"),
                Tok("prodejen", "prodejna", "NOUN", 0, "root"),
            )

        /** Embedded noun phrase: Stanza hangs the adjective on the NOUN. */
        val ZOBRAZ_NEJLEPSICH_10_PRODEJEN =
            Question(
                "Zobraz nejlepších 10 prodejen",
                "cs",
                Tok("Zobraz", "zobraz", "NOUN", 0, "root"),
                Tok("nejlepších", "lepší", "ADJ", 4, "amod", mapOf("Degree" to "Sup")),
                Tok("10", "10", "NUM", 4, "nummod:gov"),
                Tok("prodejen", "prodejna", "NOUN", 1, "nmod"),
            )

        val TEN_NEJLEPSICH_PRODEJEN =
            Question(
                "10 nejlepších prodejen",
                "cs",
                Tok("10", "10", "NUM", 3, "nummod:gov"),
                Tok("nejlepších", "lepší", "ADJ", 3, "amod", mapOf("Degree" to "Sup")),
                Tok("prodejen", "prodejna", "NOUN", 0, "root"),
            )

        /** hartland's English tree (2026-09-29): 5 → largest → stores. */
        val THE_5_LARGEST_STORES =
            Question(
                "the 5 largest stores",
                "en",
                Tok("the", "the", "DET", 4, "det"),
                Tok("5", "5", "NUM", 3, "nummod"),
                Tok("largest", "large", "ADJ", 4, "amod", mapOf("Degree" to "Sup")),
                Tok("stores", "store", "NOUN", 0, "root"),
            )

        val DALSICH_10_PRODEJEN =
            Question(
                "dalších 10 prodejen",
                "cs",
                Tok("dalších", "další", "ADJ", 2, "amod"),
                Tok("10", "10", "NUM", 3, "nummod:gov"),
                Tok("prodejen", "prodejna", "NOUN", 0, "root"),
            )

        /** The predicate: the adjective is the ROOT, and the count hangs from the noun under it. */
        val NEJVYSSI_3_ROKY =
            Question(
                "Tržby byly nejvyšší 3 roky po sobě",
                "cs",
                Tok("Tržby", "tržba", "NOUN", 3, "nsubj"),
                Tok("byly", "být", "AUX", 3, "cop"),
                Tok("nejvyšší", "vysoký", "ADJ", 0, "root", mapOf("Degree" to "Sup")),
                Tok("3", "3", "NUM", 5, "nummod"),
                Tok("roky", "rok", "NOUN", 3, "obl"),
                Tok("po", "po", "ADP", 7, "case"),
                Tok("sobě", "se", "PRON", 5, "nmod"),
            )

        /** Every recorded parse of the frame-role corpus, entities included. */
        fun corpus(): List<AnalyzeResponse> {
            val url = checkNotNull(OperatorWordsTest::class.java.getResource("/frame-roles/parses"))
            val dir = java.io.File(url.toURI())
            val parser =
                com.google.protobuf.util.JsonFormat
                    .parser()
                    .ignoringUnknownFields()
            return dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.map { f ->
                AnalyzeResponse.newBuilder().also { parser.merge(f.readText(), it) }.build()
            }
        }

        fun resolve(
            question: Question,
            fuzzy: OpFuzzy,
            config: LookupRoundConfig = LookupRoundConfig.DEFAULT,
        ): ResolveResponse {
            val pipeline =
                ResolverPipeline(
                    FakeNlp(question.parse),
                    fuzzy,
                    SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), ResolverThresholds.LIVE),
                    emptyMap(),
                    ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                    lookupRounds = LookupRounds(fuzzy, config),
                )
            val request =
                ResolveRequest
                    .newBuilder()
                    .setConversationId("op-words")
                    .setFresh(FreshQuestion.newBuilder().setText(question.text).setLocale(question.lang))
                    .setRegistry(REGISTRY)
                    .build()
            return runBlocking { pipeline.resolve(request) }
        }
    }
}
