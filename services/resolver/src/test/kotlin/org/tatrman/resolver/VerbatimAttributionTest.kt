// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.pipeline.Literals
import org.tatrman.resolver.pipeline.VerbatimAttribution

/**
 * LP-P1·S2·T4 — §2.1, rule by rule.
 *
 * `VerbatimLatticeTest` drives the whole pipeline and therefore exercises one path through this
 * object per case. These pin the choices themselves: which attribute a shape takes, how far the
 * parse chain may reach, and what happens when there is no parse to reach along.
 */
class VerbatimAttributionTest :
    StringSpec({

        val store =
            ResolverEntityType(
                ref = "er.entity.store",
                categories = listOf("er.entity.store"),
                anchors = listOf("prodejna"),
                nameRef = "er.entity.store.name",
                codeRef = "er.entity.store.code",
            )

        "a name-shaped literal takes the name attribute" {
            VerbatimAttribution.attributeRefOf("Pelex", store) shouldBe "er.entity.store.name"
        }

        "a code-shaped literal takes the code attribute" {
            // §2.1's fallback shape: upper-case/digits/separators, at least one digit. `A-14/B` is
            // the kind of thing an estate uses as a store code and nobody uses as a store name.
            VerbatimAttribution.attributeRefOf("A-14/B", store) shouldBe "er.entity.store.code"
            VerbatimAttribution.attributeRefOf("501001", store) shouldBe "er.entity.store.code"
        }

        "ALL CAPS with no digit is a name, not a code" {
            // The rule that keeps a shouted name out of the code column. `PELEX` is how half the
            // estates in this domain spell a brand.
            VerbatimAttribution.attributeRefOf("PELEX", store) shouldBe "er.entity.store.name"
        }

        "code-shaped, but the entity declares no code column ⇒ the name column" {
            val nameOnly = store.copy(codeRef = "")

            VerbatimAttribution.attributeRefOf("501001", nameOnly) shouldBe "er.entity.store.name"
        }

        "the model's own code_format wins over the shape heuristic" {
            // An estate that declared `code_format` has said what a code looks like HERE. A regex
            // invented in this service would be a second rule about the same question, and the two
            // would eventually disagree — the estate's spelling is the one that decides.
            val periodic = store.copy(codeFormat = "^[0-9]{4}Q[1-4]$")

            VerbatimAttribution.attributeRefOf("2026Q1", periodic) shouldBe "er.entity.store.code"
            // Code-SHAPED by the fallback heuristic, but not by this estate's format.
            VerbatimAttribution.attributeRefOf("A-14/B", periodic) shouldBe "er.entity.store.name"
        }

        "an entity that declares neither ⇒ null, and the value is headless" {
            val silent = store.copy(nameRef = "", codeRef = "")

            VerbatimAttribution.attributeRefOf("Pelex", silent).shouldBeNull()
        }

        "a malformed code_format does not take the resolve down with it" {
            // The estate's regex arrives as authored text through the archive. A bad one is a
            // model defect, and the honest response to it here is to fall back to the shape rule,
            // not to fail a question that has nothing to do with codes.
            val broken = store.copy(codeFormat = "^[0-9")

            VerbatimAttribution.attributeRefOf("Pelex", broken) shouldBe "er.entity.store.name"
        }

        "the head is found up the dep chain, at most three hops" {
            // `zákazník ... "Valmy"` with the literal three hops under the mention: at the limit.
            val text = "zákazník který se jmenuje \"Valmy\""
            val parse =
                parseOf(
                    token("zákazník", 0, 8, 0), // 0 root — the head mention
                    token("který", 9, 14, 1),
                    token("se", 15, 17, 4),
                    token("jmenuje", 18, 25, 2),
                    token("\"", 26, 27, 4),
                    token("Valmy", 27, 32, 4), // → jmenuje → který → zákazník = 3 hops
                    token("\"", 32, 33, 4),
                )
            val literal = Literals.of(text, parse).spans.single()

            val attributed = VerbatimAttribution.attribute(literal, parse, listOf(head(0, "m1", store)))

            attributed?.attributeRef shouldBe "er.entity.store.name"
            attributed?.mentionId shouldBe "m1"
        }

        "a fourth hop is too far: the chain is bounded, not followed to the root" {
            val text = "zákazník a a a \"Valmy\""
            val parse =
                parseOf(
                    token("zákazník", 0, 8, 0),
                    token("a", 9, 10, 1),
                    token("a", 11, 12, 2),
                    token("a", 13, 14, 3),
                    token("\"", 15, 16, 4),
                    token("Valmy", 16, 21, 4), // → a → a → a → zákazník = 4 hops
                    token("\"", 21, 22, 4),
                )
            val literal = Literals.of(text, parse).spans.single()

            // Out of parse reach, and out of distance reach too (5 tokens away): headless. A
            // literal that far from anything is exactly the case where guessing would be wrong.
            VerbatimAttribution.attribute(literal, parse, listOf(head(0, "m1", store))).shouldBeNull()
        }

        "with no parse, the nearest mention within three tokens scopes it — left preferred" {
            // The degraded floor. Two heads equidistant: the one BEFORE the literal wins, because
            // that is where the thing a string restricts sits in both languages this serves.
            val text = "prodejna \"Valmy\" region"
            val parse = AnalyzeResponse.getDefaultInstance()
            val literal = Literals.of(text, parse).spans.single()
            val region = store.copy(ref = "er.entity.region", nameRef = "er.entity.region.name")

            val attributed =
                VerbatimAttribution.attribute(
                    literal,
                    parse,
                    listOf(head(2, "m2", region), head(0, "m1", store)),
                )

            attributed?.mentionId shouldBe "m1"
            attributed?.attributeRef shouldBe "er.entity.store.name"
        }

        "a head further than three tokens does not scope anything" {
            val text = "prodejna a a a \"Valmy\""
            val literal = Literals.of(text, AnalyzeResponse.getDefaultInstance()).spans.single()

            VerbatimAttribution
                .attribute(literal, AnalyzeResponse.getDefaultInstance(), listOf(head(0, "m1", store)))
                .shouldBeNull()
        }

        "no mentions at all ⇒ headless, never a default column" {
            val text = "\"Valmy\""
            val literal = Literals.of(text, AnalyzeResponse.getDefaultInstance()).spans.single()

            VerbatimAttribution.attribute(literal, AnalyzeResponse.getDefaultInstance(), emptyList()).shouldBeNull()
        }
    }) {
    companion object {
        private fun head(
            headToken: Int,
            mentionId: String,
            entityType: ResolverEntityType,
        ) = VerbatimAttribution.Head(headToken, mentionId, entityType)

        private fun parseOf(vararg tokens: Token): AnalyzeResponse =
            AnalyzeResponse
                .newBuilder()
                .setLanguage("cs")
                .addAllTokens(tokens.toList())
                .build()

        /** [depHead] is 1-based, as the wire has it; 0 is the root. */
        private fun token(
            text: String,
            start: Int,
            end: Int,
            depHead: Int,
        ): Token =
            Token
                .newBuilder()
                .setText(text)
                .setCharStart(start)
                .setCharEnd(end)
                .setLemma(text)
                .setUpos("NOUN")
                .setDepHead(depHead)
                .setDepRelation("dep")
                .build()
    }
}
