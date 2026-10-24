// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.Provenance
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.resolver.model.Reach
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.Bound
import org.tatrman.resolver.pipeline.Clarify
import org.tatrman.resolver.pipeline.DomainSpanCandidate
import org.tatrman.resolver.pipeline.GateSpans
import org.tatrman.resolver.pipeline.MentionRetry
import org.tatrman.resolver.pipeline.Slot
import org.tatrman.resolver.pipeline.SlotHint

/**
 * RG-P5.S1.T4 — gateSpans against a fake `BatchMatch` loaded with the Q-20
 * vocabulary shapes. Thresholds are the live ENTITIES_ONLY values.
 */
class GateSpansTest :
    StringSpec({

        val thresholds = ResolverThresholds.LIVE
        val product = ResolverEntityType("er.product", listOf("er.product"), emptyList())
        val branch = ResolverEntityType("er.branch", listOf("er.branch"), listOf("pobočka"))
        val typDokladu =
            ResolverEntityType("er.typ_dokladu", listOf("er.typ_dokladu.kod", "er.typ_dokladu.nazev"), listOf("doklad"))
        val stredisko =
            ResolverEntityType("er.stredisko", listOf("er.stredisko.kod", "er.stredisko.nazev"), listOf("středisko"))
        val ukazatel =
            ResolverEntityType("er.ukazatel", listOf("er.ukazatel.kod", "er.ukazatel.nazev"), listOf("ukazatel"))
        val allTypes = listOf(product, branch, typDokladu, stredisko, ukazatel)

        "one BatchMatch: buildBatchRequest emits one positional SpanQuery per candidate" {
            val cands =
                listOf(
                    cand("Octavie", 22, 29, listOf("er.product")),
                    cand("pražských pobočkách", 32, 51, listOf("er.branch")),
                )
            val req = GateSpans.buildBatchRequest(cands, locale = "cs", perSpanLimit = 5)
            req.spansCount shouldBe 2
            req.getSpans(0).query shouldBe "Octavie"
            req.getSpans(0).categoriesList shouldContainExactly listOf("er.product")
            req.getSpans(1).query shouldBe "pražských pobočkách"
            req.locale shouldBe "cs"
        }

        "MEMBER hit → Domain binding carrying resolved_id" {
            val cands = listOf(cand("Octavie", 22, 29, listOf("er.product")))
            val resp = batch(fmr(fm("p-octavia", "Škoda Octavia", 0.97, "er.product", SourceTag.MEMBER)))
            val outcome = GateSpans.gate(cands, resp, allTypes, thresholds, emptyMap(), "snap-1")
            val bound = outcome.shouldBeInstanceOf<Bound>()
            val b = bound.bindings.single()
            b.resolvedId shouldBe "p-octavia"
            b.targetRef.shouldBeNull()
            b.entityTypeRef shouldBe "er.product"
            b.vocabularySource shouldBe "MEMBER"
            b.snapshotHash shouldBe "snap-1"
        }

        "VOCABULARY hit → target-ref binding, no resolved_id" {
            val cands = listOf(cand("pobočkách", 42, 51, listOf("er.branch")))
            val resp =
                batch(
                    fmr(
                        fm(
                            "term-pobocka",
                            "pobočka",
                            0.88,
                            "er.branch",
                            SourceTag.VOCABULARY,
                            targetRef = "er.branch#term-pobocka",
                        ),
                    ),
                )
            val outcome = GateSpans.gate(cands, resp, allTypes, thresholds, emptyMap(), "snap-1")
            val b = outcome.shouldBeInstanceOf<Bound>().bindings.single()
            b.targetRef shouldBe "er.branch#term-pobocka"
            b.resolvedId.shouldBeNull()
            b.entityTypeRef shouldBe "er.branch"
            b.vocabularySource shouldBe "VOCABULARY"
        }

        "HP-H — a value label binds as the member the lattice spells, and names its attribute" {
            val label = "er.entity.sales.channel.marketplace"
            val cands = listOf(cand("tržiště", 8, 15, listOf(label)))
            val resp = batch(fmr(valueLabel(label, "tržiště")))
            val b =
                GateSpans
                    .gate(
                        cands,
                        resp,
                        allTypes,
                        thresholds,
                        emptyMap(),
                        "snap-1",
                    ).shouldBeInstanceOf<Bound>()
                    .bindings
                    .single()
            // The resolution names the member as the lattice does (`Bindings`), not the archive's five segments.
            b.targetRef shouldBe "er.entity.sales.channel#marketplace"
            b.entityTypeRef shouldBe "er.entity.sales.channel"
        }

        "HP-H — an ask between the store dimension and the store channel offers the channel as the member spelling" {
            // The bare store word (ttr-demo#62): the dimension and the channel's value label, both exact. A pick resumes
            // from the option's target ref, so the option must carry the member the door renders.
            val label = "er.entity.sales.channel.store"
            val cands = listOf(cand("prodejen", 6, 14, listOf("er.entity.store", label)))
            val resp =
                batch(
                    fmr(
                        fm(
                            "lex:er.entity.store",
                            "prodejen",
                            0.9999,
                            "er.entity.store",
                            SourceTag.DECLARED,
                            targetRef = "er.entity.store",
                        ),
                        valueLabel(label, "prodejen", score = 0.9999),
                    ),
                )
            val clarify =
                GateSpans
                    .gate(
                        cands,
                        resp,
                        allTypes,
                        thresholds,
                        emptyMap(),
                        "snap-1",
                    ).shouldBeInstanceOf<Clarify>()
            clarify.options.map { it.targetRef } shouldContainExactlyInAnyOrder
                listOf("er.entity.store", "er.entity.sales.channel#store")
            clarify.options.single { it.targetRef == "er.entity.sales.channel#store" }.entityTypeRef shouldBe
                "er.entity.sales.channel"
        }

        "below the bind floor (< 0.5) → no binding" {
            val cands = listOf(cand("roce", 0, 4, listOf("er.product")))
            val resp = batch(fmr(fm("x", "x", 0.40, "er.product", SourceTag.MEMBER)))
            val outcome = GateSpans.gate(cands, resp, allTypes, thresholds, emptyMap(), "snap-1")
            outcome.shouldBeInstanceOf<Bound>().bindings shouldBe emptyList()
        }

        "exact-match dominance: an exact code binds despite a lower near-name (code-vs-name)" {
            val cands = listOf(cand("FAP", 0, 3, listOf("er.typ_dokladu.kod", "er.typ_dokladu.nazev")))
            val resp =
                batch(
                    fmr(
                        fm("code-FAP", "FAP", 0.9999, "er.typ_dokladu.kod", SourceTag.MEMBER),
                        fm(
                            "name-fap",
                            "Faktura přijatá",
                            0.61,
                            "er.typ_dokladu.nazev",
                            SourceTag.VOCABULARY,
                            targetRef = "er.typ_dokladu.nazev",
                        ),
                    ),
                )
            val b =
                GateSpans
                    .gate(
                        cands,
                        resp,
                        allTypes,
                        thresholds,
                        emptyMap(),
                        "snap-1",
                    ).shouldBeInstanceOf<Bound>()
                    .bindings
                    .single()
            b.resolvedId shouldBe "code-FAP"
            b.entityTypeRef shouldBe "er.typ_dokladu"
        }

        "two DISTINCT exact matches are a genuine tie → clarify, not a silent bind" {
            // Both score at exact (0.9999) with different candidate_ids (homonymous rows):
            // exact dominance must NOT swallow this — it's a real instance ambiguity.
            val cands = listOf(cand("Praha", 0, 5, listOf("er.branch")))
            val resp =
                batch(
                    fmr(
                        fm("b-praha-1", "Praha 1", 0.9999, "er.branch", SourceTag.MEMBER),
                        fm("b-praha-6", "Praha 6", 0.9999, "er.branch", SourceTag.MEMBER),
                    ),
                )
            val clarify =
                GateSpans
                    .gate(cands, resp, allTypes, thresholds, emptyMap(), "snap-1")
                    .shouldBeInstanceOf<Clarify>()
            clarify.options.map { it.resolvedId } shouldContainExactlyInAnyOrder listOf("b-praha-1", "b-praha-6")
        }

        "exact dominance still drops a sub-exact near name (single exact contender binds)" {
            // one match at exact, a second BELOW exact but within the ambiguity gap of it:
            // the near name is dropped by the exact filter, so it binds without clarifying.
            val cands = listOf(cand("FAP", 0, 3, listOf("er.typ_dokladu.kod", "er.typ_dokladu.nazev")))
            val resp =
                batch(
                    fmr(
                        fm("code-FAP", "FAP", 0.9999, "er.typ_dokladu.kod", SourceTag.MEMBER),
                        fm("near-fap", "FAPx", 0.98, "er.typ_dokladu.kod", SourceTag.MEMBER),
                    ),
                )
            val b =
                GateSpans
                    .gate(cands, resp, allTypes, thresholds, emptyMap(), "snap-1")
                    .shouldBeInstanceOf<Bound>()
                    .bindings
                    .single()
            b.resolvedId shouldBe "code-FAP"
        }

        "entity-identity dedup: the same resolved id via two spans collapses to one binding" {
            val cands =
                listOf(
                    cand("pobočka Praha", 0, 13, listOf("er.branch")),
                    cand("Praha pobočka", 20, 33, listOf("er.branch")),
                )
            val resp =
                batch(
                    fmr(fm("b-praha", "Pražská pobočka", 0.92, "er.branch", SourceTag.MEMBER)),
                    fmr(fm("b-praha", "Pražská pobočka", 0.90, "er.branch", SourceTag.MEMBER)),
                )
            val bound =
                GateSpans
                    .gate(
                        cands,
                        resp,
                        allTypes,
                        thresholds,
                        emptyMap(),
                        "snap-1",
                    ).shouldBeInstanceOf<Bound>()
            bound.bindings shouldHaveSize 1
            bound.bindings.single().resolvedId shouldBe "b-praha"
            bound.bindings.single().score shouldBe 0.92 // the higher-scoring span wins
        }

        "instance ambiguity → AwaitingClarification with the distinct contenders (capped at maxOptions)" {
            val cands = listOf(cand("QT", 0, 2, listOf("er.stredisko.kod", "er.stredisko.nazev")))
            val resp =
                batch(
                    fmr(
                        fm("qt-orlak", "QT ORLAK", 0.72, "er.stredisko.nazev", SourceTag.MEMBER),
                        fm("qt-belus", "QT BELUS", 0.70, "er.stredisko.nazev", SourceTag.MEMBER),
                    ),
                )
            val clarify =
                GateSpans
                    .gate(
                        cands,
                        resp,
                        allTypes,
                        thresholds,
                        emptyMap(),
                        "snap-1",
                    ).shouldBeInstanceOf<Clarify>()
            clarify.options.map { it.resolvedId } shouldContainExactlyInAnyOrder listOf("qt-orlak", "qt-belus")
            clarify.options.size shouldBe 2
        }

        // --- MS-P3·S2 — the collapse, end to end through the gate (contracts §8.3) -----------

        "MS: the shared-anchor span binds the ATTRIBUTE instead of asking (G2-that-must-not-ask)" {
            // The S1 merged candidate: ONE span carrying both owners' refs and categories. Before
            // MS it reached here gated to one owner alone; now both compete, and the containment
            // collapse answers instead of clarifying.
            val sales =
                ResolverEntityType(
                    "er.entity.sales",
                    listOf("er.entity.sales.name"),
                    listOf("tržby"),
                    objectKind = "entity_with_measures",
                )
            val amount =
                ResolverEntityType(
                    "er.entity.sales.amount_czk",
                    listOf("er.entity.sales.amount_czk"),
                    listOf("tržby"),
                    objectKind = "measure",
                    ownerRef = "er.entity.sales",
                )
            val merged =
                DomainSpanCandidate(
                    "tržby",
                    7,
                    12,
                    listOf("er.entity.sales", "er.entity.sales.amount_czk"),
                    listOf("er.entity.sales.name", "er.entity.sales.amount_czk"),
                    anchored = true,
                )
            val resp =
                batch(
                    fmr(
                        fm(
                            "lex:sales",
                            "tržby",
                            1.0,
                            "er.entity.sales.name",
                            SourceTag.DECLARED,
                            targetRef = "er.entity.sales",
                        ),
                        fm(
                            "lex:amount",
                            "tržby",
                            1.0,
                            "er.entity.sales.amount_czk",
                            SourceTag.DECLARED,
                            targetRef = "er.entity.sales.amount_czk",
                        ),
                    ),
                )
            val outcome = GateSpans.gate(listOf(merged), resp, listOf(sales, amount), thresholds, emptyMap(), "snap-1")
            val bound = outcome.shouldBeInstanceOf<Bound>()
            bound.bindings shouldHaveSize 1
            bound.bindings.single().targetRef shouldBe "er.entity.sales.amount_czk"
        }

        "MS: the same span WITHOUT a declared owner still clarifies — the collapse is the only change" {
            // Identical inputs, one difference: the attribute declares no `ownerRef`, which is what
            // a pre-v3 archive serves. The gate goes back to refusing, which is the correct answer
            // when nothing declares the two objects to be one answer at two granularities.
            val sales = ResolverEntityType("er.entity.sales", listOf("er.entity.sales.name"), listOf("tržby"))
            val amount =
                ResolverEntityType(
                    "er.entity.sales.amount_czk",
                    listOf("er.entity.sales.amount_czk"),
                    listOf("tržby"),
                )
            val merged =
                DomainSpanCandidate(
                    "tržby",
                    7,
                    12,
                    listOf("er.entity.sales", "er.entity.sales.amount_czk"),
                    listOf("er.entity.sales.name", "er.entity.sales.amount_czk"),
                    anchored = true,
                )
            val resp =
                batch(
                    fmr(
                        fm(
                            "lex:sales",
                            "tržby",
                            1.0,
                            "er.entity.sales.name",
                            SourceTag.DECLARED,
                            targetRef = "er.entity.sales",
                        ),
                        fm(
                            "lex:amount",
                            "tržby",
                            1.0,
                            "er.entity.sales.amount_czk",
                            SourceTag.DECLARED,
                            targetRef = "er.entity.sales.amount_czk",
                        ),
                    ),
                )
            GateSpans
                .gate(listOf(merged), resp, listOf(sales, amount), thresholds, emptyMap(), "snap-1")
                .shouldBeInstanceOf<Clarify>()
        }

        "sibling-column: a MEMBER value on the NAZEV column also points at its KOD sibling" {
            val cands = listOf(cand("MAJETEK", 0, 7, listOf("er.ukazatel.kod", "er.ukazatel.nazev")))
            val siblings = mapOf("er.ukazatel.nazev" to listOf("er.ukazatel.kod"))
            val resp = batch(fmr(fm("majetek-1", "MAJETEK", 0.95, "er.ukazatel.nazev", SourceTag.MEMBER)))
            val b =
                GateSpans
                    .gate(
                        cands,
                        resp,
                        allTypes,
                        thresholds,
                        siblings,
                        "snap-1",
                    ).shouldBeInstanceOf<Bound>()
                    .bindings
                    .single()
            b.siblingRefs shouldContainExactly listOf("er.ukazatel.kod")
            b.entityTypeRef shouldBe "er.ukazatel"
        }
        // --- MH — the slot and the reach reach the gate, and the outcome shows it ---------------
        //
        // Everything above feeds `GateSpans.gate` candidates with NO slot, which is exactly the
        // pre-MH reading, and stays green unchanged. These feed it the hartland homonym.

        val mhStore =
            ResolverEntityType(
                ref = "er.entity.store",
                categories = listOf("er.entity.store"),
                anchors = listOf("prodejna"),
                objectKind = "entity",
                reachedFrom = listOf(Reach("er.entity.store_sales", mandatory = true)),
            )
        val mhStoreSales =
            ResolverEntityType(
                ref = "er.entity.store_sales",
                categories = listOf("er.entity.store_sales"),
                anchors = listOf("prodejna", "tržby"),
                objectKind = "entity_with_measures",
            )
        val mhTypes = listOf(mhStore, mhStoreSales)

        /** One span, two EXACT hits — the tie the Binder used to refuse on kinds alone. */
        fun mhHomonymResponse() =
            batch(
                fmr(
                    fm(
                        "lex:er.entity.store",
                        "prodejna",
                        1.0,
                        "er.entity.store",
                        SourceTag.DECLARED,
                        "er.entity.store",
                    ),
                    fm(
                        "lex:er.entity.store_sales",
                        "prodejna",
                        1.0,
                        "er.entity.store_sales",
                        SourceTag.DECLARED,
                        "er.entity.store_sales",
                    ),
                ),
            )

        fun mhCandidate(slot: SlotHint) =
            DomainSpanCandidate(
                text = "prodejna",
                start = 0,
                end = 8,
                gatedEntityRefs = listOf("er.entity.store", "er.entity.store_sales"),
                categories = listOf("er.entity.store", "er.entity.store_sales"),
                anchored = true,
                headToken = 0,
                lemma = "prodejna",
                slot = slot,
            )

        fun mhGate(
            slot: SlotHint,
            types: List<ResolverEntityType> = mhTypes,
        ) = GateSpans.gate(listOf(mhCandidate(slot)), mhHomonymResponse(), types, thresholds, emptyMap(), "snap-mh")

        "MH: a COUNT_HEAD slot binds the dimension through the real gate" {
            mhGate(SlotHint(Slot.COUNT_HEAD))
                .shouldBeInstanceOf<Bound>()
                .bindings
                .single()
                .targetRef shouldBe "er.entity.store"
        }

        "MH: a FILTER under the channel's OWN fact binds the dimension (the reach decided it)" {
            // T2 prefers the fact here; the mandatory reach flips it to the dimension. That the
            // flip happens through the REAL gate — registry maps built inside `gate`, not handed
            // in by the test — is the point of asserting it here rather than only on the Binder.
            mhGate(SlotHint(Slot.FILTER, headRefs = listOf("er.entity.store_sales"), headMeasureCapable = true))
                .shouldBeInstanceOf<Bound>()
                .bindings
                .single()
                .targetRef shouldBe "er.entity.store"
        }

        "MH: a NONE slot still asks, and each option now carries its SPECIES" {
            val clarify = mhGate(SlotHint.NONE).shouldBeInstanceOf<Clarify>()
            clarify.options shouldHaveSize 2
            clarify.options.map { it.objectKind } shouldContainExactlyInAnyOrder
                listOf("entity", "entity_with_measures")
        }

        "MH: a MEMBER option carries a BLANK kind, even when its row names an owner ref" {
            // review-087 F6. A member is a data ROW; a kind is a claim about a declared OBJECT.
            // lex-matcher leaves a member's `target_ref` empty today, but nothing forces that —
            // `BinderTest`'s own member helper sets it — and the proto comment, the KDoc and the
            // contract all promise "" here. Two members of one category is the instance
            // ambiguity that reaches a Clarify with member options (RS-26).
            val outcome =
                GateSpans.gate(
                    listOf(mhCandidate(SlotHint.NONE)),
                    batch(
                        fmr(
                            fm("store-42", "Nashville", 1.0, "er.entity.store", SourceTag.MEMBER, "er.entity.store"),
                            fm("store-43", "Knoxville", 1.0, "er.entity.store", SourceTag.MEMBER, "er.entity.store"),
                        ),
                    ),
                    mhTypes,
                    thresholds,
                    emptyMap(),
                    "snap-mh",
                )

            val clarify = outcome.shouldBeInstanceOf<Clarify>()
            clarify.options.map { it.resolvedId } shouldContainExactlyInAnyOrder listOf("store-42", "store-43")
            // Blank, even though each row names `er.entity.store` — whose kind IS declared.
            clarify.options.map { it.objectKind } shouldContainExactlyInAnyOrder listOf("", "")
        }

        "MH: a registry with no kinds and no reach is the pre-MH gate, unchanged" {
            val plainTypes =
                listOf(
                    ResolverEntityType("er.entity.store", listOf("er.entity.store"), listOf("prodejna")),
                    ResolverEntityType(
                        "er.entity.store_sales",
                        listOf("er.entity.store_sales"),
                        listOf("prodejna"),
                    ),
                )
            // The slot says "an entity" and the registry says nothing about either ref, so there
            // is nothing to prefer — and the options carry a blank kind rather than a guess.
            val clarify = mhGate(SlotHint(Slot.COUNT_HEAD), plainTypes).shouldBeInstanceOf<Clarify>()
            clarify.options shouldHaveSize 2
            clarify.options.map { it.objectKind } shouldContainExactlyInAnyOrder listOf("", "")
        }

        // ── MH-P3 tier M — A-MH-1b: the governed reading wins its own span ──────────────
        //
        // A governed value is gated to its anchor's owners. When those owners hold no such
        // member, the value must still be ASKED openly rather than silently swallowed (M2) —
        // so `SpanProposal` emits both an anchored governed candidate and an unanchored open
        // sibling for the same span, and the gate decides which one speaks.

        val storeState = ResolverEntityType("er.entity.store.state", listOf("er.entity.store.state"), emptyList())
        val caState =
            ResolverEntityType(
                "er.entity.customer_address.state",
                listOf("er.entity.customer_address.state"),
                emptyList(),
            )
        val whState =
            ResolverEntityType("er.entity.warehouse.state", listOf("er.entity.warehouse.state"), emptyList())
        val storeSales = ResolverEntityType("er.entity.store_sales", listOf("er.entity.store_sales"), listOf("sale"))
        val memberTypes = listOf(storeSales, storeState, caState, whState)

        "A-MH-1b — a governed candidate that matched NOTHING lets its open sibling speak" {
            val cands =
                listOf(
                    governedCand("TN", 9, 11, listOf("er.entity.store_sales")),
                    openCand("TN", 9, 11, memberTypes.map { it.ref }),
                )
            val resp =
                batch(
                    // the governed query: its owner holds no member `TN`
                    fmr(),
                    // the open query: three owners do
                    fmr(
                        fm("TN", "TN", 1.0, "er.entity.store.state", SourceTag.MEMBER),
                        fm("TN", "TN", 1.0, "er.entity.customer_address.state", SourceTag.MEMBER),
                        fm("TN", "TN", 1.0, "er.entity.warehouse.state", SourceTag.MEMBER),
                    ),
                )

            val outcome = GateSpans.gate(cands, resp, memberTypes, thresholds, emptyMap(), "snap-m")
            val clarify = outcome.shouldBeInstanceOf<Clarify>()
            clarify.options shouldHaveSize 3
            clarify.options.map { it.entityTypeRef } shouldContainExactlyInAnyOrder
                listOf("er.entity.store.state", "er.entity.customer_address.state", "er.entity.warehouse.state")
        }

        "A-MH-1b — a governed candidate that BOUND suppresses its open sibling entirely" {
            val cands =
                listOf(
                    governedCand("TN", 9, 11, listOf("er.entity.store.state")),
                    openCand("TN", 9, 11, memberTypes.map { it.ref }),
                )
            val resp =
                batch(
                    // the governed query found its owner's member — this is the answer
                    fmr(fm("TN", "TN", 1.0, "er.entity.store.state", SourceTag.MEMBER)),
                    // the open query found all three, and would otherwise raise a G2 over a
                    // question the governor already answered
                    fmr(
                        fm("TN", "TN", 1.0, "er.entity.store.state", SourceTag.MEMBER),
                        fm("TN", "TN", 1.0, "er.entity.customer_address.state", SourceTag.MEMBER),
                        fm("TN", "TN", 1.0, "er.entity.warehouse.state", SourceTag.MEMBER),
                    ),
                )

            val outcome = GateSpans.gate(cands, resp, memberTypes, thresholds, emptyMap(), "snap-m")
            val bound = outcome.shouldBeInstanceOf<Bound>()
            bound.bindings.single().resolvedId shouldBe "TN"
            bound.bindings.single().entityTypeRef shouldBe "er.entity.store.state"
        }

        "review-104 F3 — one value bound in two vocabularies on two spans is two bindings" {
            // `stores in TN, warehouses in TN`: since A-MV-15 both members carry the id `TN`, so a
            // binding dedupe on the id alone kept one and silently dropped the other restriction.
            val cands =
                listOf(
                    governedCand("TN", 10, 12, listOf("er.entity.store.state")),
                    governedCand("TN", 28, 30, listOf("er.entity.warehouse.state")),
                )
            val resp =
                batch(
                    fmr(fm("TN", "TN", 1.0, "er.entity.store.state", SourceTag.MEMBER)),
                    fmr(fm("TN", "TN", 1.0, "er.entity.warehouse.state", SourceTag.MEMBER)),
                )

            val bound =
                GateSpans
                    .gate(
                        cands,
                        resp,
                        memberTypes,
                        thresholds,
                        emptyMap(),
                        "snap-m",
                    ).shouldBeInstanceOf<Bound>()

            bound.bindings.map { it.memberOf } shouldContainExactlyInAnyOrder
                listOf("er.entity.store.state", "er.entity.warehouse.state")
        }

        "A-MH-1b — the suppression is per SPAN: another span's open candidate is untouched" {
            val cands =
                listOf(
                    governedCand("TN", 9, 11, listOf("er.entity.store.state")),
                    openCand("TN", 9, 11, memberTypes.map { it.ref }),
                    openCand("Nashville", 20, 29, memberTypes.map { it.ref }),
                )
            val resp =
                batch(
                    fmr(fm("TN", "TN", 1.0, "er.entity.store.state", SourceTag.MEMBER)),
                    fmr(fm("TN", "TN", 1.0, "er.entity.store.state", SourceTag.MEMBER)),
                    fmr(
                        fm("n#1", "Nashville", 1.0, "er.entity.store.state", SourceTag.MEMBER),
                        fm("n#2", "Nashville", 1.0, "er.entity.warehouse.state", SourceTag.MEMBER),
                    ),
                )

            val outcome = GateSpans.gate(cands, resp, memberTypes, thresholds, emptyMap(), "snap-m")
            val clarify = outcome.shouldBeInstanceOf<Clarify>()
            clarify.options.map { it.spanText }.distinct() shouldContainExactly listOf("Nashville")
        }

        "T4 — a MEMBER option names its OWNER; a VOCABULARY option does not" {
            val cands = listOf(openCand("TN", 9, 11, memberTypes.map { it.ref }))
            val resp =
                batch(
                    fmr(
                        fm("TN", "TN", 1.0, "er.entity.store.state", SourceTag.MEMBER),
                        fm("TN", "TN", 1.0, "er.entity.customer_address.state", SourceTag.MEMBER),
                    ),
                )

            val clarify =
                GateSpans
                    .gate(cands, resp, memberTypes, thresholds, emptyMap(), "snap-m")
                    .shouldBeInstanceOf<Clarify>()

            clarify.options.map { it.memberOf } shouldContainExactlyInAnyOrder
                listOf("er.entity.store.state", "er.entity.customer_address.state")
            // and the species stays blank on a member, review-087 F6
            clarify.options.map { it.objectKind }.distinct() shouldContainExactly listOf("")
        }

        "T4 — a VOCABULARY tie leaves member_of empty and keeps naming its species" {
            val cands = listOf(cand("doklad", 0, 6, listOf("er.typ_dokladu.kod", "er.ukazatel.kod")))
            val resp =
                batch(
                    fmr(
                        fm(
                            "t1",
                            "doklad",
                            1.0,
                            "er.typ_dokladu.kod",
                            SourceTag.VOCABULARY,
                            targetRef = "er.typ_dokladu",
                        ),
                        fm("t2", "doklad", 1.0, "er.ukazatel.kod", SourceTag.VOCABULARY, targetRef = "er.ukazatel"),
                    ),
                )

            val clarify =
                GateSpans
                    .gate(cands, resp, allTypes, thresholds, emptyMap(), "snap-1")
                    .shouldBeInstanceOf<Clarify>()

            clarify.options.map { it.memberOf }.distinct() shouldContainExactly listOf("")
            clarify.options.map { it.targetRef } shouldContainExactlyInAnyOrder
                listOf("er.typ_dokladu", "er.ukazatel")
        }

        // ── ttr-server#204 — a mention's second lookup, as its head ─────────────────────────────

        val channel = ResolverEntityType("er.channel", listOf("er.channel"), listOf("marketplace"))
        val head = fm("t-marketplace", "marketplace", 1.0, "er.channel", SourceTag.VOCABULARY, targetRef = "er.channel")

        "#204 — a phrase that bound NOTHING is answered by its head" {
            val cands = listOf(cand("the marketplace", 0, 15, listOf("er.channel")))
            val outcome =
                GateSpans.gate(
                    cands,
                    batch(fmr()),
                    listOf(channel),
                    thresholds,
                    emptyMap(),
                    "snap-1",
                    retries = mapOf(0 to MentionRetry.Second(listOf(head), cands[0])),
                )

            val b = outcome.shouldBeInstanceOf<Bound>().bindings.single()
            b.targetRef shouldBe "er.channel"
            b.rawText shouldBe "the marketplace"
        }

        "#204 — the head's answer is recorded on the mention it binds as: its own words, when one surfaces" {
            val cands = listOf(cand("promotional marketplace", 0, 23, listOf("er.channel")))
            val narrowed = cands[0].copy(text = "marketplace", start = 12, end = 23)
            val outcome =
                GateSpans.gate(
                    cands,
                    batch(fmr()),
                    listOf(channel),
                    thresholds,
                    emptyMap(),
                    "snap-1",
                    retries = mapOf(0 to MentionRetry.Second(listOf(head), narrowed)),
                )

            val b = outcome.shouldBeInstanceOf<Bound>().bindings.single()
            b.rawText shouldBe "marketplace"
            outcome.gated.single().candidate shouldBe narrowed
        }

        "#204 — a phrase that bound keeps its own answer, and its head is never read" {
            val cands = listOf(cand("the marketplace", 0, 15, listOf("er.channel", "er.other")))
            val own = fm("t-own", "the marketplace", 1.0, "er.other", SourceTag.VOCABULARY, targetRef = "er.other")
            val outcome =
                GateSpans.gate(
                    cands,
                    batch(fmr(own)),
                    listOf(channel, ResolverEntityType("er.other", listOf("er.other"), emptyList())),
                    thresholds,
                    emptyMap(),
                    "snap-1",
                    retries = mapOf(0 to MentionRetry.Second(listOf(head), cands[0])),
                )

            outcome
                .shouldBeInstanceOf<Bound>()
                .bindings
                .single()
                .targetRef shouldBe "er.other"
        }

        "#204 — a phrase that is AMBIGUOUS on its own still asks: its head does not settle it" {
            val cands = listOf(cand("the doklad", 0, 10, listOf("er.typ_dokladu.kod", "er.ukazatel.kod")))
            val tie =
                fmr(
                    fm("t1", "doklad", 1.0, "er.typ_dokladu.kod", SourceTag.VOCABULARY, targetRef = "er.typ_dokladu"),
                    fm("t2", "doklad", 1.0, "er.ukazatel.kod", SourceTag.VOCABULARY, targetRef = "er.ukazatel"),
                )
            val single =
                fm("t1", "doklad", 1.0, "er.typ_dokladu.kod", SourceTag.VOCABULARY, targetRef = "er.typ_dokladu")
            val outcome =
                GateSpans.gate(
                    cands,
                    batch(tie),
                    allTypes,
                    thresholds,
                    emptyMap(),
                    "snap-1",
                    retries = mapOf(0 to MentionRetry.Second(listOf(single), cands[0])),
                )

            outcome.shouldBeInstanceOf<Clarify>().options shouldHaveSize 2
        }
    }) {
    companion object {
        private fun governedCand(
            text: String,
            start: Int,
            end: Int,
            refs: List<String>,
        ) = DomainSpanCandidate(
            text,
            start,
            end,
            refs,
            refs,
            anchored = true,
            origin = DomainSpanCandidate.Origin.GOVERNED_VALUE,
        )

        private fun openCand(
            text: String,
            start: Int,
            end: Int,
            refs: List<String>,
        ) = DomainSpanCandidate(
            text,
            start,
            end,
            refs,
            refs,
            anchored = false,
            origin = DomainSpanCandidate.Origin.OPEN_VALUE,
        )

        private fun cand(
            text: String,
            start: Int,
            end: Int,
            categories: List<String>,
        ) = DomainSpanCandidate(text, start, end, categories, categories, anchored = true)

        private fun fm(
            id: String,
            candidate: String,
            score: Double,
            category: String,
            source: SourceTag,
            targetRef: String = "",
            method: String = "TATRMAN",
        ): FuzzyMatch {
            val b =
                FuzzyMatch
                    .newBuilder()
                    .setCandidateId(id)
                    .setCandidate(candidate)
                    .setScore(score)
                    .setCategory(category)
                    .setSource(source)
                    .setProvenance(
                        Provenance
                            .newBuilder()
                            .setProducer("fuzzy")
                            .setMethod(method)
                            .setRawScore(score),
                    )
            if (targetRef.isNotBlank()) b.targetRef = targetRef
            return b.build()
        }

        /** A value label as lex-matcher serves it: METADATA, classed MEMBER, its archive ref as target and category. */
        private fun valueLabel(
            archiveRef: String,
            text: String,
            score: Double = 1.0,
        ): FuzzyMatch =
            fm("lex:$archiveRef", text, score, archiveRef, SourceTag.METADATA, targetRef = archiveRef)
                .toBuilder()
                .setTargetClass(org.tatrman.fuzzy.v1.TargetClass.TARGET_CLASS_MEMBER)
                .build()

        private fun fmr(vararg matches: FuzzyMatch): FuzzyMatchResponse =
            FuzzyMatchResponse.newBuilder().addAllMatches(matches.toList()).build()

        private fun batch(vararg results: FuzzyMatchResponse): BatchMatchResponse =
            BatchMatchResponse.newBuilder().addAllResults(results.toList()).build()
    }
}
