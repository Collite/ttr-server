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

        "the model's own pattern OR the fallback shape — §2.1 says or (review-103 F6)" {
            // An estate that declared a pattern has said what ELSE a code looks like here; it did
            // not say that nothing else is one. The resolver used the pattern INSTEAD of the shape,
            // so a head with a date-mask format lost every ordinary code.
            val periodic = store.copy(codeFormat = "^[0-9]{4}Q[1-4]$")

            VerbatimAttribution.attributeRefOf("2026Q1", periodic) shouldBe "er.entity.store.code"
            VerbatimAttribution.attributeRefOf("A-14/B", periodic) shouldBe "er.entity.store.code"
            VerbatimAttribution.attributeRefOf("Pelex", periodic) shouldBe "er.entity.store.name"
        }

        "a declared pattern makes a LETTER-ONLY code a code (ruling 3)" {
            // hartland's TPC-DS business keys: sixteen capitals, no digit. The fallback shape needs
            // a digit, so only the head's own `code_pattern` can say these are codes.
            val tpcds = store.copy(codeFormat = "^[A-P]{16}$")

            VerbatimAttribution.attributeRefOf("AAAAAAAABAAAAAAA", tpcds) shouldBe "er.entity.store.code"
            VerbatimAttribution.attributeRefOf("PELEX", tpcds) shouldBe "er.entity.store.name"
        }

        "a CODE-ONLY head drops the digit rider (ruling 3)" {
            // With no name column there is nowhere else for a shouted literal to go: the rider
            // exists to keep `PELEX` out of a code column WHEN a name column is its home.
            val codeOnly = store.copy(nameRef = "")

            VerbatimAttribution.attributeRefOf("AAAAAAAABAAAAAAA", codeOnly) shouldBe "er.entity.store.code"
            VerbatimAttribution.attributeRefOf("PELEX", codeOnly) shouldBe "er.entity.store.code"
            // …but a literal that is not code-SHAPED at all is still nothing this head can take.
            VerbatimAttribution.attributeRefOf("Pelex Oil", codeOnly).shouldBeNull()
        }

        "an ATTRIBUTE head is its own answer (review-103 F14)" {
            // *zákazníky s názvem "Valmy"* with `názvem` bound to the attribute: the user named
            // the column. It used to stop the walk with nothing, and the literal went headless.
            val nameAttr =
                ResolverEntityType(
                    ref = "er.entity.store.name",
                    categories = listOf("er.entity.store.name"),
                    anchors = listOf("název"),
                    objectKind = "attribute",
                    ownerRef = "er.entity.store",
                )
            val text = "prodejny s názvem \"Valmy\""
            val literal = Literals.of(text, AnalyzeResponse.getDefaultInstance()).spans.single()

            val attributed =
                VerbatimAttribution.attribute(
                    literal,
                    AnalyzeResponse.getDefaultInstance(),
                    listOf(head(2, "m2", nameAttr)),
                )

            attributed?.attributeRef shouldBe "er.entity.store.name"
            attributed?.facet shouldBe VerbatimAttribution.Facet.NAME
            // An attribute some entity declares as its CODE is a code facet — `equals` by default.
            VerbatimAttribution
                .attribute(
                    literal,
                    AnalyzeResponse.getDefaultInstance(),
                    listOf(head(2, "m2", nameAttr.copy(ref = "er.entity.store.code"))),
                    codeRefs = setOf("er.entity.store.code"),
                )?.facet shouldBe VerbatimAttribution.Facet.CODE
        }

        "a head that yields nothing does not end the search (review-103 F14)" {
            // The nearest head is a measure — never a thing a string restricts — so the walk goes
            // on to the store two words further left, still inside the bound.
            val sales = store.copy(ref = "md.measure.sales", nameRef = "", codeRef = "", objectKind = "measure")
            val text = "prodejny tržby \"Pelex\""
            val literal = Literals.of(text, AnalyzeResponse.getDefaultInstance()).spans.single()

            VerbatimAttribution
                .attribute(
                    literal,
                    AnalyzeResponse.getDefaultInstance(),
                    listOf(head(1, "m2", sales), head(0, "m1", store)),
                )?.mentionId shouldBe "m1"
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

        "distance runs edge to edge: delimiter to the NEAREST word of the head (review-103 F2)" {
            // The floor tokenizer splits the opening quote off, and a two-word head is proposed from
            // its FIRST word. Measured first-content-token to head-word, *dodací místa začínající
            // na "Pelex"* was 5 apart and headless; edge to edge it is 3, inside the bound.
            val text = "dodací místa začínající na \"Pelex\""
            val parse =
                parseOf(
                    token("dodací", 0, 6, 0),
                    token("místa", 7, 12, 0),
                    token("začínající", 13, 23, 0),
                    token("na", 24, 26, 0),
                    token("\"", 27, 28, 0),
                    token("Pelex", 28, 33, 0),
                    token("\"", 33, 34, 0),
                )
            val literal = Literals.of(text, parse).spans.single()
            val twoWord = VerbatimAttribution.Head(0, "m1", store, firstToken = 0, lastToken = 1)

            VerbatimAttribution.attribute(literal, parse, listOf(twoWord))?.mentionId shouldBe "m1"
        }

        "LEFT first: a head on the right is used only when there is none on the left (review-103 F2b)" {
            // *prodejny začínající na "Pel" podle zákazníků*: the customer is nearer, on the right.
            val text = "prodejny začínající na \"Pel\" podle zákazníků"
            val parse =
                parseOf(
                    token("prodejny", 0, 8, 0),
                    token("začínající", 9, 19, 0),
                    token("na", 20, 22, 0),
                    token("\"", 23, 24, 0),
                    token("Pel", 24, 27, 0),
                    token("\"", 27, 28, 0),
                    token("podle", 29, 34, 0),
                    token("zákazníků", 35, 44, 0),
                )
            val literal = Literals.of(text, parse).spans.single()
            val customer = store.copy(ref = "er.entity.customer", nameRef = "er.entity.customer.name")

            VerbatimAttribution
                .attribute(literal, parse, listOf(head(7, "m2", customer), head(0, "m1", store)))
                ?.attributeRef shouldBe "er.entity.store.name"
            // …and with no head on the left, the right one is taken.
            VerbatimAttribution
                .attribute(literal, parse, listOf(head(7, "m2", customer)))
                ?.attributeRef shouldBe "er.entity.customer.name"
        }

        "a predicate is taken from the LEFT only, whatever the parse says (review-103 F2b)" {
            // *zákazníka "Valmy" začínající na "Pe"*: the parse hangs *začínající* near Valmy, but
            // it qualifies the NEXT literal.
            val text = "zákazníka \"Valmy\" začínající na \"Pe\""
            val parse =
                parseOf(
                    token("zákazníka", 0, 9, 0),
                    token("\"", 10, 11, 3),
                    token("Valmy", 11, 16, 5), // → začínající
                    token("\"", 16, 17, 3),
                    token("začínající", 18, 28, 1),
                    token("na", 29, 31, 5),
                    token("\"", 32, 33, 8),
                    token("Pe", 33, 35, 6), // → na → začínající
                    token("\"", 35, 36, 8),
                )
            val (valmy, pe) = Literals.of(text, parse).spans
            val triggers = listOf(trigger(4, "pred:starts_with"), trigger(5, "pred:starts_with"))

            VerbatimAttribution.predicate(valmy, parse, triggers) shouldBe ""
            VerbatimAttribution.predicate(pe, parse, triggers) shouldBe "pred:starts_with"
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

        // ---- LP-P2b·T5 (§2/§3): the `pred:` ref beside the attribution -------------------------

        "the predicate is found up the same dep chain as the head" {
            // *dodací místa začínající na "Pelex"* — the shape the whole effort exists for. The
            // chain runs `Pelex → na → začínající → místa`, so the TRIGGER sits on the path to the
            // head: one walk, one window, two answers.
            val text = "místa začínající na \"Pelex\""
            val parse =
                parseOf(
                    token("místa", 0, 5, 0), // 0 root — the head mention
                    token("začínající", 6, 16, 1), // → místa
                    token("na", 17, 19, 2), // → začínající
                    token("\"", 20, 21, 3),
                    token("Pelex", 21, 26, 3), // → na → začínající = 2 hops to the trigger
                    token("\"", 26, 27, 3),
                )
            val literal = Literals.of(text, parse).spans.single()

            VerbatimAttribution.predicate(literal, parse, listOf(trigger(1, "pred:starts_with"))) shouldBe
                "pred:starts_with"
            // And the head is still found, through the same chain, one hop further up.
            VerbatimAttribution
                .attribute(literal, parse, listOf(head(0, "m1", store)))
                ?.attributeRef shouldBe "er.entity.store.name"
        }

        "no trigger in the question ⇒ \"\", and §2.2's default is the consumer's business" {
            // *zákazník "Valmy"* names no predicate. "" is not a failure: the reading a user who
            // wrote no trigger meant is `contains` for a name and `equals` for a code, and the
            // lattice writes that default out from the facet the attribution landed on (D5).
            val text = "zákazník \"Valmy\""
            val parse =
                parseOf(
                    token("zákazník", 0, 8, 0),
                    token("\"", 9, 10, 1),
                    token("Valmy", 10, 15, 1),
                    token("\"", 15, 16, 1),
                )
            val literal = Literals.of(text, parse).spans.single()

            VerbatimAttribution.predicate(literal, parse, emptyList()) shouldBe ""
            VerbatimAttribution.predicate(literal, parse, listOf(trigger(9, "pred:contains"))) shouldBe ""
        }

        "with no parse, the nearest trigger within three tokens wins — left preferred" {
            // The degraded floor again, and the same left preference: both languages put the
            // predicate before the string it applies to.
            val text = "začínající \"Pelex\" obsahující"
            val parse = AnalyzeResponse.getDefaultInstance()
            val literal = Literals.of(text, parse).spans.single()

            VerbatimAttribution.predicate(
                literal,
                parse,
                listOf(trigger(2, "pred:contains"), trigger(0, "pred:starts_with")),
            ) shouldBe "pred:starts_with"
        }

        "a trigger further than three tokens scopes nothing" {
            val text = "začínající a a a \"Pelex\""
            val literal = Literals.of(text, AnalyzeResponse.getDefaultInstance()).spans.single()

            VerbatimAttribution.predicate(
                literal,
                AnalyzeResponse.getDefaultInstance(),
                listOf(trigger(0, "pred:starts_with")),
            ) shouldBe ""
        }

        "a trigger with no head still speaks — the G3 loses the column, not the comparison" {
            // Attribution and predicate are looked up independently on purpose. A literal that
            // found no head is reported as a G3 gap; throwing away what the user DID say about
            // the comparison would make that gap harder to answer, not easier.
            val text = "začínající na \"Pelex\""
            val parse =
                parseOf(
                    token("začínající", 0, 10, 0),
                    token("na", 11, 13, 1),
                    token("\"", 14, 15, 2),
                    token("Pelex", 15, 20, 2),
                    token("\"", 20, 21, 2),
                )
            val literal = Literals.of(text, parse).spans.single()

            VerbatimAttribution.attribute(literal, parse, emptyList()).shouldBeNull()
            VerbatimAttribution.predicate(literal, parse, listOf(trigger(0, "pred:starts_with"))) shouldBe
                "pred:starts_with"
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

        private fun trigger(
            headToken: Int,
            ref: String,
        ) = VerbatimAttribution.Trigger(headToken, ref)

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
