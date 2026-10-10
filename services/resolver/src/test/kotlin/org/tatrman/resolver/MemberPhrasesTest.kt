// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.grpc.Status
import io.grpc.StatusException
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
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
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.LookupRoundConfig
import org.tatrman.resolver.pipeline.LookupRounds
import org.tatrman.resolver.pipeline.MemberPhrases
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.EntityType
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.ValueFinding

/**
 * A member's label typed out unquoted, which the parse reads as a clause, binds as a value of its
 * member vocabulary ([MemberPhrases]).
 *
 * The hero is the Czech demo's 3.4 on hartland (CZ world, 2026-10-10): *„…nejvyšší podíl reklamací
 * s důvodem Nedorazilo včas?“* proposed no span at all for the reason, the composer wrote the US
 * world's `'Did not get it on time'`, and the answer was 0 rows. The parses are the Stanza trees
 * hartland's nlp returned for these questions that day, trimmed to the fields the resolver reads.
 *
 * The matcher below answers a member vocabulary the way lex-matcher's TOKENS method does — every
 * value sharing a word with the query comes back, scored by how much of the value it covers — so
 * the near misses are real, and the verbatim rule is what keeps them out.
 */
class MemberPhrasesTest :
    StringSpec({

        fun ResolveResponse.valueOn(text: String): ValueFinding =
            resolutionState.valuesList.single {
                it.span.text ==
                    text
            }

        fun ResolveResponse.valuesStartingWith(word: String): List<ValueFinding> =
            resolutionState.valuesList.filter { it.span.text.startsWith(word, ignoreCase = true) }

        fun ResolveResponse.gapsOn(text: String) = resolutionState.gapsList.filter { it.span.text == text }

        // ── the hero, both worlds ─────────────────────────────────────────────────────────────

        "3.4 cs — „s důvodem Nedorazilo včas“ is a value of reason_desc, bound to that very member" {
            val fuzzy = ReasonFuzzy()
            val r = resolve(Q34_CS, fuzzy)

            val value = r.valueOn("Nedorazilo včas")
            val attribution = value.attributionsList.single()
            attribution.attributeRef shouldBe REASON_DESC
            attribution.binding.ref shouldEndWith "#Nedorazilo včas"
            r.gapsOn("Nedorazilo včas").shouldBeEmpty()
            // asked of the member vocabularies and of nothing else
            val asked = fuzzy.batches.first().spansList
            asked.map { it.query } shouldContainExactly listOf("mělo ve 2", "mělo ve", "Nedorazilo včas")
            asked.forEach { it.categoriesList shouldContainExactly MEMBER_CATEGORIES }
        }

        "3.4 en — „with reason Did not get it on time in 2025“ binds the whole label, not a prefix" {
            val r = resolve(Q34_EN, ReasonFuzzy())

            val value = r.valueOn("Did not get it on time")
            value.attributionsList.single().attributeRef shouldBe REASON_DESC
            value.attributionsList
                .single()
                .binding.ref shouldEndWith "#Did not get it on time"
            // the year is still a date, not part of the label
            r.valuesStartingWith("Did").shouldHaveSize(1)
        }

        "typed in lower case without diacritics — „s duvodem nedorazilo vcas“ — still finds the member" {
            val r = resolve(NEDORAZILO_FOLDED, ReasonFuzzy())

            val value = r.valueOn("nedorazilo vcas")
            value.attributionsList
                .single()
                .binding.ref shouldEndWith "#Nedorazilo včas"
        }

        "a label the user capitalised is a slot even when it starts with a noun — „s důvodem Výměna dárku“" {
            val r = resolve(VYMENA_DARKU, ReasonFuzzy())

            r
                .valueOn("Výměna dárku")
                .attributionsList
                .single()
                .binding.ref shouldEndWith "#Výměna dárku"
        }

        // ── what it must not do ───────────────────────────────────────────────────────────────

        "words that only SHARE a word with a member are not that member — „s důvodem nedorazilo zboží“" {
            val fuzzy = ReasonFuzzy()
            val r = resolve(NEDORAZILO_ZBOZI, fuzzy)

            // the matcher did answer — with 'Nedorazilo včas', which is not what was typed
            fuzzy.batches
                .first()
                .spansList
                .map { it.query } shouldContainExactly listOf("nedorazilo zboží")
            r.valuesStartingWith("nedorazilo").shouldBeEmpty()
            r.gapsOn("nedorazilo zboží").shouldBeEmpty()
        }

        "a clause that matches no member leaves no value and no gap — `centrum mělo ve 2`" {
            val r = resolve(Q34_CS, ReasonFuzzy())

            r.valuesStartingWith("mělo").shouldBeEmpty()
            r.resolutionState.gapsList
                .filter { it.span.text.startsWith("mělo") }
                .shouldBeEmpty()
        }

        "a row spelled like the words is not a member unless it IS a member row — DECLARED never confirms" {
            // the matcher echoes every member-category query back as a DECLARED row of reason_desc
            // and holds no member values at all: right category, right text, wrong layer
            val r = resolve(Q34_CS, ReasonFuzzy(declaredEcho = true))

            r.valuesStartingWith("Nedorazilo").shouldBeEmpty()
        }

        "off with the lookup rung: not asked, and the question is what it was before" {
            val fuzzy = ReasonFuzzy()
            val r = resolve(Q34_CS, fuzzy, LookupRoundConfig.DISABLED)

            fuzzy.batches
                .flatMap { it.spansList }
                .filter { it.query == "Nedorazilo včas" }
                .shouldBeEmpty()
            r.valuesStartingWith("Nedorazilo").shouldBeEmpty()
        }

        "a matcher that fails the member lookup leaves the question as it was, and the broad pass still runs" {
            val failed = resolve(Q34_CS, ReasonFuzzy(failMemberLookup = true))
            val without = resolve(Q34_CS, ReasonFuzzy(), LookupRoundConfig.DISABLED)

            failed.valuesStartingWith("Nedorazilo").shouldBeEmpty()
            failed.resolutionState.mentionsList.map { it.span.text } shouldBe
                without.resolutionState.mentionsList.map { it.span.text }
            failed.resolutionState.gapsList.map { it.span.text to it.kind } shouldBe
                without.resolutionState.gapsList.map { it.span.text to it.kind }
            failed.resolutionState.valuesList.map { it.span.text } shouldBe
                without.resolutionState.valuesList.map { it.span.text }
        }

        // ── the slot rule, on the parse alone ─────────────────────────────────────────────────

        "slots — a noun followed by a clause; never a lower-case noun phrase, a proper noun or a preposition" {
            val slots = MemberPhrases.slots(Q34_CS.parse)
            slots.map { s -> s.head to s.run } shouldContainExactly
                listOf(
                    2 to listOf(3, 4, 5), // centrum → mělo ve 2   (the `.` ends it)
                    13 to listOf(14, 15), // důvodem → Nedorazilo včas   (the `?` ends it)
                )
            // `podíl reklamací` (noun, lower-case noun) and `reklamací s` (noun, preposition) are not slots
            MemberPhrases.slots(PRAHA_DC).shouldBeEmpty()
        }

        "slots — the run stops at a universal and at a conjunction, and needs two words" {
            // en: `in 2025` — the year is a date, so the run is `Did … in`
            MemberPhrases.slots(Q34_EN.parse).single { it.head == 10 }.run shouldContainExactly
                listOf(11, 12, 13, 14, 15, 16, 17)
            // `důvodem Nedorazilo a …` — one word before the conjunction is no label
            MemberPhrases.slots(ONE_WORD_THEN_AND.parse).shouldBeEmpty()
        }
    }) {
    private class Question(
        val text: String,
        val lang: String,
        vararg tokens: Tok,
        val entities: List<Triple<String, String, Int>> = emptyList(),
    ) {
        val parse: AnalyzeResponse =
            run {
                var from = 0
                val built =
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
                            .build()
                    }
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage(lang)
                    .setDetectedLanguage(lang)
                    .setTraceId("member-phrases")
                    .addAllTokens(built)
                    .addAllEntities(
                        entities.map { (surface, label, occurrence) ->
                            var at = -1
                            repeat(occurrence + 1) { at = text.indexOf(surface, at + 1) }
                            NerEntity
                                .newBuilder()
                                .setText(surface)
                                .setCharStart(at)
                                .setCharEnd(at + surface.length)
                                .setLabel(label)
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
    )

    /**
     * hartland's three return reasons that matter here plus three near misses, in both languages, and
     * one DECLARED row per object whose anchor shares a stem with the query. TOKENS-like: a member
     * comes back when it shares a word with the query, scored by the share of its words covered.
     */
    private class ReasonFuzzy(
        private val declaredEcho: Boolean = false,
        private val failMemberLookup: Boolean = false,
    ) : FuzzyClient {
        val batches: MutableList<BatchMatchRequest> = mutableListOf()

        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            batches += request
            val memberOnly = request.spansList.all { it.categoriesList.toSet() == MEMBER_CATEGORIES.toSet() }
            if (failMemberLookup && memberOnly) throw StatusException(Status.UNAVAILABLE.withDescription("down"))
            val builder = BatchMatchResponse.newBuilder()
            for (span in request.spansList) {
                val asked = span.categoriesList.toSet()
                val q = words(span.query)
                val matches = mutableListOf<FuzzyMatch>()
                for ((category, values) in MEMBERS) {
                    if (category !in asked || declaredEcho) continue
                    for (v in values) {
                        val shared = words(v).count { it in q }
                        if (shared > 0) matches += member(v, category, shared.toDouble() / words(v).size)
                    }
                }
                if (declaredEcho && REASON_DESC in asked) matches += declared(span.query, REASON_DESC, span.query)
                for (etype in REGISTRY.entityTypesList) {
                    if (etype.ref !in asked) continue
                    if (etype.anchorsList.none { stem(it, span.query) }) continue
                    matches += declared(span.query, etype.ref, span.query)
                }
                builder.addResults(
                    FuzzyMatchResponse.newBuilder().addAllMatches(matches.sortedByDescending { it.score }),
                )
            }
            return builder.build()
        }

        override suspend fun lookup(request: LookupRequest): LookupResponse = LookupResponse.getDefaultInstance()

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()

        private fun words(s: String) = fold(s).split(' ').filter { it.isNotBlank() }

        private fun stem(
            anchor: String,
            query: String,
        ): Boolean {
            val a = fold(anchor)
            if (a.contains(' ') && fold(query) == a) return true
            return fold(query).split(' ').any { q ->
                val shared = a.commonPrefixWith(q).length
                a == q || (shared >= 4 && shared >= minOf(a.length, q.length) - 2)
            }
        }

        private fun member(
            value: String,
            category: String,
            score: Double,
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId(value)
                .setCandidate(value)
                .setScore(score)
                .setCategory(category)
                .setSource(SourceTag.MEMBER)
                .setMatchMethod("TOKENS")
                .setProvenance(
                    Provenance
                        .newBuilder()
                        .setProducer("lex-matcher")
                        .setMethod("TATRMAN")
                        .setRawScore(score),
                ).build()

        private fun declared(
            query: String,
            ref: String,
            candidate: String,
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId("lex:$ref:${fold(query)}")
                .setCandidate(candidate)
                .setScore(1.0)
                .setCategory(ref)
                .setSource(SourceTag.DECLARED)
                .setTargetRef(ref)
                .setTargetClass(org.tatrman.fuzzy.v1.TargetClass.TARGET_CLASS_MODEL_OBJECT)
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
                ).addCapabilities(
                    Capability
                        .newBuilder()
                        .setLanguage(parse.language)
                        .setOp(NlpOp.NER)
                        .setEngine("stanza"),
                ).build()
    }

    private companion object {
        const val REASON = "er.entity.reason"
        const val REASON_DESC = "er.entity.reason.reason_desc"
        const val WAREHOUSE = "er.entity.warehouse"
        const val WAREHOUSE_NAME = "er.entity.warehouse.warehouse_name"
        const val CATALOG_RETURNS = "er.entity.catalog_returns"

        val MEMBER_CATEGORIES = listOf(REASON_DESC, WAREHOUSE_NAME)

        /** hartland's member values, as the two worlds' databases hold them (a subset). */
        val MEMBERS: Map<String, List<String>> =
            mapOf(
                REASON_DESC to
                    listOf(
                        "Nedorazilo včas",
                        "Přestalo fungovat",
                        "Výměna dárku",
                        "Did not get it on time",
                        "Did not like the color",
                        "Parts missing",
                    ),
                WAREHOUSE_NAME to listOf("Brno DC", "Praha DC"),
            )

        /** The archive channel's shape: objects with anchors, member vocabularies flagged and owned. */
        val REGISTRY: Registry =
            Registry
                .newBuilder()
                .addEntityTypes(etype(REASON, "entity", "důvod reklamace", "return reason"))
                .addEntityTypes(etype(REASON_DESC, "attribute").setOwnerRef(REASON).setMemberVocabulary(true))
                .addEntityTypes(etype(WAREHOUSE, "entity", "distribuční centrum", "distribution center"))
                .addEntityTypes(etype(WAREHOUSE_NAME, "attribute").setOwnerRef(WAREHOUSE).setMemberVocabulary(true))
                .addEntityTypes(
                    etype(CATALOG_RETURNS, "entity_with_measures", "reklamace z tržiště", "marketplace returns"),
                ).addLocales("cs")
                .addLocales("en")
                .setSnapshotHash("member-phrases")
                .build()

        fun etype(
            ref: String,
            kind: String,
            vararg anchors: String,
        ): EntityType.Builder =
            EntityType
                .newBuilder()
                .setRef(ref)
                .addCategories(ref)
                .addAllAnchors(anchors.toList())
                .setObjectKind(kind)

        fun fold(v: String): String =
            java.text.Normalizer
                .normalize(v.lowercase(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")

        /** hartland's Stanza tree, 2026-10-10. */
        val Q34_CS =
            Question(
                "Které distribuční centrum mělo ve 2. pololetí 2025 nejvyšší podíl reklamací s důvodem Nedorazilo včas?",
                "cs",
                Tok("Které", "který", "DET", 3, "det"),
                Tok("distribuční", "distribuční", "ADJ", 3, "amod"),
                Tok("centrum", "centrum", "NOUN", 4, "nsubj"),
                Tok("mělo", "mít", "VERB", 0, "root"),
                Tok("ve", "v", "ADP", 8, "case"),
                Tok("2", "2", "NUM", 8, "nummod"),
                Tok(".", ".", "PUNCT", 6, "punct"),
                Tok("pololetí", "pololetí", "NOUN", 4, "obl"),
                Tok("2025", "2025", "NUM", 8, "nummod"),
                Tok("nejvyšší", "vysoký", "ADJ", 11, "amod"),
                Tok("podíl", "podíl", "NOUN", 4, "obj"),
                Tok("reklamací", "reklamace", "NOUN", 11, "nmod"),
                Tok("s", "s", "ADP", 14, "case"),
                Tok("důvodem", "důvod", "NOUN", 15, "obl"),
                Tok("Nedorazilo", "dorazit", "VERB", 4, "conj"),
                Tok("včas", "včas", "ADV", 15, "advmod"),
                Tok("?", "?", "PUNCT", 4, "punct"),
                entities = listOf(Triple("2025", "DATE", 0)),
            )

        /** hartland's Stanza tree, 2026-10-10: the label is parsed as an adverbial clause. */
        val Q34_EN =
            Question(
                "Which distribution center had the highest share of returns with reason Did not get it on time in 2025?",
                "en",
                Tok("Which", "which", "DET", 3, "det"),
                Tok("distribution", "distribution", "NOUN", 3, "compound"),
                Tok("center", "center", "NOUN", 4, "nsubj"),
                Tok("had", "have", "VERB", 0, "root"),
                Tok("the", "the", "DET", 7, "det"),
                Tok("highest", "high", "ADJ", 7, "amod"),
                Tok("share", "share", "NOUN", 4, "obj"),
                Tok("of", "of", "ADP", 9, "case"),
                Tok("returns", "return", "NOUN", 7, "nmod"),
                Tok("with", "with", "ADP", 14, "mark"),
                Tok("reason", "reason", "NOUN", 14, "obl"),
                Tok("Did", "do", "AUX", 14, "aux"),
                Tok("not", "not", "PART", 14, "advmod"),
                Tok("get", "get", "VERB", 4, "advcl"),
                Tok("it", "it", "PRON", 14, "obj"),
                Tok("on", "on", "ADP", 17, "case"),
                Tok("time", "time", "NOUN", 14, "obl"),
                Tok("in", "in", "ADP", 19, "case"),
                Tok("2025", "2025", "NUM", 14, "obl"),
                Tok("?", "?", "PUNCT", 4, "punct"),
                entities = listOf(Triple("2025", "DATE", 0)),
            )

        val NEDORAZILO_FOLDED =
            Question(
                "Kolik reklamací s duvodem nedorazilo vcas bylo v roce 2025?",
                "cs",
                Tok("Kolik", "kolik", "DET", 2, "det:numgov"),
                Tok("reklamací", "reklamace", "NOUN", 5, "nsubj"),
                Tok("s", "s", "ADP", 4, "case"),
                Tok("duvodem", "duvod", "NOUN", 2, "nmod"),
                Tok("nedorazilo", "dorazit", "VERB", 0, "root"),
                Tok("vcas", "vcas", "ADV", 5, "advmod"),
                Tok("bylo", "být", "AUX", 5, "cop"),
                Tok("v", "v", "ADP", 9, "case"),
                Tok("roce", "rok", "NOUN", 5, "obl"),
                Tok("2025", "2025", "NUM", 9, "nummod"),
                Tok("?", "?", "PUNCT", 5, "punct"),
                entities = listOf(Triple("2025", "DATE", 0)),
            )

        val VYMENA_DARKU =
            Question(
                "Kolik reklamací s důvodem Výměna dárku?",
                "cs",
                Tok("Kolik", "kolik", "DET", 2, "det:numgov"),
                Tok("reklamací", "reklamace", "NOUN", 0, "root"),
                Tok("s", "s", "ADP", 4, "case"),
                Tok("důvodem", "důvod", "NOUN", 2, "nmod"),
                Tok("Výměna", "výměna", "NOUN", 4, "nmod"),
                Tok("dárku", "dárek", "NOUN", 5, "nmod"),
                Tok("?", "?", "PUNCT", 2, "punct"),
            )

        val NEDORAZILO_ZBOZI =
            Question(
                "Kolik reklamací s důvodem nedorazilo zboží?",
                "cs",
                Tok("Kolik", "kolik", "DET", 2, "det:numgov"),
                Tok("reklamací", "reklamace", "NOUN", 5, "nsubj"),
                Tok("s", "s", "ADP", 4, "case"),
                Tok("důvodem", "důvod", "NOUN", 2, "nmod"),
                Tok("nedorazilo", "dorazit", "VERB", 0, "root"),
                Tok("zboží", "zboží", "NOUN", 5, "nsubj"),
                Tok("?", "?", "PUNCT", 5, "punct"),
            )

        /** A proper-noun value is path (b)'s, never a slot. */
        val PRAHA_DC =
            Question(
                "Tržby z tržiště pro centrum Praha DC",
                "cs",
                Tok("Tržby", "tržba", "NOUN", 0, "root"),
                Tok("z", "z", "ADP", 3, "case"),
                Tok("tržiště", "tržiště", "NOUN", 1, "nmod"),
                Tok("pro", "pro", "ADP", 5, "case"),
                Tok("centrum", "centrum", "NOUN", 1, "nmod"),
                Tok("Praha", "Praha", "PROPN", 5, "nmod"),
                Tok("DC", "DC", "PROPN", 6, "flat"),
            ).parse

        val ONE_WORD_THEN_AND =
            Question(
                "reklamace s důvodem Nedorazilo a jiné",
                "cs",
                Tok("reklamace", "reklamace", "NOUN", 0, "root"),
                Tok("s", "s", "ADP", 3, "case"),
                Tok("důvodem", "důvod", "NOUN", 1, "nmod"),
                Tok("Nedorazilo", "dorazit", "VERB", 1, "acl"),
                Tok("a", "a", "CCONJ", 6, "cc"),
                Tok("jiné", "jiný", "ADJ", 4, "conj"),
            )

        fun resolve(
            question: Question,
            fuzzy: ReasonFuzzy,
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
                    .setConversationId("member-phrases")
                    .setFresh(FreshQuestion.newBuilder().setText(question.text).setLocale(question.lang))
                    .setRegistry(REGISTRY)
                    .build()
            return runBlocking { pipeline.resolve(request) }
        }
    }
}
