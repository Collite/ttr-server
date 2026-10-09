// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.fuzzy.v1.SpanQuery
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.text.Normalization.fold

/**
 * ✅MH-D5, the matcher half — **a mention is also looked up by its dictionary form.**
 *
 * The broad pass asks the matcher about the words the user WROTE (`DomainSpanCandidate.text`). The
 * matcher's TOKENS and TYPOS paths already compare on a lemma axis of their own, but an authored
 * `EXACT` term is held to its exact form (RV-32), and that is where an inflecting language falls
 * through: the estate declares an object by its citation form (`produkt`, `portfolio`, `klient` —
 * every entity label is one), and the user writes `produkty`, `portfolií`, `našich klientů`. Each of
 * those met no row of its own object and either bound the next-best row in reach (`produkty` → the
 * English alias *product category* by token + typo, so a prefix filter on product names ran on the
 * category column) or none at all (`G1_UNBOUND` on `portfolií`).
 *
 * The fix stays inside B-T1's one round trip: for a mention whose dictionary form differs from what
 * was written, ONE more `SpanQuery` asks the same categories about that form, as a trailing slot of
 * the same `BatchMatch`; [merge] folds its rows back onto the mention's own slot before anything
 * reads the response, and drops the trailing slots, so every positional reader downstream sees the
 * batch shape it always did.
 *
 * **What is asked — one content word, never a phrase.**
 *  - Only [DomainSpanCandidate.Origin.ANCHOR_PHRASE] spans: the mention layer is where an object's
 *    name is matched. Values are matched against member data, where the matcher already lemmatises
 *    the query itself.
 *  - The span's tokens minus its determiners (`det*`). An anchor phrase keeps an article, a
 *    demonstrative or a possessive inside it on purpose (it says WHICH one), but no estate authors
 *    *našich klientů* as a term: `klient` is the term.
 *  - Exactly ONE content token left ⇒ its lemma is the form asked. Several ⇒ nothing is asked. A
 *    Czech multi-word term's citation form is not the token-wise lemma of what was written: an
 *    adjective agrees with its head's gender (*nulové zásoby* → *nulová zásoba*, while the lemma of
 *    *nulové* is *nulový*), and a governed dependent keeps its case (*hodnota objednávky*, never
 *    *hodnota objednávka*). Lemmatising the head alone breaks the agreement just the same. A phrase
 *    is matched as written (CsGrainPhraseTest), and an estate declares the phrase it means.
 *  - Nothing when the form folds to the text already asked, or when the parse has no lemma.
 *
 * **What a dictionary-form row may do — less than the same row met as written.**
 *  - Only DECLARED and LEARNED rows (never MEMBER — see above), only rows whose authored method is
 *    `EXACT` or `TYPOS(n)` (the TOKENS path already scored the written query on its own lemma axis,
 *    and asking again would count it twice), and only rows with NO declared matching profile
 *    (RV-44): an estate that wrote a profile has said which normalized forms count for its term,
 *    `lemma` among them if it wants it, and the matcher honours that already.
 *  - A row the written form already reached is dropped (one identity, [Binder.identityKey]); the
 *    written reading speaks.
 *  - Its score drops by [PENALTY], wider than the tie band, so a row the written form reached with
 *    an EQUAL score wins outright instead of tying; a weaker written row still loses, which is the
 *    point (*product category* 0.71 against `produkt` 1.0).
 *  - It is stamped [NORM_LEMMA] on `provenance.norm` with no `provenance.algorithm` — the shape no
 *    matcher row has (norm and algorithm travel together on a profile row) — and
 *    `EvidenceClasses` reads that as at most `DECLARED_ALIAS`: the estate's term met through
 *    morphology, as a `TYPOS(1)` hit on a profile-less declared row already is. Never `EXACT`, which
 *    is a claim about the written form. `provenance.method` gains [ALGORITHM_SUFFIX], so the
 *    binding's producer says how it was reached.
 *
 * English is unaffected in practice: the lemma of a plural is usually declared beside it (an entity
 * carries `labelPlural`), so the dictionary form either folds to what was written or meets a row the
 * written form already reached.
 */
object LemmaLookup {
    /** Below an equal written-form score by more than `ResolverThresholds.ambiguityGap` (0.05). */
    const val PENALTY: Double = 0.1

    /** The `provenance.norm` a dictionary-form row carries — RV-44's own name for the stratum. */
    const val NORM_LEMMA: String = "lemma"

    /** Appended to `provenance.method`, which becomes the binding's `producer.algorithm`. */
    const val ALGORITHM_SUFFIX: String = "+lemma"

    private val TYPOS = Regex("""^TYPOS\(\d+\)$""", RegexOption.IGNORE_CASE)

    /** One extra slot: the mention at [candidateIndex], asked about as [form]. */
    data class Query(
        val candidateIndex: Int,
        val form: String,
    )

    /** The dictionary-form questions worth asking, one per eligible mention, in candidate order. */
    fun plan(
        candidates: List<DomainSpanCandidate>,
        parse: AnalyzeResponse,
    ): List<Query> {
        val tokens = parse.tokensList
        if (tokens.isEmpty()) return emptyList()
        return candidates.mapIndexedNotNull { i, c ->
            if (c.origin != DomainSpanCandidate.Origin.ANCHOR_PHRASE) return@mapIndexedNotNull null
            formOf(c, tokens)?.let { Query(i, it) }
        }
    }

    /** The trailing `SpanQuery`s for [plan] — the mention's own categories, the same limit. */
    fun queries(
        plan: List<Query>,
        candidates: List<DomainSpanCandidate>,
        perSpanLimit: Int,
    ): List<SpanQuery> =
        plan.map { q ->
            SpanQuery
                .newBuilder()
                .setQuery(q.form)
                .addAllCategories(candidates[q.candidateIndex].categories)
                .setLimit(perSpanLimit)
                .build()
        }

    /**
     * [response] with each planned mention's admissible dictionary-form rows appended to its own
     * slot, and the trailing slots — [offset] onwards, the ones [queries] added — removed. With an
     * empty [plan] the response is returned as it came.
     */
    fun merge(
        response: BatchMatchResponse,
        plan: List<Query>,
        offset: Int,
    ): BatchMatchResponse {
        if (plan.isEmpty()) return response
        val results = response.resultsList
        val extra = HashMap<Int, List<FuzzyMatch>>()
        plan.forEachIndexed { k, q ->
            val written = results.getOrNull(q.candidateIndex)?.matchesList.orEmpty()
            val reached = written.map { Binder.identityKey(it) }.toSet()
            val rows =
                results
                    .getOrNull(offset + k)
                    ?.matchesList
                    .orEmpty()
                    .filter { admissible(it) && Binder.identityKey(it) !in reached }
                    .distinctBy { Binder.identityKey(it) }
                    .map(::stamp)
            if (rows.isNotEmpty()) extra[q.candidateIndex] = extra[q.candidateIndex].orEmpty() + rows
        }
        val builder = BatchMatchResponse.newBuilder()
        results.take(offset).forEachIndexed { i, slot ->
            val rows = extra[i]
            builder.addResults(if (rows == null) slot else slot.toBuilder().addAllMatches(rows).build())
        }
        // A slot the matcher left off the end (it answered fewer spans than it was asked) stays absent;
        // only a mention whose own slot exists can carry rows, so nothing positional moves.
        if (results.size < offset) {
            for (i in results.size until offset) builder.addResults(FuzzyMatchResponse.getDefaultInstance())
        }
        return builder.build()
    }

    /** A dictionary-form row, by the marker [stamp] writes — see the class doc. */
    fun isLemmaHit(match: FuzzyMatch): Boolean =
        match.hasProvenance() &&
            match.provenance.hasNorm() &&
            match.provenance.norm == NORM_LEMMA &&
            !match.provenance.hasAlgorithm()

    private fun formOf(
        candidate: DomainSpanCandidate,
        tokens: List<Token>,
    ): String? {
        val content =
            tokens.filter { t ->
                t.charStart >= candidate.start && t.charEnd <= candidate.end && !t.depRelation.startsWith("det")
            }
        val word = content.singleOrNull() ?: return null
        val lemma = word.lemma.trim()
        if (lemma.isEmpty() || lemma.any { it.isWhitespace() }) return null
        return lemma.takeIf { fold(it) != fold(candidate.text) }
    }

    private fun admissible(match: FuzzyMatch): Boolean {
        if (match.source == SourceTag.MEMBER || match.source == SourceTag.UNRECOGNIZED) return false
        if (match.hasProvenance() && (match.provenance.hasNorm() || match.provenance.hasAlgorithm())) return false
        val method = if (match.hasMatchMethod()) match.matchMethod.trim() else return false
        return method.equals("EXACT", ignoreCase = true) || TYPOS.matches(method)
    }

    private fun stamp(match: FuzzyMatch): FuzzyMatch =
        match
            .toBuilder()
            .setScore(match.score - PENALTY)
            .setProvenance(
                match.provenance
                    .toBuilder()
                    .setMethod(match.provenance.method.ifBlank { "TATRMAN" } + ALGORITHM_SUFFIX)
                    .setNorm(NORM_LEMMA)
                    .clearAlgorithm(),
            ).build()
}
