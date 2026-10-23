// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.concept

import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.fuzzy.v1.TargetClass
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.model.ResolverRegistry
import org.tatrman.resolver.model.conceptFacts
import org.tatrman.resolver.model.memberEntityByCategory
import org.tatrman.resolver.pipeline.Binder
import org.tatrman.resolver.pipeline.ConceptLifts
import org.tatrman.resolver.v1.EvidenceClass

/**
 * CN-P5 S5.1 T5 (cn contracts §4 `concept.search:v1`; A-CN-10, A-CN-5/7) — the concepts a span of text can name, as a
 * thin view over what already exists: ONE lex-matcher lookup and the resolver's own lift. No new index.
 *
 *  1. the span is looked up across the whole declared layer and the member indexes (a concept term is a declared row,
 *     A-CN-10; an element or a member may lift to a concept);
 *  2. the Binder ranks the rows ([Binder.ranked]) — RV-14's evidence classes (a WEAK row is not evidence: it names
 *     nothing), and within each class the resolver's own §1.5 steps, its rule and not a copy of it: a phase yields to
 *     its bearer, then the default sense, then the authored priority;
 *  4. every surviving row reads as its concepts ([ConceptLifts.of] — a term as the concept it names, an element or a
 *     member through `liftsTo`, never onto a phase), merged by concept with every source kept.
 *
 * **Concepts only.** A member or an element is never returned; it is a source of the concept it lifts to. The filter
 * sits here, at the door, and never in the loader — the matcher keeps serving members to everyone else.
 */
class ConceptSearch(
    private val fuzzy: FuzzyClient,
    private val registry: suspend () -> ResolverRegistry,
) {
    /** One concept a span can name. [score] is the best row's, comparable only within [evidenceClass] (RV-14). */
    data class Hit(
        val qname: String,
        val stereotype: String,
        val via: String,
        val sources: List<String>,
        val note: String,
        val evidenceClass: EvidenceClass,
        val score: Double,
    )

    /** A search's answer, with the snapshot it was read from (the registry's: the lift is the vocabulary's). */
    data class Answer(
        val snapshotHash: String,
        val hits: List<Hit>,
    )

    suspend fun search(
        text: String,
        maxCandidates: Int = DEFAULT_MAX,
    ): Answer {
        val reg = registry()
        val concepts = reg.entityTypes.conceptFacts()
        if (concepts.isEmpty || text.isBlank()) return Answer(reg.snapshotHash, emptyList())

        val rows =
            fuzzy
                .lookup(
                    LookupRequest
                        .newBuilder()
                        .setTerm(text)
                        .setMaxCandidates(maxCandidates.coerceIn(1, MAX))
                        .build(),
                ).candidatesList
                .filter { it.source == SourceTag.MEMBER || it.targetClass == TargetClass.TARGET_CLASS_MODEL_OBJECT }

        val kept = Binder.ranked(rows, reg.thresholds, concepts)

        val vocabularies = reg.entityTypes.flatMap { et -> et.categories.map { it to et.ref } }.toMap()
        val owners = reg.entityTypes.memberEntityByCategory()
        val hits = LinkedHashMap<String, Hit>()
        for (c in kept) {
            val m = c.match
            val isMember = m.source == SourceTag.MEMBER
            val category = if (isMember) vocabularies[m.category] ?: m.category else ""
            val owner = if (isMember) owners[m.category].orEmpty() else ""
            for (lifted in ConceptLifts.of(m, category, owner, emptyList(), concepts)) {
                val seen = hits[lifted.qname]
                hits[lifted.qname] =
                    if (seen == null) {
                        lifted.toHit(c)
                    } else {
                        seen.copy(sources = (seen.sources + lifted.provenance).distinct())
                    }
            }
        }
        return Answer(reg.snapshotHash, hits.values.toList())
    }

    private fun ConceptLifts.LiftedConcept.toHit(c: Binder.ClassedMatch): Hit =
        Hit(qname, stereotype, via, provenance, note, c.evidenceClass, c.match.score)

    companion object {
        const val DEFAULT_MAX = 50

        /** lex-matcher's own cap on a lookup (`LookupRequest.max_candidates`). */
        const val MAX = 200
    }
}
