// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.resolver.pipeline.UniversalExtraction
import org.tatrman.resolver.v1.UniversalEntityType

/**
 * `UniversalExtraction` types exactly what `UniversalClassifier` calls universal — so a value the
 * classifier hands to the domain path is never ALSO extracted as a universal binding.
 */
class UniversalExtractionTest :
    StringSpec({

        "a coarse MISC with no code is left to the domain path; a coded number and a place are extracted (UD-P0)" {
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .addEntities(ner("Orion", 0, 5, "MISC", ""))
                    .addEntities(ner("Vega", 6, 10, "MISC", "cnec:"))
                    .addEntities(ner("501001", 11, 17, "MISC", "cnec:no"))
                    .addEntities(ner("Nashville", 18, 27, "GPE", ""))
                    .build()

            UniversalExtraction.extractUniversal(parse).map { it.text to it.entityType } shouldContainExactly
                listOf(
                    "501001" to UniversalEntityType.MISC,
                    "Nashville" to UniversalEntityType.LOCATION,
                )
        }

        "a coarse MISC that is a number is extracted as universal MISC, as before UD-P0 (review-108 F2)" {
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .addEntities(ner("501001", 0, 6, "MISC", ""))
                    .addEntities(ner("Orion", 7, 12, "MISC", ""))
                    .build()

            UniversalExtraction.extractUniversal(parse).map { it.text to it.entityType } shouldContainExactly
                listOf("501001" to UniversalEntityType.MISC)
        }
    }) {
    private companion object {
        fun ner(
            text: String,
            start: Int,
            end: Int,
            label: String,
            normalizedValue: String,
        ): NerEntity =
            NerEntity
                .newBuilder()
                .setText(text)
                .setCharStart(start)
                .setCharEnd(end)
                .setLabel(label)
                .setNormalizedValue(normalizedValue)
                .setSourceEngine("test")
                .build()
    }
}
