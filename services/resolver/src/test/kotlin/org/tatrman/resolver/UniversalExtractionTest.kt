// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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

        // ---- one date, not its parts (NameTag types „v říjnu 2025“ as tm + ty, „v květnu 2025“ as one T) ----

        "a month and a year typed apart are joined into the date the user wrote" {
            val q = "Které položky tam byly v říjnu 2025 vyprodané?"
            val dates = extract(q, part(q, "říjnu", "cnec:tm"), part(q, "2025", "cnec:ty"))
            dates.map { it.text to it.normalizedValue } shouldContainExactly listOf("říjnu 2025" to "cnec:T|B-tm")
            dates.single().entityType shouldBe UniversalEntityType.DATE
            dates.single().start shouldBe q.indexOf("říjnu")
            dates.single().end shouldBe q.indexOf("2025") + 4
        }

        "every Czech month form NameTag splits is joined with its year" {
            for (m in listOf(
                "lednu",
                "února",
                "únoru",
                "září",
                "října",
                "říjnu",
                "říjen",
                "listopadu",
                "prosinci",
                "leden",
            )) {
                val q = "Tržby v $m 2025"
                extract(q, part(q, m, "cnec:tm"), part(q, "2025", "cnec:ty")).map { it.text } shouldContainExactly
                    listOf("$m 2025")
            }
        }

        "a day, a month and a year typed apart are one date" {
            val q = "Tržby 15. října 2025"
            extract(q, part(q, "15.", "cnec:td"), part(q, "října", "cnec:tm"), part(q, "2025", "cnec:ty"))
                .map { it.text to it.normalizedValue } shouldContainExactly listOf("15. října 2025" to "cnec:T|B-td")
        }

        "two years are two dates, not one" {
            val q = "Tržby v roce 2024 a 2025"
            extract(q, part(q, "2024", "cnec:ty"), part(q, "2025", "cnec:ty")).map { it.text } shouldContainExactly
                listOf("2024", "2025")
        }

        "two adjacent years with nothing between them are still two dates (a year does not follow a year)" {
            val q = "Tržby 2024 2025"
            extract(q, part(q, "2024", "cnec:ty"), part(q, "2025", "cnec:ty")).map { it.text } shouldContainExactly
                listOf("2024", "2025")
        }

        "a coordination keeps its months apart — „říjnu a listopadu 2025“ is not this join's to decide" {
            val q = "Tržby v říjnu a listopadu 2025"
            extract(q, part(q, "říjnu", "cnec:tm"), part(q, "listopadu 2025", "cnec:T|B-tm"))
                .map { it.text } shouldContainExactly listOf("říjnu", "listopadu 2025")
        }

        "a year before a month is not joined (calendar order only)" {
            val q = "Tržby 2025 říjen"
            extract(q, part(q, "2025", "cnec:ty"), part(q, "říjen", "cnec:tm")).map { it.text } shouldContainExactly
                listOf("2025", "říjen")
        }

        "anything but whitespace between the parts keeps them apart" {
            val q = "Tržby v říjnu, 2025"
            extract(q, part(q, "říjnu", "cnec:tm"), part(q, "2025", "cnec:ty")).map { it.text } shouldContainExactly
                listOf("říjnu", "2025")
        }

        "a date NameTag already joined passes through unchanged" {
            val q = "Tržby v květnu 2025"
            extract(q, part(q, "květnu 2025", "cnec:T|B-tm")).map { it.text to it.normalizedValue } shouldContainExactly
                listOf("květnu 2025" to "cnec:T|B-tm")
        }

        "without the question text nothing is joined (the parse's entities as they came)" {
            val q = "Tržby v říjnu 2025"
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .addEntities(
                        part(q, "říjnu", "cnec:tm"),
                    ).addEntities(part(q, "2025", "cnec:ty"))
                    .build()
            UniversalExtraction.extractUniversal(parse).map { it.text } shouldContainExactly listOf("říjnu", "2025")
        }

        "offsets that do not match the question leave the parts alone" {
            val q = "Tržby v říjnu 2025"
            val wrong = ner("říjnu", 0, 5, "DATE", "cnec:tm") // "Tržby" sits at 0..5, not "říjnu"
            extract(q, wrong, part(q, "2025", "cnec:ty")).map { it.text } shouldContainExactly listOf("říjnu", "2025")
        }
    }) {
    private companion object {
        /** A DATE entity for [text]'s first occurrence in [q], with the given CNEC code. */
        fun part(
            q: String,
            text: String,
            code: String,
        ): NerEntity {
            val at = q.indexOf(text)
            check(at >= 0) { "'$text' is not in '$q'" }
            return ner(text, at, at + text.length, "DATE", code)
        }

        fun extract(
            q: String,
            vararg entities: NerEntity,
        ) = UniversalExtraction.extractUniversal(
            AnalyzeResponse.newBuilder().addAllEntities(entities.toList()).build(),
            q,
        )

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
