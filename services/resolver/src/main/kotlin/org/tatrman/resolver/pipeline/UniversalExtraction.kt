// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.resolver.v1.UniversalEntityType

/** A universal (engine-typed) binding: person/geo/time/money/number. */
data class UniversalBinding(
    val start: Int,
    val end: Int,
    val text: String,
    val entityType: UniversalEntityType,
    val rawText: String,
    val normalizedValue: String,
    val sourceEngine: String,
)

/**
 * extractUniversal (RG-P5.S1.T5) — turn the parse's NER entities into universal
 * bindings, keeping ONLY the universal classes (person/geo/time/money/number).
 * Institution/object spans are left for the domain path (they are declared
 * values, gated by fuzzy — spike §1). Classification is delegated to
 * [UniversalClassifier] so it can never drift from [SpanProposal]'s exclusion set.
 */
object UniversalExtraction {
    fun extractUniversal(parse: AnalyzeResponse): List<UniversalBinding> =
        parse.entitiesList.mapNotNull { e ->
            val type: UniversalEntityType =
                UniversalClassifier.classify(e.label, e.normalizedValue, e.text) ?: return@mapNotNull null
            UniversalBinding(
                start = e.charStart,
                end = e.charEnd,
                text = e.text,
                entityType = type,
                rawText = e.text,
                normalizedValue = e.normalizedValue,
                sourceEngine = e.sourceEngine,
            )
        }
}
