// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.v1.UniversalEntityType
import org.tatrman.text.Normalization.fold

/**
 * UD-P3 — a place or a person name that nothing in the sentence governs, offered as a member of the
 * vocabularies whose own values the NER finds such names in.
 *
 * *„Kolik týdnů v roce 2025 mělo **Brno DC** nulové zásoby?“* NameTag types „Brno DC“ a place, and
 * no anchor governs it — `mělo` is a verb — so the governed dual reading (`SpanProposal`, UD §3.1)
 * never forms, and the warehouse named exactly that is never looked up: the question gets a G3 and the
 * fast path falls through. UD declined the obvious fix ("ask every vocabulary about every place",
 * #118 option 2) because a lone city then finds anything that shares a word with it (`Stores in
 * Paris` → the return reason "Parts missing"), and a bare place has no anchor whose reach could
 * filter that (A-UD-7).
 *
 * The gate here is the DATA. lex-matcher runs the same NER over each member vocabulary when it loads
 * and reports, per vocabulary, which kinds of names its values carry ([NamedCategories], from
 * `GetStatus`). A place is offered only to the vocabularies whose values the NER calls places — the
 * return reasons hold none, so `Paris` never reaches them; the warehouse names do, so `Brno DC` does.
 *
 * **Stage 1: the whole name, verbatim.** A member row speaks for this reading only when its value IS
 * the span, modulo case, diacritics and spacing ([admitted]) — the rule `MemberPhrases` confirms a
 * label by. A TOKENS or TYPOS hit never does, so `Dallas` does not become the warehouse "Dallas DC".
 * Part of a name (`v Brně` for "Brno DC") is Stage 2, not built.
 *
 * **What it does not touch.** A governed place keeps its governed reading: nothing is proposed over an
 * entity a governed dual reading already reads. No profile, or no named vocabulary for the entity's
 * type, proposes nothing at all, so the resolve is byte-identical to the one before UD-P3 (I-11, I-12).
 * The reading is decided by the same seam as the governed one (`UniversalSeam`): it supersedes the
 * place when a member spoke, and is withdrawn when none did.
 */
object NamedMembers {
    /**
     * The NAMED_MEMBER candidates of [parse]: one per universal entity of a dual-reading type that
     * no governed dual reading in [proposed] reads, scoped to the member vocabularies [named] lists
     * for its type. Extent: the entity's tokens plus the words the parse continues its name with
     * (`Praha` + `DC`, ✅UD-9), stopping at a declared mention or a quoted literal.
     */
    fun candidates(
        parse: AnalyzeResponse,
        proposed: List<DomainSpanCandidate>,
        named: NamedCategories,
        entityTypes: List<ResolverEntityType>,
        literals: Literals = Literals.NONE,
    ): List<DomainSpanCandidate> {
        if (named.isEmpty) return emptyList()
        val tokens = parse.tokensList
        if (tokens.isEmpty()) return emptyList()
        // The vocabularies a profile can name: member vocabularies, in the registry's own spelling.
        // A category the profile reports and the registry does not declare is never asked.
        val vocabularies =
            entityTypes
                .filter { it.memberVocabulary }
                .flatMap { et -> et.categories.map { it to et.ref } }
                .distinctBy { it.first.lowercase() }
        if (vocabularies.isEmpty()) return emptyList()

        val read = proposed.mapNotNullTo(HashSet()) { it.dualReadingOf }
        val universal = SpanProposal.universalCharRanges(parse.entitiesList)
        // A declared mention is its own model object and never part of a name; nor is a literal.
        val excluded =
            tokens.indices
                .filter { i ->
                    i in literals.tokens ||
                        proposed.any {
                            it.origin == DomainSpanCandidate.Origin.ANCHOR_PHRASE &&
                                it.start <= tokens[i].charStart &&
                                tokens[i].charEnd <= it.end
                        }
                }.toHashSet()

        val out = mutableListOf<DomainSpanCandidate>()
        for (e in parse.entitiesList) {
            val type = UniversalClassifier.dualReadingType(e.label, e.normalizedValue, e.text) ?: continue
            val entity = e.charStart to e.charEnd
            if (entity in read || out.any { it.dualReadingOf == entity }) continue
            if (literals.overlaps(e.charStart, e.charEnd)) continue
            val scope = named.of(type)
            val asked = vocabularies.filter { it.first.lowercase() in scope }
            // I-11 — no vocabulary holds this kind of name: nothing is proposed, nothing is asked.
            if (asked.isEmpty()) continue
            val run =
                SpanProposal.withNameTail(
                    SpanProposal.tokensWithin(e, tokens).filterNot { it in excluded },
                    tokens,
                    universal,
                    excluded,
                )
            if (run.isEmpty()) continue
            val head = SpanProposal.syntacticHead(run, tokens)
            out +=
                DomainSpanCandidate(
                    text = SpanProposal.surface(run, tokens),
                    start = run.minOf { tokens[it].charStart },
                    end = run.maxOf { tokens[it].charEnd },
                    gatedEntityRefs = asked.map { it.second }.distinct(),
                    categories = asked.map { it.first },
                    anchored = false,
                    origin = DomainSpanCandidate.Origin.NAMED_MEMBER,
                    headToken = head,
                    lemma = tokens[head].lemma.ifBlank { tokens[head].text },
                    dualReadingOf = entity,
                )
        }
        return out
    }

    /**
     * I-13 — the rows that may decide a NAMED_MEMBER reading: MEMBER rows of the vocabularies it was
     * scoped to whose value is the span itself, verbatim modulo case, diacritics and spacing. Nothing
     * else — not a TOKENS or TYPOS member hit, not a declared row — so a reading that found only those
     * has nothing to bind and is withdrawn.
     *
     * The category is checked HERE as well as asked for, as in `MemberPhrases`: a matcher that ignored
     * the filter must not be able to turn a place into some other vocabulary's member.
     */
    fun admitted(
        matches: List<FuzzyMatch>,
        candidate: DomainSpanCandidate,
    ): List<FuzzyMatch> {
        val scope = candidate.categories.mapTo(HashSet()) { it.lowercase() }
        val name = key(candidate.text)
        return matches.filter {
            it.source == SourceTag.MEMBER && it.category.lowercase() in scope && key(it.candidate) == name
        }
    }

    /**
     * The gated spans and leftover mentions a NAMED_MEMBER that spoke has taken the words of. Its
     * extent can be wider than the NER's (`Praha` typed a place, `DC` its tail), and a word of the tail
     * may meanwhile have been proposed or left over on its own (`DC` as a mention): once the member
     * spoke, the name is one value, and a second finding over its characters would be a second answer
     * to one question. When it did not speak it is withdrawn, and these stay exactly as they were.
     */
    fun claimedBy(
        spoken: List<GatedSpan>,
        span: DomainSpanCandidate,
    ): Boolean =
        span.origin != DomainSpanCandidate.Origin.NAMED_MEMBER &&
            spoken.any { it.candidate.start < span.end && span.start < it.candidate.end }

    /** Verbatim modulo case, diacritics and spacing — a stored `char(n)` value's padding included. */
    private fun key(s: String): String = fold(s.trim()).replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
}

/**
 * UD-P3 — per dual-reading universal type, the member categories (lower-cased) whose values the NER
 * finds such names in, read from lex-matcher's `GetStatus` entity profiles.
 *
 * A category counts for a type when at least one of its values carries an entity [UniversalClassifier]
 * gives that type (⚑UD-13 (a)): the profile reports the NER's own kinds, and which of them are places
 * is the classifier's rule, the one the question's entities are typed by — so the two sides cannot
 * disagree about what a place is.
 */
data class NamedCategories(
    private val byType: Map<UniversalEntityType, Set<String>>,
) {
    val isEmpty: Boolean get() = byType.values.all { it.isEmpty() }

    fun of(type: UniversalEntityType): Set<String> = byType[type].orEmpty()

    companion object {
        val NONE = NamedCategories(emptyMap())

        fun fromStatus(status: FuzzyStatusResponse): NamedCategories {
            val byType = HashMap<UniversalEntityType, MutableSet<String>>()
            for (category in status.categoriesList) {
                if (!category.hasEntityProfile()) continue
                for (count in category.entityProfile.entitiesList) {
                    if (count.members < 1) continue
                    val type = UniversalClassifier.dualReadingType(count.label, count.code) ?: continue
                    byType.getOrPut(type) { HashSet() } += category.category.lowercase()
                }
            }
            return NamedCategories(byType)
        }
    }
}

/**
 * UD-P3 — the [NamedCategories] the pipeline reads, refreshed from lex-matcher's `GetStatus` at most
 * once per [ttlMs]. Profiles move only when a vocabulary loads, so a minute-old answer is as good as
 * a fresh one, and a resolve pays for the status call once a minute rather than every time.
 *
 * Fail-open in the "as before" direction: a status that cannot be read within [timeoutMs] keeps the
 * last answer, and before the first one there is none ([NamedCategories.NONE]) — no NAMED_MEMBER
 * candidate is proposed (I-12).
 */
class NamedCategorySource(
    private val fuzzy: FuzzyClient,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(NamedCategorySource::class.java)

    @Volatile
    private var cached: NamedCategories = NamedCategories.NONE

    @Volatile
    private var readAt: Long? = null

    suspend fun current(): NamedCategories {
        val now = clock()
        val last = readAt
        if (last != null && now - last < ttlMs) return cached
        // Claimed before the call, so concurrent resolves do not all ask at once.
        readAt = now
        val status =
            try {
                withTimeoutOrNull(timeoutMs) { fuzzy.getStatus() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("named members: lex-matcher status unreadable ({}) — keeping the last profile", e.message)
                null
            }
        if (status != null) {
            val next = NamedCategories.fromStatus(status)
            if (next != cached) {
                log.info(
                    "named members: {}",
                    UniversalClassifier.DUAL_READING_TYPES.joinToString(" ") { t ->
                        "${t.name}=${next.of(t).sorted()}"
                    },
                )
            }
            cached = next
        }
        return cached
    }

    companion object {
        const val DEFAULT_TTL_MS: Long = 60_000
        const val DEFAULT_TIMEOUT_MS: Long = 1_000
    }
}
