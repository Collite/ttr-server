// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.pipeline.DomainSpanCandidate
import org.tatrman.resolver.pipeline.SpanProposal

/**
 * RG-P5.S1.T3 — the anchored span proposal (Q-20 GO-WITH-FALLBACK, spike §5).
 * Builds the candidate set that gateSpans (T4) turns into one BatchMatch call.
 *
 * These fixtures are hand-authored dep parses (a real nlp `AnalyzeResponse`
 * shape) so the unit stays engine-free; the parity instrument (T6) exercises the
 * live corpora. The hero is the load-bearing case: `pražských pobočkách` must
 * propose as ONE multi-word candidate (the live single-head TODO is fixed here).
 */
class SpanProposalTest :
    StringSpec({

        // The declared registry the spike's anchored gating uses. `pobočka` is the
        // er.branch lexicon term (the anchor); `středisko` is the QSTRED_DF anchor.
        val branch =
            ResolverEntityType(ref = "er.branch", categories = listOf("er.branch"), anchors = listOf("pobočka"))
        val product = ResolverEntityType(ref = "er.product", categories = listOf("er.product"), anchors = emptyList())
        val qstred =
            ResolverEntityType(
                ref = "er.qstred_df",
                categories = listOf("er.qstred_df.kod", "er.qstred_df.nazev"),
                anchors = listOf("středisko"),
            )

        // "Kolik jsme utržili za Octavie v pražských pobočkách za poslední fiskální čtvrtletí?"
        // 0 Kolik 1 jsme 2 utržili(root) 3 za 4 Octavie(PROPN,obl) 5 v 6 pražských(amod→8)
        // 7 pobočkách(NOUN,obl,lemma pobočka) 8 za 9 poslední 10 fiskální 11 čtvrtletí 12 ?
        val hero =
            AnalyzeResponse
                .newBuilder()
                .addAllTokens(
                    listOf(
                        tok("Kolik", 0, 5, "kolik", "ADV", 3, "advmod"),
                        tok("jsme", 6, 10, "být", "AUX", 3, "aux"),
                        tok("utržili", 11, 18, "utržit", "VERB", 0, "root"),
                        tok("za", 19, 21, "za", "ADP", 5, "case"),
                        tok("Octavie", 22, 29, "Octavie", "PROPN", 3, "obl"),
                        tok("v", 30, 31, "v", "ADP", 8, "case"),
                        tok("pražských", 32, 41, "pražský", "ADJ", 8, "amod"),
                        tok("pobočkách", 42, 51, "pobočka", "NOUN", 3, "obl"),
                        tok("za", 52, 54, "za", "ADP", 12, "case"),
                        tok("poslední", 55, 63, "poslední", "ADJ", 12, "amod"),
                        tok("fiskální", 64, 72, "fiskální", "ADJ", 12, "amod"),
                        tok("čtvrtletí", 73, 82, "čtvrtletí", "NOUN", 3, "obl"),
                        tok("?", 82, 83, "?", "PUNCT", 3, "punct"),
                    ),
                ).addEntities(ner("poslední fiskální čtvrtletí", 55, 82, "DATE"))
                .build()

        "hero: `pražských pobočkách` proposes as ONE anchored multi-word candidate gated to er.branch" {
            val cands = SpanProposal.proposeDomainSpans(hero, listOf(branch, product))
            val branchCand = cands.single { it.text == "pražských pobočkách" }
            branchCand.anchored shouldBe true
            branchCand.gatedEntityRefs shouldBe listOf("er.branch")
            branchCand.start shouldBe 32
            branchCand.end shouldBe 51
        }

        "hero: `Octavie` proposes as an unanchored proper-noun candidate gated to all types" {
            val cands = SpanProposal.proposeDomainSpans(hero, listOf(branch, product))
            val octavie = cands.single { it.text == "Octavie" }
            octavie.anchored shouldBe false
            octavie.gatedEntityRefs shouldContainExactlyInAnyOrder listOf("er.branch", "er.product")
        }

        "hero: the universal DATE span `poslední fiskální čtvrtletí` produces no domain candidate" {
            val cands = SpanProposal.proposeDomainSpans(hero, listOf(branch, product))
            cands.any { it.text.contains("čtvrtletí") } shouldBe false
            // no candidate overlaps the excluded [55,82) DATE range
            cands.any { it.start < 82 && it.end > 55 }.shouldBeFalse()
        }

        "(c) a domain-eligible NER entity tagged as a common NOUN (cnec:op) still proposes" {
            // Live morphology tags a product name like `Octavie` NNFP4/NOUN (not PROPN), so the
            // anchored/PROPN paths miss it — but NameTag flags it `op` (object) → domain-eligible,
            // so path (c) admits it as a candidate gated against all declared types.
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .addAllTokens(
                        listOf(
                            tok("Kolik", 0, 5, "kolik", "ADV", 3, "advmod"),
                            tok("za", 6, 8, "za", "ADP", 3, "case"),
                            tok("Octavie", 9, 16, "Octavia", "NOUN", 0, "obl"),
                        ),
                    ).addEntities(
                        NerEntity
                            .newBuilder()
                            .setText("Octavie")
                            .setCharStart(9)
                            .setCharEnd(16)
                            .setLabel("MISC")
                            .setNormalizedValue("cnec:op")
                            .setSourceEngine("nametag3")
                            .build(),
                    ).build()
            val cands = SpanProposal.proposeDomainSpans(parse, listOf(branch, product))
            val octavie = cands.single { it.text == "Octavie" }
            octavie.anchored shouldBe false
            octavie.gatedEntityRefs shouldContainExactlyInAnyOrder listOf("er.branch", "er.product")
        }

        "(c) a universal NER entity (cnec:gu geo) is NOT proposed as a domain candidate" {
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .addAllTokens(listOf(tok("Praha", 0, 5, "Praha", "PROPN", 0, "root")))
                    .addEntities(
                        NerEntity
                            .newBuilder()
                            .setText("Praha")
                            .setCharStart(0)
                            .setCharEnd(5)
                            .setLabel("LOCATION")
                            .setNormalizedValue("cnec:gu")
                            .build(),
                    ).build()
            SpanProposal.proposeDomainSpans(parse, listOf(branch, product)).any { it.start < 5 && it.end > 0 } shouldBe
                false
        }

        "anchored value: `středisko` governing `QT ORLAK` proposes the value gated to QSTRED_DF only" {
            // "Zobraz středisko QT ORLAK" — 0 Zobraz(root) 1 středisko(obj,lemma) 2 QT(PROPN,flat→4)
            // 3 ORLAK(PROPN,nmod→2)
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .addAllTokens(
                        listOf(
                            tok("Zobraz", 0, 6, "zobrazit", "VERB", 0, "root"),
                            tok("středisko", 7, 16, "středisko", "NOUN", 1, "obj"),
                            tok("QT", 17, 19, "QT", "PROPN", 4, "flat"),
                            tok("ORLAK", 20, 25, "ORLAK", "PROPN", 2, "nmod"),
                        ),
                    ).build()
            val cands = SpanProposal.proposeDomainSpans(parse, listOf(qstred, branch))
            val value =
                cands.single {
                    it.text == "QT ORLAK" && it.origin == DomainSpanCandidate.Origin.GOVERNED_VALUE
                }
            value.anchored shouldBe true
            value.gatedEntityRefs shouldBe listOf("er.qstred_df")
            value.categories shouldContainExactlyInAnyOrder listOf("er.qstred_df.kod", "er.qstred_df.nazev")

            // A-MH-1b: the governed scoping above is unchanged, and the span now ALSO carries an
            // open sibling. It is not a second reading of the value — it is the question "is this
            // a value of anything?", asked in the same batch and discarded by the gate whenever
            // the governed lookup answers (`GateSpans.resolveOpenSiblings`).
            val open =
                cands.single {
                    it.text == "QT ORLAK" && it.origin == DomainSpanCandidate.Origin.OPEN_VALUE
                }
            open.anchored shouldBe false
            open.gatedEntityRefs shouldContainExactlyInAnyOrder listOf("er.qstred_df", "er.branch")
        }

        "no over-generation: a common noun that is neither an anchor nor a proper noun proposes nothing" {
            // "Zobraz záznamy" — `záznamy` is a NOUN, not a declared anchor, not PROPN.
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .addAllTokens(
                        listOf(
                            tok("Zobraz", 0, 6, "zobrazit", "VERB", 0, "root"),
                            tok("záznamy", 7, 14, "záznam", "NOUN", 1, "obj"),
                        ),
                    ).build()
            SpanProposal.proposeDomainSpans(parse, listOf(branch, product, qstred)) shouldBe emptyList()
        }

        // --- MS-P3·S1 — the same-span multi-owner merge (contracts §8.2, design.md §10.2) ----

        // One anchor word, two declared owners: the entity and its own measure attribute. This is
        // the COMMON shape for a shared anchor (`tržby`), and the single-word path is the one that
        // used to emit a candidate per owner.
        val sales =
            ResolverEntityType(
                ref = "er.entity.sales",
                categories = listOf("er.entity.sales.name"),
                anchors = listOf("tržby"),
                objectKind = "entity_with_measures",
            )
        val salesAmount =
            ResolverEntityType(
                ref = "er.entity.sales.amount_czk",
                categories = listOf("er.entity.sales.amount_czk"),
                anchors = listOf("tržby"),
                objectKind = "measure",
                ownerRef = "er.entity.sales",
            )

        // "Zobraz tržby" — 0 Zobraz(VERB, root) 1 tržby(NOUN, obj, lemma tržby)
        val sharedAnchor =
            AnalyzeResponse
                .newBuilder()
                .addAllTokens(
                    listOf(
                        tok("Zobraz", 0, 6, "zobrazit", "VERB", 0, "root"),
                        tok("tržby", 7, 12, "tržby", "NOUN", 1, "obj"),
                    ),
                ).build()

        "MS: a single-word anchor declared for two owners proposes ONE candidate carrying both" {
            val cands = SpanProposal.proposeDomainSpans(sharedAnchor, listOf(sales, salesAmount))

            val anchorPhrases = cands.filter { it.origin == DomainSpanCandidate.Origin.ANCHOR_PHRASE }
            anchorPhrases.size shouldBe 1
            val merged = anchorPhrases.single()
            merged.text shouldBe "tržby"
            merged.start shouldBe 7
            merged.end shouldBe 12
            merged.anchored shouldBe true
            // Both owners reach the gate together — the point of the merge. Gated to one of them
            // alone, the other never becomes evidence and the Binder cannot choose between them.
            merged.gatedEntityRefs shouldContainExactlyInAnyOrder
                listOf("er.entity.sales", "er.entity.sales.amount_czk")
            merged.categories shouldContainExactlyInAnyOrder
                listOf("er.entity.sales.name", "er.entity.sales.amount_czk")
        }

        // "Zobraz tržby prodejen" — 0 Zobraz(VERB, root) 1 tržby(NOUN, obj) 2 prodejen(NOUN, nmod→2)
        // `prodejen` is a nominal argument GOVERNED by the anchor: a value candidate iff the
        // anchor's owner has member vocabulary at all.
        val anchorGoverningValue =
            AnalyzeResponse
                .newBuilder()
                .addAllTokens(
                    listOf(
                        tok("Zobraz", 0, 6, "zobrazit", "VERB", 0, "root"),
                        tok("tržby", 7, 12, "tržby", "NOUN", 1, "obj"),
                        tok("prodejen", 13, 21, "prodejna", "NOUN", 2, "nmod"),
                    ),
                ).build()

        "MS: a measure-only anchor proposes NO governed value (VALUELESS_OBJECT_KINDS, real kinds)" {
            val cands = SpanProposal.proposeDomainSpans(anchorGoverningValue, listOf(salesAmount))
            cands.none { it.origin == DomainSpanCandidate.Origin.GOVERNED_VALUE } shouldBe true
            // the anchor itself still proposes — only what it governs is withheld
            cands.single { it.origin == DomainSpanCandidate.Origin.ANCHOR_PHRASE }.text shouldBe "tržby"
        }

        "MS: an entity_with_measures anchor DOES propose its governed value (it has members)" {
            val cands = SpanProposal.proposeDomainSpans(anchorGoverningValue, listOf(sales))
            val value = cands.single { it.origin == DomainSpanCandidate.Origin.GOVERNED_VALUE }
            value.text shouldBe "prodejen"
            value.gatedEntityRefs shouldBe listOf("er.entity.sales")
        }

        "MS: sharing the anchor does not suppress the entity owner's governed value" {
            // The merge is for the anchor phrase only. `prodejen` is a value of the ENTITY and of
            // nothing else: the measure contributes none, and the entity's is still emitted.
            val cands = SpanProposal.proposeDomainSpans(anchorGoverningValue, listOf(sales, salesAmount))
            val values = cands.filter { it.origin == DomainSpanCandidate.Origin.GOVERNED_VALUE }
            values.map { it.gatedEntityRefs } shouldBe listOf(listOf("er.entity.sales"))
        }

        "MS: which owner survives is no longer registry order" {
            // The defect this stage removes: `dedupe` keys on (start, end), so the per-owner loop
            // did not double-emit — it kept whichever owner the registry listed FIRST and dropped
            // the other. Reversing the registry used to reverse the answer.
            val forward = SpanProposal.proposeDomainSpans(sharedAnchor, listOf(sales, salesAmount))
            val reversed = SpanProposal.proposeDomainSpans(sharedAnchor, listOf(salesAmount, sales))

            fun refsOf(c: List<DomainSpanCandidate>) =
                c.single { it.origin == DomainSpanCandidate.Origin.ANCHOR_PHRASE }.gatedEntityRefs.sorted()
            refsOf(forward) shouldBe refsOf(reversed)
            refsOf(forward) shouldBe listOf("er.entity.sales", "er.entity.sales.amount_czk")
        }

        "MS: three owners on one anchor all reach the gate" {
            val salesQty =
                ResolverEntityType(
                    ref = "er.entity.sales.quantity",
                    categories = listOf("er.entity.sales.quantity"),
                    anchors = listOf("tržby"),
                    objectKind = "measure",
                    ownerRef = "er.entity.sales",
                )
            val cands = SpanProposal.proposeDomainSpans(sharedAnchor, listOf(sales, salesAmount, salesQty))
            cands
                .single { it.origin == DomainSpanCandidate.Origin.ANCHOR_PHRASE }
                .gatedEntityRefs shouldContainExactlyInAnyOrder
                listOf("er.entity.sales", "er.entity.sales.amount_czk", "er.entity.sales.quantity")
        }

        "MS invariant: no two ANCHOR_PHRASE candidates ever share a span (contracts §8.2)" {
            val registry = listOf(branch, product, qstred, sales, salesAmount)
            listOf(hero, sharedAnchor, anchorGoverningValue).forEach { parse ->
                val spans =
                    SpanProposal
                        .proposeDomainSpans(parse, registry)
                        .filter { it.origin == DomainSpanCandidate.Origin.ANCHOR_PHRASE }
                        .map { it.start to it.end }
                spans shouldBe spans.distinct()
            }
        }

        "R4-γ floor: a parse-less (no dep) input still yields n-gram candidates gated to all types" {
            // Degraded language: tokens present, every dep_head = 0, no NER.
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .addAllTokens(
                        listOf(
                            tok("ukazatel", 0, 8, "", "", 0, ""),
                            tok("MAJETEK", 9, 16, "", "", 0, ""),
                        ),
                    ).build()
            val cands = SpanProposal.proposeDomainSpans(parse, listOf(branch, product, qstred))
            cands shouldNotBe emptyList<DomainSpanCandidate>()
            cands.map { it.text } shouldContain "MAJETEK"
            cands.all { !it.anchored } shouldBe true
        }

        // ── MH-P3 tier M ────────────────────────────────────────────────────────────────
        //
        // A value governed by a MULTI-OWNER anchor. `prodejna` names the store dimension AND the
        // fact the Stores-channel term is pinned to, so the anchor gates to both — and before
        // A-MH-1a the governed-value loop emitted one candidate PER OWNER on the same span, of
        // which `dedupe` (keyed on `(start,end)`) kept whichever the registry listed first.
        val mhStore =
            ResolverEntityType(
                ref = "er.entity.store",
                categories = listOf("er.entity.store"),
                anchors = listOf("prodejna"),
                objectKind = "entity",
            )
        val mhStoreSales =
            ResolverEntityType(
                ref = "er.entity.store_sales",
                categories = listOf("er.entity.store_sales"),
                anchors = listOf("prodejna"),
                objectKind = "entity_with_measures",
            )
        val mhMeasure =
            ResolverEntityType(
                ref = "er.entity.store_sales.amount",
                categories = listOf("er.entity.store_sales.amount"),
                anchors = listOf("prodejna"),
                objectKind = "measure",
            )

        // "Prodejny v TN" — 0 Prodejny(root) 1 v(case→2) 2 TN(PROPN,nmod→0)
        val mhParse =
            AnalyzeResponse
                .newBuilder()
                .addAllTokens(
                    listOf(
                        tok("Prodejny", 0, 8, "prodejna", "NOUN", 0, "root"),
                        tok("v", 9, 10, "v", "ADP", 3, "case"),
                        tok("TN", 11, 13, "TN", "PROPN", 1, "nmod"),
                    ),
                ).build()

        "A-MH-1a — a governed value under a multi-owner anchor is ONE candidate gated to the union" {
            val cands = SpanProposal.proposeDomainSpans(mhParse, listOf(mhStore, mhStoreSales))

            val governed =
                cands.filter {
                    it.origin == DomainSpanCandidate.Origin.GOVERNED_VALUE && it.text == "TN"
                }
            governed.size shouldBe 1
            governed.single().anchored shouldBe true
            governed.single().gatedEntityRefs shouldContainExactlyInAnyOrder
                listOf("er.entity.store", "er.entity.store_sales")
            governed.single().categories shouldContainExactlyInAnyOrder
                listOf("er.entity.store", "er.entity.store_sales")
        }

        "A-MH-1a — and the union does not depend on the order the registry lists the owners in" {
            val forward = SpanProposal.proposeDomainSpans(mhParse, listOf(mhStore, mhStoreSales))
            val reversed = SpanProposal.proposeDomainSpans(mhParse, listOf(mhStoreSales, mhStore))

            fun governedRefs(cands: List<DomainSpanCandidate>) =
                cands
                    .single { it.origin == DomainSpanCandidate.Origin.GOVERNED_VALUE && it.text == "TN" }
                    .gatedEntityRefs
                    .sorted()

            governedRefs(forward) shouldBe governedRefs(reversed)
        }

        "A-MH-1a — a VALUELESS owner (measure) contributes nothing to the union" {
            val cands = SpanProposal.proposeDomainSpans(mhParse, listOf(mhStore, mhMeasure))

            cands
                .single { it.origin == DomainSpanCandidate.Origin.GOVERNED_VALUE && it.text == "TN" }
                .gatedEntityRefs shouldContainExactlyInAnyOrder listOf("er.entity.store")
        }

        "A-MH-1a — an anchor whose ONLY owner is valueless proposes no governed value at all" {
            val cands = SpanProposal.proposeDomainSpans(mhParse, listOf(mhMeasure))

            cands.none { it.origin == DomainSpanCandidate.Origin.GOVERNED_VALUE } shouldBe true
        }

        // ── MV-T4 (member-vocabulary contracts §5.3) — the governed lookup reaches the members ──
        //
        // A governed value is gated to `membersOf(anchor) ∪ the anchor's own categories`. Before
        // MV the categories were the anchor's own only — `er.entity.store` — and lex-matcher
        // registers `TN` under `er.entity.store.state`, so the governed lookup could never find it.

        fun governedTn(cands: List<DomainSpanCandidate>) =
            cands.single { it.origin == DomainSpanCandidate.Origin.GOVERNED_VALUE && it.text == "TN" }

        "MV-T4 — E11 `Stores in TN`: the governed value is gated to the STORE's member vocabularies" {
            val governed =
                governedTn(
                    SpanProposal.proposeDomainSpans(
                        MhMembers.parse("Stores in TN", MhMembers.e11En()),
                        MhMembers.entityTypes(),
                    ),
                )

            governed.categories shouldContainExactlyInAnyOrder
                listOf(MhMembers.STORE, MhMembers.STORE_SALES, MhMembers.STORE_STATE, MhMembers.STORE_NAME)
            // …and to nobody else's: `TN` is also a customer_address and a warehouse member, and
            // those are the readings the sentence ruled out by saying "stores"
            governed.categories shouldNotContain MhMembers.CA_STATE
            governed.categories shouldNotContain MhMembers.WAREHOUSE_STATE
            // the GATED refs are still the anchor's owners — the governor tier M reasons about
            governed.gatedEntityRefs shouldContainExactlyInAnyOrder listOf(MhMembers.STORE, MhMembers.STORE_SALES)
            governed.anchored shouldBe true
        }

        "MV-T4 — the same on the v5 archive channel, where the members carry no terms" {
            val governed =
                governedTn(
                    SpanProposal.proposeDomainSpans(
                        MhMembers.parse("Stores in TN", MhMembers.e11En()),
                        MvEstate.entityTypes(),
                    ),
                )

            governed.categories shouldContainExactlyInAnyOrder
                listOf(MvEstate.STORE, MvEstate.STORE_SALES, MvEstate.STORE_STATE, MvEstate.STORE_NAME)
        }

        "MV-T4 — an owner with no member vocabulary lends none: `Customers in TN` stays gated to customer" {
            val governed =
                governedTn(
                    SpanProposal.proposeDomainSpans(
                        MhMembers.parse("Customers in TN", MhMembers.e13En()),
                        MvEstate.entityTypes(),
                    ),
                )

            governed.categories shouldContainExactly listOf(MvEstate.CUSTOMER)
        }

        "MV-T4 — the OPEN sibling is unchanged: every declared category, members included" {
            val types = MvEstate.entityTypes()
            val open =
                SpanProposal
                    .proposeDomainSpans(MhMembers.parse("Stores in TN", MhMembers.e11En()), types)
                    .single { it.origin == DomainSpanCandidate.Origin.OPEN_VALUE && it.text == "TN" }

            open.categories shouldContainExactlyInAnyOrder types.flatMap { it.categories }.distinct()
        }

        "MV-T4 — compat §6: on a v4 archive the governed value is exactly today's" {
            val v4 = MvEstate.entityTypes(MvEstate.writeArchive(transform = MvEstate::asV4))
            val governed =
                governedTn(SpanProposal.proposeDomainSpans(MhMembers.parse("Stores in TN", MhMembers.e11En()), v4))

            governed.categories shouldContainExactly listOf(MvEstate.STORE, MvEstate.STORE_SALES)
        }

        // ⛑ hartland, 2026-09-16 — "Which portfolios does client `conseq:8801234` hold?" reached the
        // matcher as ONE phrase, "Which portfolios", was looked up against a METADATA row whose
        // term is `portfolios` with method EXACT, bound nothing, and ended the turn as
        // `I don't recognise "Which portfolios"` — with no option to offer, because nothing matched
        // the phrase and so there was nothing to sign.
        //
        // ⚑ The anchor is the FOLDED LEMMA (`fold(lemma.ifBlank { text })`), and both the lemma and
        // the anchor are `portfolios` here because that is what the live English parse produced —
        // the fixture mirrors production rather than a shape the estate does not have.
        val portfolio =
            ResolverEntityType(
                ref = "er.entity.portfolio",
                categories = listOf("er.entity.portfolio"),
                anchors = listOf("portfolios"),
            )

        // dep_head is 1-BASED (0 = root), which is what `children[headIdx + 1]` relies on.
        // 0 Which(det→portfolios) 1 portfolios(obj→hold) 2 does 3 client 4 hold(root)
        fun interrogativeParse(detFeats: Map<String, String>): AnalyzeResponse =
            AnalyzeResponse
                .newBuilder()
                .addAllTokens(
                    listOf(
                        tok("Which", 0, 5, "which", "DET", 2, "det", detFeats),
                        tok("portfolios", 6, 16, "portfolios", "NOUN", 5, "obj"),
                        tok("does", 17, 21, "do", "AUX", 5, "aux"),
                        tok("client", 22, 28, "client", "NOUN", 5, "nsubj"),
                        tok("hold", 29, 33, "hold", "VERB", 0, "root"),
                    ),
                ).build()

        // `single { … }` rather than `map { }.shouldContain`, deliberately: it THROWS when the
        // candidate is absent. The first cut of these tests asserted `none { contains("Which") }`
        // over a list that was empty because the fixture never matched an anchor at all — a
        // vacuous pass, and exactly the shape a guard test must not be able to take.
        "⛑ an INTERROGATIVE determiner is NOT part of the anchor phrase" {
            val cands =
                SpanProposal.proposeDomainSpans(interrogativeParse(mapOf("PronType" to "Int")), listOf(portfolio))
            val anchor = cands.single { it.origin == DomainSpanCandidate.Origin.ANCHOR_PHRASE }

            anchor.text shouldBe "portfolios"
            anchor.start shouldBe 6
            anchor.end shouldBe 16
            anchor.gatedEntityRefs shouldBe listOf("er.entity.portfolio")
        }

        "a NON-interrogative determiner still joins the phrase — the guard is narrow" {
            // The mutation this catches: excluding `det` wholesale. "the account" / "toho účtu"
            // must stay ONE mention, which is what the relation set exists for.
            val cands =
                SpanProposal.proposeDomainSpans(interrogativeParse(mapOf("PronType" to "Art")), listOf(portfolio))
            val anchor = cands.single { it.origin == DomainSpanCandidate.Origin.ANCHOR_PHRASE }

            anchor.text shouldBe "Which portfolios"
            anchor.start shouldBe 0
            anchor.end shouldBe 16
        }

        "Czech reports PronType=Int,Rel — the guard reads the LIST, not the whole string" {
            val cands =
                SpanProposal.proposeDomainSpans(interrogativeParse(mapOf("PronType" to "Int,Rel")), listOf(portfolio))
            val anchor = cands.single { it.origin == DomainSpanCandidate.Origin.ANCHOR_PHRASE }

            anchor.text shouldBe "portfolios"
        }
    }) {
    companion object {
        private fun tok(
            text: String,
            start: Int,
            end: Int,
            lemma: String,
            upos: String,
            depHead: Int,
            depRelation: String,
            /** UD morphological features. Defaulted and LAST, so every call above is unchanged. */
            feats: Map<String, String> = emptyMap(),
        ): Token =
            Token
                .newBuilder()
                .setText(text)
                .setCharStart(start)
                .setCharEnd(end)
                .setLemma(lemma)
                .setUpos(upos)
                .setDepHead(depHead)
                .setDepRelation(depRelation)
                .putAllFeats(feats)
                .build()

        private fun ner(
            text: String,
            start: Int,
            end: Int,
            label: String,
        ): NerEntity =
            NerEntity
                .newBuilder()
                .setText(text)
                .setCharStart(start)
                .setCharEnd(end)
                .setLabel(label)
                .build()
    }
}
