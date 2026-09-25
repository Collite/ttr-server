// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.resolver.model.Reach
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.Binder
import org.tatrman.resolver.pipeline.EquivalentReading
import org.tatrman.resolver.pipeline.Slot
import org.tatrman.resolver.pipeline.SlotHint
import org.tatrman.resolver.v1.EvidenceClass

/**
 * RV-P2.2.T1 — the evidence-class gate, tested as the pure decision it is.
 *
 * The class order is `EXACT > DECLARED_ALIAS > LEARNED_ALIAS > ANCHORED_FUZZY_STRONG >
 * UNANCHORED_FUZZY_STRONG > WEAK` (RV-14), and the one rule that makes it a lexicographic order
 * rather than a tie-breaker is asserted here from several directions: **a numeric score compares
 * only WITHIN a class**. A 0.99 in a lower class does not beat a 0.62 in a higher one, and no
 * score whatsoever lifts a WEAK candidate into a binding.
 *
 * These cases feed [Binder.decide] pre-classified candidates on purpose — deriving the class from
 * a matcher row is a separate question with its own list (T2/T4, `EvidenceClassesTest`), and
 * mixing the two would make an ordering bug look like a derivation bug.
 */
class BinderTest :
    StringSpec({

        val thresholds = ResolverThresholds.LIVE

        "(a) a unique top class binds, and everything below it is rejected rather than recorded" {
            val verdict =
                Binder.decide(
                    listOf(
                        classed("acct-501001", 0.62, EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS),
                        classed("cc-5au5001", 0.99, EvidenceClass.EVIDENCE_CLASS_UNANCHORED_FUZZY_STRONG),
                    ),
                    thresholds,
                )
            val bind = verdict.shouldBeInstanceOf<Binder.Bind>()
            bind.winner.match.candidateId shouldBe "acct-501001"
            bind.admitted.map { it.match.candidateId } shouldContainExactly listOf("acct-501001")
            bind.rejected.map { it.match.candidateId } shouldContainExactly listOf("cc-5au5001")
        }

        "(b) two candidates in the SAME class within the tie band → ambiguous, nothing binds" {
            val verdict =
                Binder.decide(
                    listOf(
                        classed("df-adnak", 0.72, EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG),
                        classed("df-belus", 0.70, EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG),
                    ),
                    thresholds,
                )
            val ambiguous = verdict.shouldBeInstanceOf<Binder.Ambiguous>()
            ambiguous.admitted.map { it.match.candidateId } shouldContainExactlyInAnyOrder
                listOf("df-adnak", "df-belus")
        }

        "same class but OUTSIDE the tie band: the score decides, because they are comparable" {
            val verdict =
                Binder.decide(
                    listOf(
                        classed("df-adnak", 0.95, EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG),
                        classed("df-belus", 0.71, EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG),
                    ),
                    thresholds,
                )
            verdict
                .shouldBeInstanceOf<Binder.Bind>()
                .winner.match.candidateId shouldBe "df-adnak"
        }

        "(c) a higher class beats a higher score in a lower class — 0.99 UNANCHORED loses to 0.62 DECLARED" {
            val verdict =
                Binder.decide(
                    listOf(
                        classed("near-name", 0.99, EvidenceClass.EVIDENCE_CLASS_UNANCHORED_FUZZY_STRONG),
                        classed("declared", 0.62, EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS),
                    ),
                    thresholds,
                )
            verdict
                .shouldBeInstanceOf<Binder.Bind>()
                .winner.match.candidateId shouldBe "declared"
        }

        "(d) WEAK never binds, whatever its score — even alone, even at 1.0" {
            val verdict =
                Binder.decide(
                    listOf(classed("garbage", 1.0, EvidenceClass.EVIDENCE_CLASS_WEAK)),
                    thresholds,
                )
            val noBind = verdict.shouldBeInstanceOf<Binder.NoBind>()
            noBind.admitted shouldBe emptyList()
            noBind.rejected.map { it.match.candidateId } shouldContainExactly listOf("garbage")
        }

        "(d′) a whole field of WEAK candidates is a G1/G3, not a clarification to offer the user" {
            val verdict =
                Binder.decide(
                    listOf(
                        classed("5au-5001", 0.667, EvidenceClass.EVIDENCE_CLASS_WEAK),
                        classed("7ax-0800", 0.500, EvidenceClass.EVIDENCE_CLASS_WEAK),
                    ),
                    thresholds,
                )
            verdict.shouldBeInstanceOf<Binder.NoBind>().admitted shouldBe emptyList()
        }

        "(e) LEARNED_ALIAS sits below DECLARED_ALIAS: only lexicon promotion buys the higher class" {
            val verdict =
                Binder.decide(
                    listOf(
                        classed("learned", 0.98, EvidenceClass.EVIDENCE_CLASS_LEARNED_ALIAS),
                        classed("declared", 0.80, EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS),
                    ),
                    thresholds,
                )
            verdict
                .shouldBeInstanceOf<Binder.Bind>()
                .winner.match.candidateId shouldBe "declared"
        }

        "(f) an empty candidate set is a NoBind — the gate reports nothing, the gap layer types it" {
            Binder.decide(emptyList(), thresholds).shouldBeInstanceOf<Binder.NoBind>().rejected shouldBe emptyList()
        }

        "two exact rows with DIFFERENT identities are a genuine tie — refuse over guess (RS-26)" {
            val verdict =
                Binder.decide(
                    listOf(
                        classed("fap-doklad", 0.9999, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        classed("fap-ukazatel", 0.9999, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    thresholds,
                )
            verdict.shouldBeInstanceOf<Binder.Ambiguous>().admitted.size shouldBe 2
        }

        "the SAME identity reached twice is one binding, not an ambiguity" {
            val verdict =
                Binder.decide(
                    listOf(
                        classed("b-praha", 0.92, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        classed("b-praha", 0.90, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    thresholds,
                )
            val bind = verdict.shouldBeInstanceOf<Binder.Bind>()
            bind.admitted.size shouldBe 1
            // the stronger of the two speaks for the identity
            bind.winner.match.score shouldBe 0.92
        }

        // --- MS-P3·S2 — the declared-containment collapse (contracts §8.3, design.md §10.2) ----

        val sales = "er.entity.sales"
        val amount = "er.entity.sales.amount_czk"
        val quantity = "er.entity.sales.quantity"
        val owners = mapOf(amount to sales, quantity to sales)

        "MS: an entity tied with its OWN attribute binds the attribute — one answer, two granularities" {
            val verdict =
                Binder.decide(
                    listOf(
                        declared(sales, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        declared(amount, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    thresholds,
                    owners,
                )
            val bind = verdict.shouldBeInstanceOf<Binder.Bind>()
            bind.winner.match.targetRef shouldBe amount
            bind.admitted.map { it.match.targetRef } shouldContainExactly listOf(amount)
            // nothing is silently dropped: the owner is nameable in the rung log
            bind.rejected.map { it.match.targetRef } shouldContainExactly listOf(sales)
        }

        "MS: with no declared owners the same input stays Ambiguous (pre-v3 estates unchanged)" {
            val verdict =
                Binder.decide(
                    listOf(
                        declared(sales, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        declared(amount, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    thresholds,
                )
            verdict.shouldBeInstanceOf<Binder.Ambiguous>().admitted.size shouldBe 2
        }

        "MS: two attributes of the SAME entity are still a genuine tie — no sibling collapse" {
            val verdict =
                Binder.decide(
                    listOf(
                        declared(amount, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        declared(quantity, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    thresholds,
                    owners,
                )
            verdict.shouldBeInstanceOf<Binder.Ambiguous>().admitted.map {
                it.match.targetRef
            } shouldContainExactlyInAnyOrder
                listOf(amount, quantity)
        }

        "MS: an entity and an UNRELATED entity's attribute are still a genuine tie" {
            val verdict =
                Binder.decide(
                    listOf(
                        declared("er.entity.branch", 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        declared(amount, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    thresholds,
                    owners,
                )
            verdict.shouldBeInstanceOf<Binder.Ambiguous>().admitted.size shouldBe 2
        }

        "MS: a WEAK owner row is rejected BEFORE the collapse ever sees it" {
            val verdict =
                Binder.decide(
                    listOf(
                        declared(sales, 0.99, EvidenceClass.EVIDENCE_CLASS_WEAK),
                        declared(amount, 0.72, EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG),
                    ),
                    thresholds,
                    owners,
                )
            val bind = verdict.shouldBeInstanceOf<Binder.Bind>()
            bind.winner.match.targetRef shouldBe amount
            // the WEAK row is refused by RV-14, with its class intact — not by the collapse
            bind.rejected.single().evidenceClass shouldBe EvidenceClass.EVIDENCE_CLASS_WEAK
        }

        "MS: the collapse lives INSIDE the top class — a higher-class entity still wins outright" {
            val verdict =
                Binder.decide(
                    listOf(
                        declared(sales, 0.80, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        declared(amount, 0.99, EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG),
                    ),
                    thresholds,
                    owners,
                )
            // RV-14: the top class wins outright, and the attribute is not in it. No cross-class
            // rule crept in with the collapse.
            verdict
                .shouldBeInstanceOf<Binder.Bind>()
                .winner.match.targetRef shouldBe sales
        }

        "MS: a MEMBER identity is never collapsed, even when it carries an owned target ref" {
            // `M:` rows are data values, not model objects. This member row's targetRef IS the
            // entity the attribute declares as its owner — so without the `V:`-only guard the
            // collapse would delete a data value on the strength of a model relation and bind the
            // attribute alone. Drop the guard and this case turns into a Bind.
            val verdict =
                Binder.decide(
                    listOf(
                        member("row-in-sales", sales, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        declared(amount, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    thresholds,
                    owners,
                )
            verdict.shouldBeInstanceOf<Binder.Ambiguous>().admitted.size shouldBe 2
        }

        "MS: out-of-band owners are not resurrected by the collapse" {
            // The entity is outside the tie band, so it is already rejected when the collapse runs
            // — and the attribute binds on its own, exactly as it did before MS.
            val verdict =
                Binder.decide(
                    listOf(
                        declared(amount, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        declared(sales, 0.10, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    thresholds,
                    owners,
                )
            val bind = verdict.shouldBeInstanceOf<Binder.Bind>()
            bind.winner.match.targetRef shouldBe amount
            bind.rejected.map { it.match.targetRef } shouldContainExactly listOf(sales)
        }

        // --- review-084 F3 — malformed containment, the two shapes that empty the survivors ----

        "MS: a containment CYCLE declines the collapse instead of throwing" {
            // `owners` is data this service did not produce. A cycle collapses every identity and
            // leaves nothing to bind; the rule declines and the ordinary tie check answers, which
            // for two distinct identities in the band is what it always was — a refusal.
            val verdict =
                Binder.decide(
                    listOf(
                        declared(sales, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        declared(amount, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    thresholds,
                    mapOf(sales to amount, amount to sales),
                )
            val ambiguous = verdict.shouldBeInstanceOf<Binder.Ambiguous>()
            ambiguous.admitted.map { it.match.targetRef } shouldContainExactlyInAnyOrder listOf(sales, amount)
            // and nothing was moved to `rejected` by a collapse that did not happen
            ambiguous.rejected.shouldBeEmpty()
        }

        "MS: a ref declared as its OWN owner still binds — never a clarification with one option" {
            // The degenerate half of the same guard, and the one that used to be wrong: returning
            // `Ambiguous` from the empty-survivors branch made `Ambiguous` reachable with a single
            // admitted candidate, and `GateSpans.outcomeOf` renders any ambiguous span by offering
            // its contenders. One row in, one option out — the exact question the collapse exists
            // to remove, produced from malformed data instead of from good data.
            val verdict =
                Binder.decide(
                    listOf(declared(amount, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT)),
                    thresholds,
                    mapOf(amount to amount),
                )
            verdict
                .shouldBeInstanceOf<Binder.Bind>()
                .winner.match.targetRef shouldBe amount
        }

        "UNSPECIFIED never outranks a real class, though proto3 gives it the zero value" {
            val verdict =
                Binder.decide(
                    listOf(
                        classed("unknown", 1.0, EvidenceClass.EVIDENCE_CLASS_UNSPECIFIED),
                        classed("anchored", 0.75, EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG),
                    ),
                    thresholds,
                )
            verdict
                .shouldBeInstanceOf<Binder.Bind>()
                .winner.match.candidateId shouldBe "anchored"
        }

        // --- MH (contracts §7.3, §8.1) — the slot rule and the reachability rule --------------
        //
        // One word, two objects: `prodejna` is `er.entity.store`'s label (a six-row dimension)
        // AND a form of the Stores-channel term pinned to `er.entity.store_sales` (the fact).
        // Neither owns the other, so the MS collapse does not apply and the band holds two
        // unrelated `V:` identities — a G2 the Binder is RIGHT to raise on the evidence it had.
        // What MH adds is evidence: the sentence's slot, and the model's declared relations.

        val store = "er.entity.store"
        val storeSales = "er.entity.store_sales"
        val storeReturns = "er.entity.store_returns"
        val webSales = "er.entity.web_sales"

        val kinds =
            mapOf(
                store to "entity",
                storeSales to "entity_with_measures",
                storeReturns to "entity",
                webSales to "entity_with_measures",
            )
        val reach =
            mapOf(
                store to listOf(Reach(storeSales, mandatory = true), Reach(storeReturns, mandatory = true)),
            )

        /** The hartland tie: two EXACT identities, same score, inside the band. */
        fun homonym() =
            listOf(
                declared(store, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                declared(storeSales, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
            )

        fun decideMh(
            classedMatches: List<Binder.ClassedMatch> = homonym(),
            slot: SlotHint = SlotHint.NONE,
            kindsMap: Map<String, String> = kinds,
            reachMap: Map<String, List<Reach>> = emptyMap(),
            ownersMap: Map<String, String> = emptyMap(),
        ) = Binder.decide(classedMatches, thresholds, ownersMap, slot, kindsMap, reachMap)

        "mh-b1: COUNT_HEAD binds the dimension — you count things, not measurements" {
            val bind = decideMh(slot = SlotHint(Slot.COUNT_HEAD)).shouldBeInstanceOf<Binder.Bind>()
            bind.winner.match.targetRef shouldBe store
            bind.admitted.map { it.match.targetRef } shouldContainExactly listOf(store)
            // Nothing silently dropped — the fact reading rides the rung log.
            bind.rejected.map { it.match.targetRef } shouldContainExactly listOf(storeSales)
        }

        "mh-b2: GROUP_BY binds the dimension — `podle X` wants a groupable axis" {
            decideMh(slot = SlotHint(Slot.GROUP_BY))
                .shouldBeInstanceOf<Binder.Bind>()
                .winner.match.targetRef shouldBe store
        }

        "mh-b7: COORD_WITH takes the sibling's kind — channel vs channel, not six rows vs a channel" {
            decideMh(slot = SlotHint(Slot.COORD_WITH, coordSiblingKinds = setOf("entity_with_measures")))
                .shouldBeInstanceOf<Binder.Bind>()
                .winner.match.targetRef shouldBe storeSales
        }

        "mh-b8: NONE is byte-identical to today — a bare word still asks" {
            decideMh(slot = SlotHint.NONE).shouldBeInstanceOf<Binder.Ambiguous>().admitted.size shouldBe 2
        }

        "mh-b9: a SAME-kind tie is genuine homonymy and stays ambiguous" {
            // Two dimensions sharing a word (`region` as geography vs as sales org). The slot
            // says "an entity" and both ARE entities: nothing to prefer, so refuse.
            val bothEntities = mapOf(store to "entity", storeSales to "entity")
            decideMh(slot = SlotHint(Slot.COUNT_HEAD), kindsMap = bothEntities)
                .shouldBeInstanceOf<Binder.Ambiguous>()
                .admitted.size shouldBe 2
        }

        "mh-b10: an M: member in the band is untouched by the slot rule" {
            // Instance ambiguity forces a Clarify (RS-26) and the kind rules have nothing to say
            // about a data row: `M:` is a different species from `V:` and stays one.
            val withMember =
                listOf(
                    declared(store, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    member("store-42", store, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                )
            val slotted = decideMh(withMember, slot = SlotHint(Slot.COUNT_HEAD))
            val plain = Binder.decide(withMember, thresholds)

            slotted.shouldBeInstanceOf<Binder.Ambiguous>()
            slotted.admitted.map { Binder.identityKey(it.match) } shouldContainExactlyInAnyOrder
                plain.admitted.map { Binder.identityKey(it.match) }
            slotted.rejected.none { it.match.source == SourceTag.MEMBER } shouldBe true
        }

        "mh-b12: a BLANK kind is not a species — a partly-described registry asks, it does not bind" {
            // review-087 F1. `kindsByRef` omits blank kinds, so a ref the archive declares nothing
            // about arrives here with `""` — and every md-owned ref is kind-less by construction
            // (`MentionKinds` covers Entity/DbTable/Attribute/DbColumn; `Model` has no md schema).
            // Dropping it in favour of a kinded row would make the resolver bind where the
            // all-undeclared case (mh-b11) correctly asks.
            val md = "md.measure.revenue"
            val mixed =
                listOf(
                    declared(store, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    declared(md, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                )
            val verdict = decideMh(mixed, slot = SlotHint(Slot.COUNT_HEAD), kindsMap = mapOf(store to "entity"))

            verdict.shouldBeInstanceOf<Binder.Ambiguous>().admitted.map {
                it.match.targetRef
            } shouldContainExactlyInAnyOrder
                listOf(store, md)
            // …and the undeclared row is not sitting in the rung log as a refusal either.
            verdict.rejected.none { it.match.targetRef == md } shouldBe true
        }

        "mh-b12b: COORD_WITH over an all-undeclared sibling prefers nothing — a no-op, not a drop" {
            // `coordSiblingKinds` is built with `mapNotNull { kindsByRef[it] }.filter { isNotBlank() }`,
            // so a sibling whose refs are all undeclared yields an EMPTY preference. Confirmed
            // here rather than assumed (review-087 F1, last bullet).
            decideMh(slot = SlotHint(Slot.COORD_WITH, coordSiblingKinds = emptySet()))
                .shouldBeInstanceOf<Binder.Ambiguous>()
                .admitted.size shouldBe 2
        }

        "mh-b11: no kinds ⇒ no preference — an estate that declared nothing is unchanged" {
            decideMh(slot = SlotHint(Slot.COUNT_HEAD), kindsMap = emptyMap())
                .shouldBeInstanceOf<Binder.Ambiguous>()
                .admitted.size shouldBe 2
        }

        // --- MH invariants: what the slot rule may NOT do ---------------------------------------

        "MH: the slot never promotes from below the band — the band decides first" {
            val verdict =
                decideMh(
                    listOf(
                        declared(storeSales, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        declared(store, 0.80, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    slot = SlotHint(Slot.COUNT_HEAD),
                )
            // `store` is 0.20 below the top and the gap is 0.05: it never reaches the rule.
            verdict
                .shouldBeInstanceOf<Binder.Bind>()
                .winner.match.targetRef shouldBe storeSales
        }

        "MH: WEAK is rejected before the slot rule, whatever the slot prefers" {
            val verdict =
                decideMh(
                    listOf(
                        declared(store, 0.99, EvidenceClass.EVIDENCE_CLASS_WEAK),
                        declared(storeSales, 0.72, EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG),
                    ),
                    slot = SlotHint(Slot.COUNT_HEAD),
                )
            val bind = verdict.shouldBeInstanceOf<Binder.Bind>()
            bind.winner.match.targetRef shouldBe storeSales
            bind.rejected.map { it.match.targetRef } shouldContainExactly listOf(store)
        }

        "MH: the MS containment collapse runs FIRST, and the slot rule sees its result" {
            // `sales` + its own measure `amount_czk` + an unrelated homonym `store`, under a
            // filter with a measure-capable head. Containment collapses the entity into its
            // measure; THEN the slot keeps the measure-ish row and drops the dimension.
            val verdict =
                decideMh(
                    listOf(
                        declared(sales, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        declared(amount, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        declared(store, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    slot = SlotHint(Slot.FILTER, headRefs = listOf(sales), headMeasureCapable = true),
                    kindsMap =
                        mapOf(
                            sales to "entity_with_measures",
                            amount to "measure",
                            store to "entity",
                        ),
                    ownersMap = owners,
                )
            val bind = verdict.shouldBeInstanceOf<Binder.Bind>()
            bind.winner.match.targetRef shouldBe amount
            bind.rejected.map { it.match.targetRef } shouldContainExactlyInAnyOrder listOf(sales, store)
        }

        "MH: a preference that matches NOTHING leaves the band alone" {
            // FILTER with a non-measure-capable head prefers nothing at all (contracts §7.3), so
            // the tie survives as a tie rather than collapsing to whatever happened to be first.
            decideMh(slot = SlotHint(Slot.FILTER, headRefs = listOf(storeReturns), headMeasureCapable = false))
                .shouldBeInstanceOf<Binder.Ambiguous>()
                .admitted.size shouldBe 2
        }

        "MH: Ambiguous is never single — a rule that would empty the band yields the band" {
            // GOVERNED_VALUE prefers `entity`, and NEITHER candidate is one. The rule must not
            // leave zero (or one) admitted; it must decline (RV-14, the review-084 F3 pattern).
            val twoFacts =
                listOf(
                    declared(storeSales, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    declared(webSales, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                )
            val verdict = decideMh(twoFacts, slot = SlotHint(Slot.GOVERNED_VALUE))
            verdict.shouldBeInstanceOf<Binder.Ambiguous>().admitted.size shouldBe 2
        }

        "MH: full kinds with slot NONE reproduces the pre-MH verdict on the MS inputs" {
            // The no-op guarantee, on the corpus this file already had: same inputs, same
            // verdict, whether or not the new maps are supplied.
            val msInputs =
                listOf(
                    declared(sales, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    declared(amount, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                )
            val withMh =
                Binder.decide(
                    msInputs,
                    thresholds,
                    owners,
                    SlotHint.NONE,
                    mapOf(sales to "entity_with_measures", amount to "measure"),
                    reach,
                )
            val plain = Binder.decide(msInputs, thresholds, owners)

            withMh
                .shouldBeInstanceOf<Binder.Bind>()
                .winner.match.targetRef shouldBe
                plain
                    .shouldBeInstanceOf<Binder.Bind>()
                    .winner.match.targetRef
            withMh.admitted.map { it.match.targetRef } shouldBe plain.admitted.map { it.match.targetRef }
            withMh.rejected.map { it.match.targetRef } shouldBe plain.rejected.map { it.match.targetRef }
        }

        // --- MH T3 (contracts §7.3, rules 1-4) — reachability -----------------------------------

        "mh-b3: a filter under the channel's OWN fact collapses to the dimension, and says so" {
            // T2 prefers the fact (measure-ish slot); T3 rule 2 says the two readings are
            // declared-EQUAL on this model — every store_sales row carries a store — and flips
            // to the dimension, which keeps group-by and member filters possible.
            val bind =
                decideMh(
                    slot = SlotHint(Slot.FILTER, headRefs = listOf(storeSales), headMeasureCapable = true),
                    reachMap = reach,
                ).shouldBeInstanceOf<Binder.Bind>()
            bind.winner.match.targetRef shouldBe store
            bind.equivalents shouldBe listOf(EquivalentReading(storeSales, "reach-equal"))
        }

        "mh-b4: a filter under a DIFFERENT fact collapses to the dimension — the E6 mis-bind" {
            // "Vratky z prodejen": the channel term is pinned to store_sales, the clause is
            // about store_returns. T2 alone binds the wrong fact; T3 rule 3 says the dimension
            // is the only reading that fits this head.
            val bind =
                decideMh(
                    slot = SlotHint(Slot.FILTER, headRefs = listOf(storeReturns), headMeasureCapable = false),
                    reachMap = reach,
                ).shouldBeInstanceOf<Binder.Bind>()
            bind.winner.match.targetRef shouldBe store
            bind.equivalents shouldBe emptyList()
        }

        "mh-b5: a head OUTSIDE the dimension's reach leaves T2's pick standing (rule 1)" {
            // "Tržby webu ... na prodejně" — H is web_sales, which no relation ties to `store`
            // on this model, and H is not the channel's fact either. Neither reading is proven
            // about this clause, so T3 says nothing and the slot's guess stands. That guess is
            // the E7-shaped risk design.md accepts for T2 alone; mh-b6 is what turns it into a
            // question once the estate DECLARES the nullable reach.
            val bind =
                decideMh(
                    slot = SlotHint(Slot.FILTER, headRefs = listOf(webSales), headMeasureCapable = true),
                    reachMap = reach,
                ).shouldBeInstanceOf<Binder.Bind>()
            bind.winner.match.targetRef shouldBe storeSales
            bind.equivalents shouldBe emptyList()
        }

        "mh-b6: a NULLABLE reach makes the readings differ — refuse, and admit both (rule 4)" {
            // BOPIS-shaped: a store_sales row MAY carry a store. The dimension join would drop
            // the rows that do not; the channel restriction would keep them. Two different
            // answers, so the honest verdict is the question — and BOTH readings must be
            // admitted, because a Clarify offers its contenders.
            val ambiguous =
                decideMh(
                    slot = SlotHint(Slot.FILTER, headRefs = listOf(storeSales), headMeasureCapable = true),
                    reachMap = mapOf(store to listOf(Reach(storeSales, mandatory = false))),
                ).shouldBeInstanceOf<Binder.Ambiguous>()
            ambiguous.admitted.map { it.match.targetRef } shouldContainExactlyInAnyOrder
                listOf(store, storeSales)
        }

        "MH: rule 4 RE-ADMITS what T2 dropped — the rule runs over the pre-T2 set" {
            // The slot dropped the dimension (measure-ish preference); the nullable reach then
            // says the two readings differ. A rule that could only remove rows would leave one
            // admitted and call it a Bind — the exact shape review-084 F3 refused.
            val ambiguous =
                decideMh(
                    slot = SlotHint(Slot.FILTER, headRefs = listOf(storeSales), headMeasureCapable = true),
                    reachMap = mapOf(store to listOf(Reach(storeSales, mandatory = false))),
                ).shouldBeInstanceOf<Binder.Ambiguous>()
            ambiguous.admitted.size shouldBe 2
            // …and the re-admitted row is not ALSO sitting in the rung log as a refusal.
            ambiguous.rejected.none { it.match.targetRef == store } shouldBe true
        }

        "MH: with no reach the T3 rules are a no-op — every mh case keeps its S3 verdict" {
            decideMh(slot = SlotHint(Slot.FILTER, headRefs = listOf(storeSales), headMeasureCapable = true))
                .shouldBeInstanceOf<Binder.Bind>()
                .winner.match.targetRef shouldBe storeSales
            decideMh(slot = SlotHint(Slot.COUNT_HEAD))
                .shouldBeInstanceOf<Binder.Bind>()
                .winner.match.targetRef shouldBe store
        }

        "MH: an M: member beside the pair is untouched, and never lands in rejected" {
            val withMember =
                homonym() +
                    member("store-42", store, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT)
            val verdict =
                decideMh(
                    withMember,
                    slot = SlotHint(Slot.FILTER, headRefs = listOf(storeSales), headMeasureCapable = true),
                    reachMap = reach,
                )
            // Instance ambiguity forces a Clarify (RS-26) whatever the V: rules decided.
            val ambiguous = verdict.shouldBeInstanceOf<Binder.Ambiguous>()
            ambiguous.admitted.any { it.match.source == SourceTag.MEMBER } shouldBe true
            ambiguous.rejected.none { it.match.source == SourceTag.MEMBER } shouldBe true
        }

        "MH: a MEASURE candidate is read through its OWNER — the fact is what H compares to" {
            // The channel word bound the measure rather than the fact. `f` is the owner, so
            // rule 2 fires exactly as it does for the fact itself.
            val measureRef = "er.entity.store_sales.ext_sales_price"
            val bind =
                decideMh(
                    listOf(
                        declared(store, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        declared(measureRef, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
                    slot = SlotHint(Slot.FILTER, headRefs = listOf(storeSales), headMeasureCapable = true),
                    kindsMap = kinds + (measureRef to "measure"),
                    reachMap = reach,
                    ownersMap = mapOf(measureRef to storeSales),
                ).shouldBeInstanceOf<Binder.Bind>()
            bind.winner.match.targetRef shouldBe store
            bind.equivalents shouldBe listOf(EquivalentReading(measureRef, "reach-equal"))
        }

        "MH: several head refs — the rule fires for ANY of them, and records the equivalent once" {
            val bind =
                decideMh(
                    slot =
                        SlotHint(
                            Slot.FILTER,
                            headRefs = listOf(storeSales, webSales),
                            headMeasureCapable = true,
                        ),
                    reachMap = reach,
                ).shouldBeInstanceOf<Binder.Bind>()
            bind.winner.match.targetRef shouldBe store
            bind.equivalents shouldBe listOf(EquivalentReading(storeSales, "reach-equal"))
        }

        "MH: rule 4 dominates rule 2 for the same pair — a nullable reach wins over an equal one" {
            // An estate that declares BOTH a mandatory and a nullable relation between the same
            // pair is contradicting itself. Refuse-over-guess: the nullable one decides.
            val ambiguous =
                decideMh(
                    slot = SlotHint(Slot.FILTER, headRefs = listOf(storeSales), headMeasureCapable = true),
                    reachMap =
                        mapOf(
                            store to
                                listOf(
                                    Reach(storeSales, mandatory = true),
                                    Reach(storeSales, mandatory = false),
                                ),
                        ),
                ).shouldBeInstanceOf<Binder.Ambiguous>()
            ambiguous.admitted.size shouldBe 2
        }

        "MH: rule 4 dominates its OWN pair only — another pair's equivalent survives (F5)" {
            // review-087 F5. One band, two facts: `store_sales` is mandatory (rule 2 — equal) and
            // `web_sales` is nullable (rule 4 — differ). The nullable pair must refuse, and the
            // mandatory pair's disclosure must still be there: a global `differ` flag threw the
            // `reach-equal` away and left the user with a question and no explanation.
            val band =
                listOf(
                    declared(store, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    declared(storeSales, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    declared(webSales, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                )
            val verdict =
                decideMh(
                    band,
                    slot =
                        SlotHint(
                            Slot.FILTER,
                            headRefs = listOf(storeSales, webSales),
                            headMeasureCapable = true,
                        ),
                    reachMap =
                        mapOf(
                            store to
                                listOf(
                                    Reach(storeSales, mandatory = true),
                                    Reach(webSales, mandatory = false),
                                ),
                        ),
                )

            // The nullable pair refuses, so both of ITS readings stay admitted…
            val ambiguous = verdict.shouldBeInstanceOf<Binder.Ambiguous>()
            ambiguous.admitted.map { it.match.targetRef } shouldContainExactlyInAnyOrder
                listOf(store, webSales)
            // …and the mandatory pair still collapsed, with its fact named in the rung log.
            ambiguous.rejected.map { it.match.targetRef } shouldContainExactly listOf(storeSales)
        }

        "MH: a fact re-admitted by rule 4 is never dropped on another pair's account" {
            // `web_sales` is nullable from `store` (rule 4 — refuse) and, under the OTHER head,
            // rule 3 would drop it on `region`'s account. A refusal is the stronger claim: the
            // question must survive, so the intersection keeps the fact in.
            val secondDim = "er.entity.region"
            val band =
                listOf(
                    declared(store, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    declared(secondDim, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    declared(webSales, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                )
            val verdict =
                decideMh(
                    band,
                    slot =
                        SlotHint(
                            Slot.FILTER,
                            headRefs = listOf(storeSales, webSales),
                            headMeasureCapable = true,
                        ),
                    kindsMap = kinds + (secondDim to "entity"),
                    reachMap =
                        mapOf(
                            store to listOf(Reach(webSales, mandatory = false)),
                            secondDim to listOf(Reach(storeSales, mandatory = true)),
                        ),
                )

            verdict.shouldBeInstanceOf<Binder.Ambiguous>().admitted.map {
                it.match.targetRef
            } shouldContainExactlyInAnyOrder listOf(store, secondDim, webSales)
            verdict.rejected.none { it.match.targetRef == webSales } shouldBe true
        }

        "MH: rules 2 and 3 name the dropped fact in the rung log — nothing silently dropped" {
            decideMh(
                slot = SlotHint(Slot.FILTER, headRefs = listOf(storeSales), headMeasureCapable = true),
                reachMap = reach,
            ).rejected.map { it.match.targetRef } shouldContainExactly listOf(storeSales)

            decideMh(
                slot = SlotHint(Slot.FILTER, headRefs = listOf(storeReturns), headMeasureCapable = false),
                reachMap = reach,
            ).rejected.map { it.match.targetRef } shouldContainExactly listOf(storeSales)
        }

        "MH: with NO headRefs the reachability rule never fires — E9 keeps asking" {
            // The degenerate "any mandatory reach" form was dropped at /planning: a bare word
            // with no clause head must stay a question (design.md §4, the single-word regression).
            decideMh(slot = SlotHint(Slot.NONE), reachMap = reach)
                .shouldBeInstanceOf<Binder.Ambiguous>()
                .admitted.size shouldBe 2
        }

        // ── MH-P3 tier M — member vs member (contracts §7.5 M3, §8.5) ──────────────────
        //
        // Three attributes on three different entities each hold a member `TN`. That is a
        // SAME-kind tie: `M:` rows are data, so neither the slot rule nor reachability is asked
        // of them. What the sentence offers instead is the GOVERNOR — the noun the value hangs
        // off — and M3 keeps the members whose owning entity the governor either IS or reaches
        // by one declared relation.
        //
        // ⚑ `owner(m)` is the COLUMN-level attribute ref (P3·S1·T1 measured it), so every rule
        // below runs on `entityOf(owner) = owners[owner]`, never on the owner ref itself.
        val customer = "er.entity.customer"
        val customerAddress = "er.entity.customer_address"
        val warehouse = "er.entity.warehouse"
        val item = "er.entity.item"
        val storeState = "er.entity.store.state"
        val caState = "er.entity.customer_address.state"
        val whState = "er.entity.warehouse.state"

        val memberOwners = mapOf(storeState to storeState, caState to caState, whState to whState)
        val memberEntityOwners =
            mapOf(storeState to store, caState to customerAddress, whState to warehouse)
        val memberKinds =
            mapOf(
                store to "entity",
                customer to "entity",
                customerAddress to "entity",
                warehouse to "entity",
                item to "entity",
                storeSales to "entity_with_measures",
                webSales to "entity_with_measures",
            )
        val memberReach =
            mapOf(
                customerAddress to listOf(Reach(customer, mandatory = true)),
                store to listOf(Reach(storeSales, mandatory = true), Reach(storeReturns, mandatory = true)),
                warehouse to listOf(Reach("er.entity.catalog_sales", mandatory = true), Reach(webSales, true)),
            )

        /** The three `TN` rows, EXACT and tied — one per owning attribute. */
        fun members() =
            listOf(
                member("store#7", storeState, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                member("ca#3", caState, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                member("wh#1", whState, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
            )

        fun decideM(
            classedMatches: List<Binder.ClassedMatch> = members(),
            governorRef: String = "",
            kindsMap: Map<String, String> = memberKinds,
            reachMap: Map<String, List<Reach>> = memberReach,
            ownersMap: Map<String, String> = memberEntityOwners,
        ) = Binder.decide(
            classedMatches,
            thresholds,
            ownersMap,
            SlotHint(governorRef = governorRef),
            kindsMap,
            reachMap,
            memberOwners,
        )

        "mh-m1: an ENTITY governor binds its own attribute's member — `stores in TN`" {
            val bind = decideM(governorRef = store).shouldBeInstanceOf<Binder.Bind>()
            bind.winner.match.candidateId shouldBe "store#7"
            bind.rejected.map { it.match.candidateId } shouldContainExactlyInAnyOrder listOf("ca#3", "wh#1")
        }

        "review-104 F3 — one VALUE in three vocabularies is three readings, not one answer reached thrice" {
            // As lex-matcher answers since A-MV-15: the id IS the value, so all three rows carry
            // `TN`. An identity without the category collapsed them to one and bound whichever the
            // matcher listed first — no governor, no question, and the owner silently chosen.
            val sameValue =
                listOf(storeState, caState, whState).map {
                    member("TN", it, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT)
                }

            decideM(classedMatches = sameValue)
                .shouldBeInstanceOf<Binder.Ambiguous>()
                .admitted
                .map { it.match.category } shouldContainExactlyInAnyOrder listOf(storeState, caState, whState)
            // ...and the governor still picks its own attribute's reading out of the three.
            decideM(classedMatches = sameValue, governorRef = store)
                .shouldBeInstanceOf<Binder.Bind>()
                .winner.match.category shouldBe storeState
        }

        "review-104 F2 — the same value reached twice in ONE vocabulary is one answer" {
            // Two routes to `store.state = TN` (the governed span and a re-asked round) are not a tie.
            decideM(
                classedMatches =
                    listOf(
                        member("TN", storeState, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                        member("TN", storeState, 0.99, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    ),
            ).shouldBeInstanceOf<Binder.Bind>()
                .winner.match.score shouldBe 1.0
        }

        "mh-m2: a governor that REACHES the owner in one declared hop binds — `customers in TN`" {
            decideM(governorRef = customer)
                .shouldBeInstanceOf<Binder.Bind>()
                .winner.match.candidateId shouldBe "ca#3"
        }

        "mh-m3: a FACT governor never selects among member owners — `sales in TN` refuses" {
            decideM(governorRef = storeSales)
                .shouldBeInstanceOf<Binder.Ambiguous>()
                .admitted
                .map { it.match.candidateId } shouldContainExactlyInAnyOrder listOf("store#7", "ca#3", "wh#1")
        }

        "mh-m4: no governor is byte-identical to today — a bare `TN` still asks" {
            decideM(governorRef = "")
                .shouldBeInstanceOf<Binder.Ambiguous>()
                .admitted
                .shouldHaveSize(3)
        }

        "mh-m5: a governor reaching NONE of the owners is a no-op, never an empty band" {
            decideM(governorRef = item)
                .shouldBeInstanceOf<Binder.Ambiguous>()
                .admitted
                .shouldHaveSize(3)
        }

        "mh-m6: the rule needs two owners — one member row is unchanged" {
            decideM(
                classedMatches = listOf(member("store#7", storeState, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT)),
                governorRef = store,
            ).shouldBeInstanceOf<Binder.Bind>()
                .winner.match.candidateId shouldBe "store#7"
        }

        "mh-m7: an estate that declared no relations is a no-op — reach empty, still asks" {
            decideM(governorRef = customer, reachMap = emptyMap())
                .shouldBeInstanceOf<Binder.Ambiguous>()
                .admitted
                .shouldHaveSize(3)
        }

        "mh-m8: a `V:` row in the band is untouched — the rule speaks only about members" {
            val mixed =
                listOf(
                    member("store#7", storeState, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                    declared(store, 1.0, EvidenceClass.EVIDENCE_CLASS_EXACT),
                )
            val withGovernor = decideM(classedMatches = mixed, governorRef = store)
            val without = decideM(classedMatches = mixed, governorRef = "")

            withGovernor.shouldBeInstanceOf<Binder.Ambiguous>()
            withGovernor.admitted.map { Binder.identityKey(it.match) } shouldBe
                without.shouldBeInstanceOf<Binder.Ambiguous>().admitted.map { Binder.identityKey(it.match) }
        }

        "mh-m1a: the rule reads the OWNER's entity, not the owner ref — no `owners` map, no rule" {
            // `owner(m)` is `er.entity.store.state`; the governor is `er.entity.store`. Without
            // the owners map there is nothing to resolve one to the other, so M3 declines.
            decideM(governorRef = store, ownersMap = emptyMap())
                .shouldBeInstanceOf<Binder.Ambiguous>()
                .admitted
                .shouldHaveSize(3)
        }

        "mh-m2a: a governor narrowing to TWO owners asks with the two, not with all three" {
            // `warehouse` reaches web_sales and catalog_sales; give store's state the same
            // declared reach so two owners qualify and the third does not.
            val reach =
                memberReach + (store to listOf(Reach(webSales, mandatory = true)))
            val verdict =
                decideM(governorRef = webSales, kindsMap = memberKinds + (webSales to "entity"), reachMap = reach)
            verdict
                .shouldBeInstanceOf<Binder.Ambiguous>()
                .admitted
                .map { it.match.candidateId } shouldContainExactlyInAnyOrder listOf("store#7", "wh#1")
        }
    }) {
    private companion object {
        /** A MEMBER row that nonetheless carries a target ref — a data value inside an object. */
        private fun member(
            id: String,
            targetRef: String,
            score: Double,
            evidenceClass: EvidenceClass,
        ): Binder.ClassedMatch =
            Binder.ClassedMatch(
                FuzzyMatch
                    .newBuilder()
                    .setCandidateId(id)
                    .setCandidate(id)
                    .setScore(score)
                    .setTargetRef(targetRef)
                    .setCategory(targetRef)
                    .setSource(SourceTag.MEMBER)
                    .build(),
                evidenceClass,
            )

        /** A DECLARED row for a model object — identity `V:targetRef`, the collapse's unit. */
        private fun declared(
            targetRef: String,
            score: Double,
            evidenceClass: EvidenceClass,
        ): Binder.ClassedMatch =
            Binder.ClassedMatch(
                FuzzyMatch
                    .newBuilder()
                    .setCandidateId("lex:$targetRef")
                    .setCandidate(targetRef)
                    .setScore(score)
                    .setTargetRef(targetRef)
                    .setCategory(targetRef)
                    .setSource(SourceTag.DECLARED)
                    .build(),
                evidenceClass,
            )

        /** A member row at [score], already carrying the class the gate is being asked to order by. */
        private fun classed(
            id: String,
            score: Double,
            evidenceClass: EvidenceClass,
        ): Binder.ClassedMatch =
            Binder.ClassedMatch(
                FuzzyMatch
                    .newBuilder()
                    .setCandidateId(id)
                    .setCandidate(id)
                    .setScore(score)
                    .setCategory("md.dimension.Account.code")
                    .setSource(SourceTag.MEMBER)
                    .build(),
                evidenceClass,
            )
    }
}
