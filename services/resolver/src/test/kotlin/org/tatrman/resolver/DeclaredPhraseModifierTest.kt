// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.google.protobuf.util.JsonFormat
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.LookupResponse
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.grounding.v1.DateTimeInterval
import org.tatrman.grounding.v1.FilterRecipe
import org.tatrman.grounding.v1.GetStatusResponse
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingResult
import org.tatrman.grounding.v1.Normalized
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Capability
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.GroundingClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.model.kindsByRef
import org.tatrman.resolver.model.ownersByRef
import org.tatrman.resolver.pipeline.FrameRolePreps
import org.tatrman.resolver.pipeline.FrameRoles
import org.tatrman.resolver.pipeline.GroundingTriggers
import org.tatrman.resolver.pipeline.LatticeAssembler
import org.tatrman.resolver.pipeline.MentionLayer
import org.tatrman.resolver.pipeline.ReGate
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.pipeline.Slot
import org.tatrman.resolver.pipeline.SlotHints
import org.tatrman.resolver.pipeline.SpanProposal
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.GateRequest
import org.tatrman.resolver.v1.Hypothesis
import org.tatrman.resolver.v1.HypothesisOutcome
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.ResolveContext
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.fuzzy.v1.TargetClass as FuzzyTargetClass
import org.tatrman.resolver.v1.TargetClass as LatticeTargetClass

/**
 * ttr-server#237 — **an adjective in front of a declared phrase is never lost.**
 *
 * A declared multi-word phrase names its own extent (`tržby z tržiště`, `marketplace revenue`), so the
 * adjective in front of it was in no span: not the phrase's, and not a leftover's, since the mention
 * layer headed its leftovers at nouns. „Měsíční tržby z tržiště v roce 2025“ came back with the measure
 * and the year — no mention of „Měsíční“, no gap — and the caller answered one total for a question
 * asking for months. „Akční …“ and "Promotional …" lost their restriction the same way.
 *
 * Now such a word is a mention of its own — a determiner or a bare adjective, by the rule
 * `MentionRetry` applies (`SpanProposal.detachable`). The demo estate declares the Czech grain
 * adjectives in every case form (ttr-demo#74), so „Měsíční“ binds the month as a declared word and R6"
 * reads it as the GROUPING, as it reads `Monthly`; an estate that declares the grains only as adverbs and
 * phrases (`měsíčně`, `po měsících` — [ADVERB_ONLY]) gets the same answer from the lookup round, which
 * asks the left-out word. A left-out word binds an operator or a calendar adjective's attribute and
 * nothing else (`MentionLayer.bindsModifier`); anything else is a G1. A word that restricts nothing
 * (`celkové`, `total`, a count word) stays silent.
 *
 * **Provenance (2026-10-14).**
 *  - Parses (`declared-phrase-modifier/<question>.parse.json`): hartland's nlp, as returned (`traceId`
 *    and `elapsedMs` dropped). cs: MorphoDiTa `czech-morfflex2.0-pdtc1.0-220710` tokens, lemmas and
 *    tags, Stanza dependencies, NameTag 3 `nametag3-czech-cnec2.0-240830` entities. en: Stanza. The
 *    questions built in this file ([built]) follow the same analysers' shapes.
 *  - [CURRENT] — registry (`registry.json`): the projection `LexiconArchiveRegistrySource` builds from the
 *    demo estate's lexicon archive at ttr-demo#74 (`sha256:6f6bcb9b…`, grammar v0.15.1): the calendar
 *    grains as adverbs, phrases and (#74) Czech adjectives in every case form, the stdlib's operator
 *    words, and the store family (ttr-demo#73). Matcher rows (`matcher.json`), keyed by the folded query:
 *    the [ADVERB_ONLY] rows with every row the newer archive adds or removes applied. Which rows those are
 *    was measured by running this repository's lex-matcher in process over both archives (the live service
 *    lemmatises a query and the in-process run does not, so only the difference between the two runs is
 *    taken, never their scores). The rows for the questions this file builds are the in-process run's
 *    over the newer archive.
 *  - [ADVERB_ONLY] — the archive before #74 (`sha256:25a38870…`): its projection (`registry-adverbs.json`)
 *    and every row lex-matcher 0.11.3 over it answered to these questions, recorded live
 *    (`matcher-adverbs.json`). It declares the calendar grains as adverbs and phrases (`měsíčně`, `po
 *    měsících`, `monthly`) and no Czech grain adjective.
 *  - The grounding kernel: chrono on a four-digit year, anchored on `er.entity.date_dim.cal_date`.
 */
class DeclaredPhraseModifierTest :
    StringSpec({

        // ── the issue: a grain adjective before a declared measure phrase ─────────────────────────

        "„Měsíční tržby z tržiště v roce 2025“ — „Měsíční“ is the month GROUPING, not dropped (the issue's row)" {
            resolve(MESICNI_TRZISTE_2025).lattice.summary() shouldContainExactly
                listOf(
                    "M Měsíční → $MONTH [GROUPING]",
                    "M tržby z tržiště → $MARKETPLACE_REVENUE [SUBJECT, MEASURE]",
                    "M roce → $CHRONO [FILTER]",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
        }

        "„Měsíční tržby z tržiště“ — the same without a year" {
            val turn = resolve(MESICNI_TRZISTE)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Měsíční → $MONTH [GROUPING]",
                    "M tržby z tržiště → $MARKETPLACE_REVENUE [SUBJECT, MEASURE]",
                )
            turn.answers()
        }

        "„Vývoj měsíčních tržeb z tržiště v roce 2025“ — an inflected form reads the same" {
            resolve(MESICNICH_VYVOJ).lattice.summary() shouldContainExactly
                listOf(
                    "M Vývoj → $TREND []",
                    "M měsíčních → $MONTH [GROUPING]",
                    "M tržeb z tržiště → $MARKETPLACE_REVENUE [SUBJECT, MEASURE]",
                    "M roce → $CHRONO [FILTER]",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
        }

        "every calendar adjective the estate has a grain for reads as that grain" {
            resolve(CTVRTLETNI).lattice.mention("Čtvrtletní") shouldBe "M Čtvrtletní → $QUARTER [GROUPING]"
            resolve(ROCNI).lattice.mention("Roční") shouldBe "M Roční → $YEAR [GROUPING]"
            resolve(TYDENNI).lattice.mention("Týdenní") shouldBe "M Týdenní → $WEEK [GROUPING]"
        }

        "an estate that declares the grains only as adverbs: the round binds the left-out adjective's grain" {
            // the issue's estate (before ttr-demo#74): „Měsíční“ is in no span, the round asks it, and the
            // adverb `měsíčně` — one edit away, and the word's whole form — answers the month
            resolve(MESICNI_TRZISTE_2025, ADVERB_ONLY).lattice.summary() shouldContainExactly
                listOf(
                    "M Měsíční → $MONTH [GROUPING]",
                    "M tržby z tržiště → $MARKETPLACE_REVENUE [SUBJECT, MEASURE]",
                    "M roce → $CHRONO [FILTER]",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
            resolve(CTVRTLETNI, ADVERB_ONLY).lattice.mention("Čtvrtletní") shouldBe "M Čtvrtletní → $QUARTER [GROUPING]"
            resolve(ROCNI, ADVERB_ONLY).lattice.mention("Roční") shouldBe "M Roční → $YEAR [GROUPING]"
            // the lattice names the word the declared phrase left out, for the re-gate
            val turn = resolve(MESICNI_TRZISTE, ADVERB_ONLY)
            turn.lattice.rungLogList
                .single { it.action == LatticeAssembler.LEFT_OUT_ACTION }
                .mentionIdsList shouldContainExactly
                listOf(
                    turn.lattice.mentionsList
                        .single { it.span.text == "Měsíční" }
                        .id,
                )
        }

        "…but never through PART of a longer term: `po měsících` / `po týdnech` are no reading of the adjective" {
            // before #74 the only rows for „měsíčních“ and „Týdenní“ were the two-word phrases, matched in
            // part; the word is not their whole form, so each stays a G1 with the phrase as its hypothesis —
            // and #74, which declares every case form, is what makes them bind (the tests above)
            for (
            (question, word, phrase) in
            listOf(
                Triple(MESICNICH_VYVOJ, "měsíčních", MONTH),
                Triple(TYDENNI, "Týdenní", WEEK),
            )
            ) {
                val turn = resolve(question, ADVERB_ONLY)
                withClue(question.text) {
                    turn.lattice.mention(word) shouldBe "M $word →  []"
                    turn.lattice.gapsList.single { it.span.text == word }.let { gap ->
                        gap.kind shouldBe GapKind.GAP_KIND_G1_UNBOUND
                        gap.hypothesesTriedList.map { it.ref } shouldContainExactly listOf("V:$phrase")
                    }
                }
            }
        }

        "„Měsíční a čtvrtletní tržby z tržiště v roce 2025“ — both coordinated adjectives are the breakdown" {
            // `čtvrtletní` hangs from `Měsíční` as its conjunct, not from the noun; it is a word the phrase
            // left out all the same, and R6" reads a conjunct of an adjective as an adjective
            for (estate in listOf(CURRENT, ADVERB_ONLY)) {
                val turn = resolve(MESICNI_A_CTVRTLETNI, estate)
                withClue(if (estate === ADVERB_ONLY) "adverbs only" else "#74") {
                    turn.lattice.summary() shouldContainExactly
                        listOf(
                            "M Měsíční → $MONTH [GROUPING]",
                            "M čtvrtletní → $QUARTER [GROUPING]",
                            "M tržby z tržiště → $MARKETPLACE_REVENUE [SUBJECT, MEASURE]",
                            "M roce → $CHRONO [FILTER]",
                            "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                        )
                    turn.answers()
                }
            }
        }

        "an estate whose archive declares no kind for the month: the grain still binds and still groups" {
            // `kindsByRef` drops a blank kind; silence refuses nothing, and R6" reads the calendar word
            val turn = resolve(MESICNI_TRZISTE, ADVERB_ONLY.withoutKind(MONTH))

            turn.lattice.mention("Měsíční") shouldBe "M Měsíční → $MONTH [GROUPING]"
            turn.answers()
        }

        "an adjective that reads as an attribute it is no grain of stays a G1 — no role, so no binding" {
            // an estate that declared `akční` for the item category (a row no archive has): the round finds
            // it, but no frame rule places a non-grain adjective's attribute, and a bound mention with no
            // role is one no consumer reads — so the attribute is the gap's hypothesis, not its binding
            val akcni =
                FuzzyMatch
                    .newBuilder()
                    .setCandidateId("lex:$ITEM_CATEGORY:cs:akční")
                    .setCandidate("akční")
                    .setScore(1.0)
                    .setCategory(ITEM_CATEGORY)
                    .setSource(SourceTag.DECLARED)
                    .setTargetRef(ITEM_CATEGORY)
                    .setMatchMethod("EXACT")
                    .setTargetClass(FuzzyTargetClass.TARGET_CLASS_MODEL_OBJECT)
                    .build()
            val turn = resolve(AKCNI, CURRENT.with(mapOf("akcni" to listOf(akcni))))

            turn.lattice.mention("Akční") shouldBe "M Akční →  []"
            turn.lattice.gapsList.single().let { gap ->
                (gap.kind to gap.span.text) shouldBe (GapKind.GAP_KIND_G1_UNBOUND to "Akční")
                gap.hypothesesTriedList.map { it.ref } shouldContainExactly listOf("V:$ITEM_CATEGORY")
            }
        }

        "\"How many marketplace orders did we have in 2025?\" — the count word is the slot hint's, never a gap" {
            val turn = resolve(HOW_MANY)

            turn.lattice.mentionsList.map { it.span.text } shouldContainExactly listOf("marketplace orders")
            turn.answers()
            // …and the count still reaches the gate: the declared phrase is the counted noun's head
            val gated = SpanProposal.proposeDomainSpans(HOW_MANY.parse, entityTypes(CURRENT))
            SlotHints
                .stamp(
                    HOW_MANY.parse,
                    gated,
                    entityTypes(CURRENT).kindsByRef(),
                    entityTypes(CURRENT).ownersByRef(),
                    "en",
                    FrameRolePreps.shipped(),
                ).single { it.text == "marketplace orders" }
                .slot.slot shouldBe Slot.COUNT_HEAD
        }

        "\"How much marketplace revenue did we have in 2025?\" — an adjective with a word of its own stays out" {
            val turn = resolve(HOW_MUCH)

            turn.lattice.mentionsList.map { it.span.text } shouldContainExactly listOf("marketplace revenue")
            turn.answers()
        }

        "\"Stores with no marketplace revenue in 2025\" — a determiner that restricts is reported, never dropped" {
            // `no` hangs from the declared phrase's head as `det`; the question is the stores WITHOUT it,
            // and answering the stores with it would be the opposite question
            val turn = resolve(NO_REVENUE)

            turn.lattice.mention("no") shouldBe "M no →  []"
            // (`Stores` is the store and the store sales here, a G2 of its own; this is about `no`)
            turn.lattice.gapsList
                .single { it.span.text == "no" }
                .kind shouldBe GapKind.GAP_KIND_G1_UNBOUND
        }

        "„Čtvrtletní“ is no time-kernel trigger: a trigger row would bind it with nothing to ground" {
            // `čtvrtletní` reads like the chrono trigger `čtvrtletí`; as a trigger it would be "bound" and
            // never asked again, and the question would lose its breakdown once more
            // ttr-demo#74 declares it: it is a mention the broad pass proposes, and the stdlib's `čtvrtletí`
            // (TYPOS(1)) answers it at 0.95 in the trigger slots — so a calendar adjective is never asked there
            for (estate in listOf(CURRENT, ADVERB_ONLY)) {
                val turn = resolve(CTVRTLETNI, estate)
                withClue(if (estate === ADVERB_ONLY) "adverbs only" else "#74") {
                    turn.lattice.mentionsList
                        .single { it.span.text == "Čtvrtletní" }
                        .bindingsList
                        .map { it.ref } shouldContainExactly listOf(QUARTER)
                    turn.answers()
                }
            }
        }

        "„Pololetní“ is no trigger either: the half-year has no grain here, so it is a G1 — not a period" {
            // `pololetí` (TYPOS(1)) answers it at 0.95 as chrono's; a left-out word takes no trigger reading
            for (estate in listOf(CURRENT, ADVERB_ONLY)) {
                val turn = resolve(POLOLETNI, estate)
                withClue(if (estate === ADVERB_ONLY) "adverbs only" else "#74") {
                    turn.lattice.mention("Pololetní") shouldBe "M Pololetní →  []"
                    turn.lattice.gapsList.map { it.kind to it.span.text } shouldContainExactly
                        listOf(GapKind.GAP_KIND_G1_UNBOUND to "Pololetní")
                }
            }
        }

        "a calendar adjective the estate has no grain for is a G1, not dropped" {
            for (question in listOf(DENNI, POLOLETNI)) {
                val word = question.text.substringBefore(' ')
                val turn = resolve(question)
                withClue(question.text) {
                    turn.lattice.mention(word) shouldBe "M $word →  []"
                    turn.lattice.gapsList.map { it.kind to it.span.text } shouldContainExactly
                        listOf(GapKind.GAP_KIND_G1_UNBOUND to word)
                }
            }
        }

        // ── any adjective, any declared phrase ────────────────────────────────────────────────────

        "„Akční tržby z tržiště v roce 2025“ — an adjective that restricts is reported, never silently dropped" {
            val turn = resolve(AKCNI)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Akční →  []",
                    "M tržby z tržiště → $MARKETPLACE_REVENUE [SUBJECT, MEASURE]",
                    "M roce → $CHRONO [FILTER]",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
            turn.lattice.gapsList.map { it.kind to it.span.text } shouldContainExactly
                listOf(GapKind.GAP_KIND_G1_UNBOUND to "Akční")
        }

        "\"Promotional marketplace revenue in 2025\" — the same in English" {
            val turn = resolve(PROMOTIONAL)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Promotional →  []",
                    "M marketplace revenue → $MARKETPLACE_REVENUE [SUBJECT, MEASURE]",
                    "V 2025 GROUNDED → $CAL_DATE",
                )
            turn.lattice.gapsList.map { it.kind to it.span.text } shouldContainExactly
                listOf(GapKind.GAP_KIND_G1_UNBOUND to "Promotional")
        }

        "„Tržby podle hlavních distribučních center v roce 2025“ — beside a declared entity phrase too" {
            val turn = resolve(HLAVNICH)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Tržby → $REVENUE [SUBJECT, MEASURE]",
                    "M hlavních →  []",
                    "M distribučních center → $WAREHOUSE [GROUPING]",
                    "M roce → $CHRONO [FILTER]",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
            turn.lattice.gapsList.map { it.kind to it.span.text } shouldContainExactly
                listOf(GapKind.GAP_KIND_G1_UNBOUND to "hlavních")
        }

        "„Průměrné tržby z tržiště v roce 2025“ — a left-out operator word binds the estate's operator" {
            // a lookup round asks a left-out adjective in the operator class too: the stdlib declares
            // `průměrné` for the average (grammar v0.15.1), so the word is the average, not a gap
            val turn = resolve(PRUMERNE)

            turn.lattice.mention("Průměrné") shouldBe "M Průměrné → $AVG []"
            turn.answers()
        }

        "„Průměrné …“ on an estate with no average operator — reported, never silently summed away" {
            val turn = resolve(PRUMERNE, ADVERB_ONLY)

            turn.lattice.mention("Průměrné") shouldBe "M Průměrné →  []"
            turn.lattice.gapsList.map { it.kind to it.span.text } shouldContainExactly
                listOf(GapKind.GAP_KIND_G1_UNBOUND to "Průměrné")
        }

        "„Dnešní tržby z prodejen“ — a left-out adjective never binds a second object: the stock row is refused" {
            // the matcher reads `Dnešní` as part of `není skladem`, a term of the stock MEASURE; an
            // adjective names a property of what it modifies or an operator over it, and the row is not
            // even the word's whole form, so it is a hypothesis the round declined and the word stays a G1
            val turn = resolve(DNESNI)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Dnešní →  []",
                    "M tržby z prodejen → $STORE_REVENUE [SUBJECT, MEASURE]",
                )
            turn.lattice.gapsList.single().let { gap ->
                (gap.kind to gap.span.text) shouldBe (GapKind.GAP_KIND_G1_UNBOUND to "Dnešní")
                gap.hypothesesTriedList.map { it.ref } shouldContainExactly listOf("V:$STOCK")
            }
        }

        "„Celkové tržby z tržiště“ / \"Total marketplace revenue\" — a silent modifier stays silent" {
            for (question in listOf(CELKOVE, TOTAL)) {
                val turn = resolve(question)
                withClue(question.text) {
                    turn.lattice.mentionsList.map { it.span.text } shouldContainExactly
                        (if (question === CELKOVE) listOf("tržby z tržiště", "roce") else listOf("marketplace revenue"))
                    turn.answers()
                }
            }
        }

        // ── the controls: unchanged ─────────────────────────────────────────────────────────────

        "control: „Tržby z tržiště po měsících v roce 2025“ — the declared grain phrase, as before" {
            val turn = resolve(PO_MESICICH)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Tržby z tržiště → $MARKETPLACE_REVENUE [SUBJECT, MEASURE]",
                    "M po měsících → $MONTH [GROUPING]",
                    "M roce → $CHRONO [FILTER]",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
            turn.answers()
        }

        "control: \"Monthly marketplace revenue in 2025\" — the declared adjective, as before" {
            val turn = resolve(MONTHLY)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Monthly → $MONTH [GROUPING]",
                    "M marketplace revenue → $MARKETPLACE_REVENUE [SUBJECT, MEASURE]",
                    "V 2025 GROUNDED → $CAL_DATE",
                )
            turn.answers()
        }

        "control: „Měsíční tržby“ — with no declared phrase the adjective is in its noun's mention, as before" {
            // on the estate where the adjective is no declared word: the leftover phrase, as before #237
            val turn = resolve(MESICNI_TRZBY, ADVERB_ONLY)

            turn.lattice.mentionsList.map { it.span.text } shouldContainExactly listOf("Měsíční tržby")
            turn.lattice.gapsList.map { it.kind to it.span.text } shouldContainExactly
                listOf(GapKind.GAP_KIND_G1_UNBOUND to "Měsíční tržby")
        }

        "„Měsíční tržby“ where the adjective is a declared word (ttr-demo#74) — a mention of its own, the month" {
            resolve(MESICNI_TRZBY).lattice.mention("Měsíční") shouldBe "M Měsíční → $MONTH [GROUPING]"
        }

        // ── the rule's edges, on built parses ───────────────────────────────────────────────────

        "a declared multi-word phrase with an adjective in front never loses the adjective" {
            // the shape, engine-free: an `amod` on the head of a declared phrase, whose span is the
            // estate's words and nothing else
            val parse =
                MhMembers.parse(
                    "promotional marketplace revenue",
                    arrayOf(
                        MhMembers.tok("promotional", 0, 11, "promotional", "ADJ", 3, "amod"),
                        MhMembers.tok("marketplace", 12, 23, "marketplace", "NOUN", 3, "compound"),
                        MhMembers.tok("revenue", 24, 31, "revenue", "NOUN", 0, "root"),
                    ),
                    "en",
                )
            val gated = SpanProposal.proposeDomainSpans(parse, listOf(MARKETPLACE_REVENUE_TYPE))
            gated.map { it.text } shouldContainExactly listOf("marketplace revenue")

            leftOut(parse, gated) shouldContainExactly listOf("promotional")
        }

        "only a word a gated span left out: an adjective inside a single-word anchor's phrase stays in it" {
            val parse =
                MhMembers.parse(
                    "promotional revenue",
                    arrayOf(
                        MhMembers.tok("promotional", 0, 11, "promotional", "ADJ", 2, "amod"),
                        MhMembers.tok("revenue", 12, 19, "revenue", "NOUN", 0, "root"),
                    ),
                    "en",
                )
            val gated = SpanProposal.proposeDomainSpans(parse, listOf(REVENUE_TYPE))
            gated.map { it.text } shouldContainExactly listOf("promotional revenue")

            leftOut(parse, gated).shouldBeEmpty()
        }

        "only beside a gated span: an adjective of a leftover noun is in that noun's mention already" {
            val parse =
                MhMembers.parse(
                    "promotional widgets",
                    arrayOf(
                        MhMembers.tok("promotional", 0, 11, "promotional", "ADJ", 2, "amod"),
                        MhMembers.tok("widgets", 12, 19, "widget", "NOUN", 0, "root"),
                    ),
                    "en",
                )
            val gated = SpanProposal.proposeDomainSpans(parse, listOf(MARKETPLACE_REVENUE_TYPE))
            val mentions = MentionLayer.propose(parse, gated)
            mentions.map { it.text } shouldContainExactly listOf("promotional widgets")

            leftOut(parse, gated, mentions).shouldBeEmpty()
        }

        "silent in the question's language only: `celkové` is silent in Czech, `total` in English" {
            fun parse(
                word: String,
                lang: String,
            ) = MhMembers.parse(
                "$word marketplace revenue",
                arrayOf(
                    MhMembers.tok(word, 0, word.length, word.lowercase(), "ADJ", 3, "amod"),
                    MhMembers.tok(
                        "marketplace",
                        word.length + 1,
                        word.length + 12,
                        "marketplace",
                        "NOUN",
                        3,
                        "compound",
                    ),
                    MhMembers.tok("revenue", word.length + 13, word.length + 20, "revenue", "NOUN", 0, "root"),
                ),
                lang,
            )

            for ((word, lang) in listOf("Total" to "en", "Celkové" to "cs", "Overall" to "en")) {
                val p = parse(word, lang)
                leftOut(p, SpanProposal.proposeDomainSpans(p, listOf(MARKETPLACE_REVENUE_TYPE))).shouldBeEmpty()
            }
            // `celkové` restricts nothing in Czech; in an English question it is a word nothing knows
            val p = parse("Celkové", "en")
            leftOut(p, SpanProposal.proposeDomainSpans(p, listOf(MARKETPLACE_REVENUE_TYPE))) shouldContainExactly
                listOf("Celkové")
        }

        "a left-out adjective is never the SUBJECT, even when nothing else could be" {
            // R9 picks the shallowest mention left without a FILTER or a GROUPING; an adjective
            // modifies what the question is about, so it is not a candidate
            val parse =
                MhMembers.parse(
                    "promotional widgets",
                    arrayOf(
                        MhMembers.tok("promotional", 0, 11, "promotional", "ADJ", 2, "amod"),
                        MhMembers.tok("widgets", 12, 19, "widget", "NOUN", 0, "root"),
                    ),
                    "en",
                )

            fun roles(subject: Boolean) =
                FrameRoles
                    .derive(
                        listOf(
                            FrameRoles.Input(
                                "adj",
                                0,
                                0,
                                LatticeTargetClass.TARGET_CLASS_UNSPECIFIED,
                                "",
                                anchorsValue = false,
                                charEnd = 11,
                                subject = subject,
                            ),
                        ),
                        parse,
                        "en",
                        FrameRolePreps.shipped(),
                    ).getValue("adj")

            roles(subject = true) shouldContainExactly listOf(FrameRole.FRAME_ROLE_SUBJECT)
            roles(subject = false).shouldBeEmpty()
        }

        "a date's adjective is the date's: a universal-typed word is never a mention of its own" {
            val parse =
                MhMembers
                    .parse(
                        "Last marketplace revenue",
                        arrayOf(
                            MhMembers.tok("Last", 0, 4, "last", "ADJ", 3, "amod"),
                            MhMembers.tok("marketplace", 5, 16, "marketplace", "NOUN", 3, "compound"),
                            MhMembers.tok("revenue", 17, 24, "revenue", "NOUN", 0, "root"),
                        ),
                        "en",
                    ).toBuilder()
                    .addEntities(MhMembers.ner("Last", 0, 4, "DATE"))
                    .build()

            leftOut(parse, SpanProposal.proposeDomainSpans(parse, listOf(MARKETPLACE_REVENUE_TYPE))).shouldBeEmpty()
        }

        "the rule `MentionRetry` applies: a determiner is left out too, a silent one vanishes" {
            fun left(question: Question) =
                leftOut(
                    question.parse,
                    SpanProposal.proposeDomainSpans(question.parse, listOf(MARKETPLACE_REVENUE_TYPE)),
                )

            left(NO_REVENUE) shouldContainExactly listOf("no")
            left(ZADNE) shouldContainExactly listOf("Žádné")
            // an article and an interrogative say WHICH one, and `naše` is the estate's own; none restricts
            left(THE_REVENUE).shouldBeEmpty()
            left(NASE).shouldBeEmpty()
        }

        "a count word and an adjective with a word of its own are never left-out mentions" {
            // `How many`: `many` has `How` under it, and it is the count `SlotHints` reads; `kolik` is a count
            // word wherever it hangs
            leftOut(
                HOW_MANY.parse,
                SpanProposal.proposeDomainSpans(HOW_MANY.parse, entityTypes(CURRENT)),
            ).shouldBeEmpty()
            leftOut(
                HOW_MUCH.parse,
                SpanProposal.proposeDomainSpans(HOW_MUCH.parse, entityTypes(CURRENT)),
            ).shouldBeEmpty()
            leftOut(
                KOLIK.parse,
                SpanProposal.proposeDomainSpans(KOLIK.parse, listOf(MARKETPLACE_REVENUE_TYPE)),
            ).shouldBeEmpty()
            val preps = FrameRolePreps.shipped()
            preps.restrictsNothing("en", "many", "many") shouldBe true
            preps.restrictsNothing("cs", "Kolik", "kolik") shouldBe true
            preps.restrictsNothing("en", "no", "no") shouldBe false
        }

        "coordinated adjectives in front of a declared phrase: each conjunct is a word of its own" {
            leftOut(
                MESICNI_A_CTVRTLETNI.parse,
                SpanProposal.proposeDomainSpans(MESICNI_A_CTVRTLETNI.parse, listOf(MARKETPLACE_REVENUE_TYPE)),
            ) shouldContainExactly listOf("Měsíční", "čtvrtletní")
        }

        "R6\" reads the word, not only its lemma: an inflected form with no lemma restored is still the grain" {
            // MorphoDiTa on a question typed without diacritics can leave `mesicnich` as its own lemma, or none
            fun roles(
                text: String,
                lemma: String,
                kind: String,
            ): List<FrameRole> {
                val parse =
                    MhMembers.parse(
                        "$text trzeb",
                        arrayOf(
                            MhMembers.tok(text, 0, text.length, lemma, "ADJ", 2, "amod"),
                            MhMembers.tok("trzeb", text.length + 1, text.length + 6, "trzba", "NOUN", 0, "root"),
                        ),
                        "cs",
                    )
                return FrameRoles
                    .derive(
                        listOf(
                            FrameRoles.Input(
                                "m",
                                0,
                                0,
                                LatticeTargetClass.TARGET_CLASS_MODEL_OBJECT,
                                kind,
                                false,
                                text.length,
                            ),
                        ),
                        parse,
                        "cs",
                        FrameRolePreps.shipped(),
                    ).getValue("m")
            }

            roles("mesicnich", "", "attribute") shouldContainExactly listOf(FrameRole.FRAME_ROLE_GROUPING)
            roles("mesicnich", "mesicnich", "attribute") shouldContainExactly listOf(FrameRole.FRAME_ROLE_GROUPING)
            // a kind the registry did not declare does not keep it off; one it declared otherwise does
            roles("mesicnich", "", "") shouldContainExactly listOf(FrameRole.FRAME_ROLE_GROUPING)
            roles("mesicnich", "", "measure").shouldNotContain(FrameRole.FRAME_ROLE_GROUPING)
            // and a word that is no calendar unit is no grain however it is spelled
            roles("akcnich", "", "attribute").shouldNotContain(FrameRole.FRAME_ROLE_GROUPING)
        }

        "what a left-out word may bind: an operator, or a calendar adjective's attribute — its whole form only" {
            val preps = FrameRolePreps.shipped()
            val mesicni = MhMembers.tok("Měsíční", 0, 7, "měsíční", "ADJ", 2, "amod")
            val akcni = MhMembers.tok("Akční", 0, 5, "akční", "ADJ", 2, "amod")

            fun row(
                ref: String,
                cls: FuzzyTargetClass,
                candidate: String,
                method: String,
            ) = FuzzyMatch
                .newBuilder()
                .setTargetRef(ref)
                .setTargetClass(cls)
                .setCandidate(candidate)
                .setMatchMethod(method)
                .setScore(0.95)
                .build()
            val month = row(MONTH, FuzzyTargetClass.TARGET_CLASS_MODEL_OBJECT, "měsíčně", "TYPOS(1)")
            val attribute = mapOf(MONTH to "attribute")

            MentionLayer.bindsModifier(month, mesicni, 1, attribute, preps, "cs") shouldBe true
            // undeclared kind: refuses nothing; a declared other kind: refused
            MentionLayer.bindsModifier(month, mesicni, 1, emptyMap(), preps, "cs") shouldBe true
            MentionLayer.bindsModifier(month, mesicni, 1, mapOf(MONTH to "measure"), preps, "cs") shouldBe false
            // a non-grain adjective may not take an attribute: no rule would give it a role
            MentionLayer.bindsModifier(month, akcni, 1, attribute, preps, "cs") shouldBe false
            // part of a longer term is not the word
            val phrase = row(MONTH, FuzzyTargetClass.TARGET_CLASS_MODEL_OBJECT, "po měsících", "TOKENS")
            MentionLayer.bindsModifier(phrase, mesicni, 1, attribute, preps, "cs") shouldBe false
            // an operator, for any word; a trigger, never
            val avg = row(AVG, FuzzyTargetClass.TARGET_CLASS_OPERATOR, "průměrné", "TYPOS(1)")
            MentionLayer.bindsModifier(avg, akcni, 1, emptyMap(), preps, "cs") shouldBe true
            val trigger = row(CHRONO, FuzzyTargetClass.TARGET_CLASS_GROUNDING_TRIGGER, "měsíc", "TYPOS(1)")
            MentionLayer.bindsModifier(trigger, mesicni, 1, emptyMap(), preps, "cs") shouldBe false
        }

        "a calendar adjective in an adjective's place is never offered to the trigger annotation" {
            val parse = MESICNI_A_CTVRTLETNI.parse
            val candidates = SpanProposal.proposeDomainSpans(parse, entityTypes(CURRENT))
            val asked = GroundingTriggers.spansOf(candidates, emptyList(), parse, FrameRolePreps.shipped())

            asked.map { it.text } shouldContainExactly listOf("tržby z tržiště")
            // without the parse nothing is held back — the shape every caller had before
            GroundingTriggers.spansOf(candidates, emptyList()).map { it.text } shouldContainExactly
                listOf("Měsíční", "čtvrtletní", "tržby z tržiště")
        }

        "the re-gate holds a left-out word to the lookup round's rule" {
            // a rung proposing the stock measure for „Dnešní“ (the row the round refused) is refused here too
            val dnesni = resolve(DNESNI, ADVERB_ONLY).lattice
            regate(dnesni, "Dnešní", STOCK, ADVERB_ONLY).let { outcome ->
                outcome.accepted shouldBe false
                outcome.reason shouldBe ReGate.Reason.NOT_A_MODIFIER
            }
            // and the month for „Měsíční“ is what such a word may bind
            val mesicni = resolve(MESICNI_TRZISTE, ADVERB_ONLY).lattice
            regate(mesicni, "Měsíční", MONTH, ADVERB_ONLY).let { outcome ->
                outcome.accepted shouldBe true
                outcome.binding.ref shouldBe MONTH
            }
            // a mention that is no left-out word is gated as before: the rule is the left-out word's only
            regate(dnesni, "tržby z prodejen", STORE_SALES, ADVERB_ONLY).reason shouldNotBe ReGate.Reason.NOT_A_MODIFIER
        }
    }) {
    private class Question(
        val text: String,
        val lang: String,
        val parse: AnalyzeResponse,
    ) {
        /** A recorded question: its parse is `declared-phrase-modifier/<resource>.parse.json`. */
        constructor(text: String, lang: String, resource: String) : this(
            text,
            lang,
            AnalyzeResponse
                .newBuilder()
                .also { JsonFormat.parser().merge(resourceText("$resource.parse.json"), it) }
                .build(),
        )

        init {
            parse.tokensList.forEach { t ->
                check(text.substring(t.charStart, t.charEnd) == t.text) { "offsets of '${t.text}' in '$text'" }
            }
        }
    }

    /** One word of a [built] parse: its form, lemma, UPOS, head (1-based, 0 the root) and relation. */
    private data class W(
        val form: String,
        val lemma: String,
        val upos: String,
        val head: Int,
        val relation: String,
    )

    private class Turn(
        val response: ResolveResponse,
    ) {
        val lattice: ResolutionState get() = response.resolutionState

        /** Nothing is asked and nothing is left open. */
        fun answers() {
            withClue("gaps ${lattice.gapsList.map { it.kind to it.span.text }}") {
                response.hasAwaiting() shouldBe false
                lattice.gapsList shouldBe emptyList()
            }
        }
    }

    /**
     * One estate: its registry and what its lex-matcher answers, by folded query. [extra] rows are answered
     * beside the recorded ones — a row no archive has, for a rule's edge.
     */
    private class Estate(
        val registry: Registry,
        val rows: Map<String, List<FuzzyMatch>>,
        val extra: Map<String, List<FuzzyMatch>> = emptyMap(),
    ) {
        /** This estate with no object kind declared for [ref] — an archive that says nothing about it. */
        fun withoutKind(ref: String): Estate =
            Estate(
                registry
                    .toBuilder()
                    .also { b -> b.entityTypesBuilderList.filter { it.ref == ref }.forEach { it.clearObjectKind() } }
                    .build(),
                rows,
                extra,
            )

        fun with(more: Map<String, List<FuzzyMatch>>): Estate = Estate(registry, rows, extra + more)
    }

    /**
     * lex-matcher as recorded (see the class doc). A scoped question answers the rows whose category it
     * asked; an unscoped one answers every row, and a declared row found that way carries `unknown`, the
     * category lex-matcher stamps on a row it has no category for. A class filter keeps the rows of those
     * classes. Rows come best first, cut at the limit asked.
     */
    private class WireMatcher(
        private val estate: Estate,
    ) : FuzzyClient {
        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            val builder = BatchMatchResponse.newBuilder()
            for (span in request.spansList) {
                val rows = answer(span.query, span.categoriesList, emptyList()).take(limitOf(span.limit, 20))
                builder.addResults(FuzzyMatchResponse.newBuilder().addAllMatches(rows))
            }
            return builder.build()
        }

        override suspend fun lookup(request: LookupRequest): LookupResponse {
            val rows =
                answer(request.term, request.categoriesList, request.targetClassesList)
                    .take(limitOf(request.maxCandidates, 10))
            return LookupResponse.newBuilder().addAllCandidates(rows).build()
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()

        private fun limitOf(
            asked: Int,
            default: Int,
        ) = if (asked > 0) asked else default

        private fun answer(
            query: String,
            categories: List<String>,
            classes: List<FuzzyTargetClass>,
        ): List<FuzzyMatch> =
            (estate.rows[fold(query)].orEmpty() + estate.extra[fold(query)].orEmpty())
                .filter { categories.isEmpty() || it.category in categories }
                .filter { classes.isEmpty() || it.targetClass in classes }
                .sortedByDescending { it.score }
                .map {
                    if (categories.isEmpty() && it.source != SourceTag.MEMBER) {
                        it.toBuilder().setCategory(NO_CATEGORY).build()
                    } else {
                        it
                    }
                }
    }

    /** chrono on a four-digit year: the calendar year, on the estate's event-date column, as live. */
    private class YearKernel : GroundingClient {
        override suspend fun ground(request: GroundRequest): GroundResponse {
            val year =
                request.spanText
                    .trim()
                    .toIntOrNull()
                    ?.takeIf { it in 1900..2999 }
                    ?: return GroundResponse.newBuilder().setStatus(GroundResponse.Status.UNGROUNDABLE).build()
            return GroundResponse
                .newBuilder()
                .setStatus(GroundResponse.Status.OK)
                .setResult(
                    GroundingResult
                        .newBuilder()
                        .setNormalized(
                            Normalized.newBuilder().setInterval(
                                DateTimeInterval
                                    .newBuilder()
                                    .setStart("$year-01-01T00:00:00Z")
                                    .setEnd("${year + 1}-01-01T00:00:00Z"),
                            ),
                        ).setFilter(
                            FilterRecipe.newBuilder().setAnchorColumn(
                                QualifiedName
                                    .newBuilder()
                                    .setPackage("hartland")
                                    .setSchemaCode(SchemaCode.ER)
                                    .setNamespace("entity")
                                    .setName("date_dim.cal_date"),
                            ),
                        ),
                ).build()
        }

        override suspend fun getStatus(): GetStatusResponse = GetStatusResponse.getDefaultInstance()
    }

    private class FakeNlp(
        private val parse: AnalyzeResponse,
    ) : NlpClient {
        override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse = parse

        override suspend fun getStatus(): StatusResponse =
            StatusResponse
                .newBuilder()
                .setReady(true)
                .addCapabilities(Capability.newBuilder().setLanguage(parse.language).setOp(NlpOp.NER))
                .addCapabilities(Capability.newBuilder().setLanguage(parse.language).setOp(NlpOp.DEP_PARSE))
                .build()
    }

    private companion object {
        const val MARKETPLACE_REVENUE = "er.entity.catalog_sales.ext_sales_price"
        const val MONTH = "er.entity.date_dim.month"
        const val QUARTER = "er.entity.date_dim.quarter"
        const val YEAR = "er.entity.date_dim.year"
        const val WEEK = "er.entity.date_dim.week_seq"
        const val CAL_DATE = "er.entity.date_dim.cal_date"
        const val CHRONO = "ground:chrono"
        const val TREND = "op:trend"
        const val REVENUE = "er.entity.channel_sales.ext_sales_price"
        const val WAREHOUSE = "er.entity.warehouse"
        const val STORE_SALES = "er.entity.store_sales"
        const val STOCK = "er.entity.inventory.quantity_on_hand"
        const val STORE_REVENUE = "er.entity.store_sales.ext_sales_price"
        const val ITEM_CATEGORY = "er.entity.item.category"
        const val AVG = "op:avg"

        /** What lex-matcher writes as the category of a declared row an unscoped question found. */
        const val NO_CATEGORY = "unknown"

        val MARKETPLACE_REVENUE_TYPE =
            ResolverEntityType(
                MARKETPLACE_REVENUE,
                listOf(MARKETPLACE_REVENUE),
                listOf("marketplace revenue", "tržby z tržiště"),
                objectKind = "measure",
                ownerRef = "er.entity.catalog_sales",
            )
        val REVENUE_TYPE =
            ResolverEntityType(
                "er.entity.channel_sales.ext_sales_price",
                listOf("er.entity.channel_sales.ext_sales_price"),
                listOf("revenue", "tržby"),
                objectKind = "measure",
                ownerRef = "er.entity.channel_sales",
            )

        val MESICNI_TRZISTE_2025 = Question("Měsíční tržby z tržiště v roce 2025", "cs", "mesicni-trziste-2025")
        val MESICNI_TRZISTE = Question("Měsíční tržby z tržiště", "cs", "mesicni-trziste")
        val MESICNICH_VYVOJ = Question("Vývoj měsíčních tržeb z tržiště v roce 2025", "cs", "mesicnich-vyvoj")
        val CTVRTLETNI = Question("Čtvrtletní tržby z tržiště v roce 2025", "cs", "ctvrtletni")
        val ROCNI = Question("Roční tržby z tržiště", "cs", "rocni")
        val TYDENNI = Question("Týdenní tržby z tržiště v roce 2025", "cs", "tydenni")
        val DENNI = Question("Denní tržby z tržiště v roce 2025", "cs", "denni")
        val POLOLETNI = Question("Pololetní tržby z tržiště v roce 2025", "cs", "pololetni")
        val AKCNI = Question("Akční tržby z tržiště v roce 2025", "cs", "akcni")
        val CELKOVE = Question("Celkové tržby z tržiště v roce 2025", "cs", "celkove")
        val PO_MESICICH = Question("Tržby z tržiště po měsících v roce 2025", "cs", "po-mesicich")
        val MESICNI_TRZBY = Question("Měsíční tržby", "cs", "mesicni-trzby")
        val MONTHLY = Question("Monthly marketplace revenue in 2025", "en", "monthly")
        val PROMOTIONAL = Question("Promotional marketplace revenue in 2025", "en", "promotional")
        val TOTAL = Question("Total marketplace revenue in 2025", "en", "total")
        val HLAVNICH = Question("Tržby podle hlavních distribučních center v roce 2025", "cs", "hlavnich")
        val PRUMERNE = Question("Průměrné tržby z tržiště v roce 2025", "cs", "prumerne")
        val DNESNI = Question("Dnešní tržby z prodejen", "cs", "dnesni")

        // ── built in the analysers' shapes (see the class doc) ──────────────────────────────────

        val MESICNI_A_CTVRTLETNI =
            "Měsíční a čtvrtletní tržby z tržiště v roce 2025".let { text ->
                Question(
                    text,
                    "cs",
                    built(
                        text,
                        "cs",
                        listOf(
                            W("Měsíční", "měsíční", "ADJ", 4, "amod"),
                            W("a", "a", "CCONJ", 3, "cc"),
                            W("čtvrtletní", "čtvrtletní", "ADJ", 1, "conj"),
                            W("tržby", "tržba", "NOUN", 0, "root"),
                            W("z", "z", "ADP", 6, "case"),
                            W("tržiště", "tržiště", "NOUN", 4, "nmod"),
                            W("v", "v", "ADP", 8, "case"),
                            W("roce", "rok", "NOUN", 4, "nmod"),
                            W("2025", "2025", "NUM", 8, "nummod"),
                        ),
                        listOf(year(text, "2025")),
                    ),
                )
            }

        val HOW_MANY =
            "How many marketplace orders did we have in 2025?".let { text ->
                Question(
                    text,
                    "en",
                    built(
                        text,
                        "en",
                        listOf(
                            W("How", "how", "ADV", 2, "advmod"),
                            W("many", "many", "ADJ", 4, "amod"),
                            W("marketplace", "marketplace", "NOUN", 4, "compound"),
                            W("orders", "order", "NOUN", 7, "obj"),
                            W("did", "do", "AUX", 7, "aux"),
                            W("we", "we", "PRON", 7, "nsubj"),
                            W("have", "have", "VERB", 0, "root"),
                            W("in", "in", "ADP", 9, "case"),
                            W("2025", "2025", "NUM", 7, "obl"),
                            W("?", "?", "PUNCT", 7, "punct"),
                        ),
                        listOf(year(text, "2025")),
                    ),
                )
            }

        val HOW_MUCH =
            "How much marketplace revenue did we have in 2025?".let { text ->
                Question(
                    text,
                    "en",
                    built(
                        text,
                        "en",
                        listOf(
                            W("How", "how", "ADV", 2, "advmod"),
                            W("much", "much", "ADJ", 4, "amod"),
                            W("marketplace", "marketplace", "NOUN", 4, "compound"),
                            W("revenue", "revenue", "NOUN", 7, "obj"),
                            W("did", "do", "AUX", 7, "aux"),
                            W("we", "we", "PRON", 7, "nsubj"),
                            W("have", "have", "VERB", 0, "root"),
                            W("in", "in", "ADP", 9, "case"),
                            W("2025", "2025", "NUM", 7, "obl"),
                            W("?", "?", "PUNCT", 7, "punct"),
                        ),
                        listOf(year(text, "2025")),
                    ),
                )
            }

        val NO_REVENUE =
            "Stores with no marketplace revenue in 2025".let { text ->
                Question(
                    text,
                    "en",
                    built(
                        text,
                        "en",
                        listOf(
                            W("Stores", "store", "NOUN", 0, "root"),
                            W("with", "with", "ADP", 5, "case"),
                            W("no", "no", "DET", 5, "det"),
                            W("marketplace", "marketplace", "NOUN", 5, "compound"),
                            W("revenue", "revenue", "NOUN", 1, "nmod"),
                            W("in", "in", "ADP", 7, "case"),
                            W("2025", "2025", "NUM", 5, "nmod"),
                        ),
                        listOf(year(text, "2025")),
                    ),
                )
            }

        val ZADNE =
            "Žádné tržby z tržiště".let { text ->
                Question(
                    text,
                    "cs",
                    built(
                        text,
                        "cs",
                        listOf(
                            W("Žádné", "žádný", "DET", 2, "det"),
                            W("tržby", "tržba", "NOUN", 0, "root"),
                            W("z", "z", "ADP", 4, "case"),
                            W("tržiště", "tržiště", "NOUN", 2, "nmod"),
                        ),
                    ),
                )
            }

        val NASE =
            "Naše tržby z tržiště".let { text ->
                Question(
                    text,
                    "cs",
                    built(
                        text,
                        "cs",
                        listOf(
                            W("Naše", "náš", "DET", 2, "det"),
                            W("tržby", "tržba", "NOUN", 0, "root"),
                            W("z", "z", "ADP", 4, "case"),
                            W("tržiště", "tržiště", "NOUN", 2, "nmod"),
                        ),
                    ),
                )
            }

        val KOLIK =
            "Kolik tržeb z tržiště".let { text ->
                Question(
                    text,
                    "cs",
                    built(
                        text,
                        "cs",
                        listOf(
                            W("Kolik", "kolik", "DET", 2, "det:numgov"),
                            W("tržeb", "tržba", "NOUN", 0, "root"),
                            W("z", "z", "ADP", 4, "case"),
                            W("tržiště", "tržiště", "NOUN", 2, "nmod"),
                        ),
                    ),
                )
            }

        val THE_REVENUE =
            "Which marketplace revenue".let { text ->
                Question(
                    text,
                    "en",
                    built(
                        text,
                        "en",
                        listOf(
                            W("Which", "which", "DET", 3, "det"),
                            W("marketplace", "marketplace", "NOUN", 3, "compound"),
                            W("revenue", "revenue", "NOUN", 0, "root"),
                        ),
                    ),
                )
            }

        val REGISTRY: Registry = registryOf("registry.json")

        fun registryOf(name: String): Registry =
            Registry.newBuilder().also { JsonFormat.parser().merge(resourceText(name), it) }.build()

        /** The demo estate at ttr-demo#74 — see the class doc. */
        val CURRENT: Estate get() = Estate(REGISTRY, ROWS)

        /**
         * The demo estate before ttr-demo#74 — see the class doc: the calendar grains declared as adverbs
         * and phrases, so „Měsíční“ is a word the declared phrase left out and the lookup round asks.
         */
        val ADVERB_ONLY: Estate by lazy { Estate(registryOf("registry-adverbs.json"), rowsOf("matcher-adverbs.json")) }

        /** A parse built by hand in the analysers' shape: [words] found in [text] left to right. */
        fun built(
            text: String,
            lang: String,
            words: List<W>,
            entities: List<org.tatrman.nlp.v1.NerEntity> = emptyList(),
        ): AnalyzeResponse {
            var at = 0
            val tokens =
                words.map { w ->
                    val start = text.indexOf(w.form, at)
                    check(start >= 0) { "'${w.form}' after $at in '$text'" }
                    at = start + w.form.length
                    MhMembers.tok(w.form, start, at, w.lemma, w.upos, w.head, w.relation)
                }
            return MhMembers
                .parse(text, tokens.toTypedArray(), lang)
                .toBuilder()
                .addAllEntities(entities)
                .build()
        }

        /** A DATE entity over [year] in [text], as NameTag types a year. */
        fun year(
            text: String,
            year: String,
        ): org.tatrman.nlp.v1.NerEntity {
            val start = text.indexOf(year)
            return MhMembers.ner(year, start, start + year.length, "DATE", "cnec:ty")
        }

        /** The recorded matcher rows, by folded query. */
        val ROWS: Map<String, List<FuzzyMatch>> = rowsOf("matcher.json")

        fun rowsOf(name: String): Map<String, List<FuzzyMatch>> =
            Json.parseToJsonElement(resourceText(name)).jsonObject.mapValues { (_, rows) ->
                rows.jsonArray.map { row ->
                    FuzzyMatch.newBuilder().also { JsonFormat.parser().merge(row.toString(), it) }.build()
                }
            }

        fun resourceText(name: String): String =
            checkNotNull(DeclaredPhraseModifierTest::class.java.getResource("/declared-phrase-modifier/$name")) {
                "missing fixture $name"
            }.readText()

        fun fold(v: String): String =
            java.text.Normalizer
                .normalize(v.lowercase().trim(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")

        /** The texts of the left-out adjectives of [parse], beside [gated] and the noun leftovers. */
        fun leftOut(
            parse: AnalyzeResponse,
            gated: List<org.tatrman.resolver.pipeline.DomainSpanCandidate>,
            mentions: List<org.tatrman.resolver.pipeline.DomainSpanCandidate> = MentionLayer.propose(parse, gated),
        ): List<String> =
            MentionLayer
                .leftOutModifiers(
                    gated,
                    mentions,
                    MentionLayer.Context(parse),
                    FrameRolePreps.shipped(),
                    parse.language,
                ).map { it.text }

        /** [estate]'s registry, as the pipeline reads it. */
        fun entityTypes(estate: Estate) =
            ResolverPipeline.fromProto(estate.registry, ResolverThresholds.LIVE).entityTypes

        /** The re-gate's verdict on one hypothesis: [ref] for the mention over [text] in [lattice]. */
        fun regate(
            lattice: ResolutionState,
            text: String,
            ref: String,
            estate: Estate,
        ): HypothesisOutcome {
            val span = lattice.mentionsList.single { it.span.text == text }.span
            return runBlocking {
                ReGate
                    .run(
                        request =
                            GateRequest
                                .newBuilder()
                                .setLattice(lattice)
                                .addHypotheses(
                                    Hypothesis
                                        .newBuilder()
                                        .setSpan(span)
                                        .setRef(ref)
                                        .setProposingRung("local"),
                                ).build(),
                        fuzzy = WireMatcher(estate),
                        entityTypes = entityTypes(estate),
                        thresholds = ResolverThresholds.LIVE,
                        snapshotHash = "",
                        maxCandidates = 10,
                        maxConcurrentLookups = 4,
                        preps = FrameRolePreps.shipped(),
                    ).outcomesList
                    .single()
            }
        }

        /** One line per mention (`M text → refs [roles]`) and per value (`V text KIND → attributions ⟵ narrowing`). */
        fun ResolutionState.summary(): List<String> =
            mentionsList.map { m ->
                "M ${m.span.text} → ${m.bindingsList.joinToString { it.ref }} " +
                    m.frameRolesList.map { it.name.removePrefix("FRAME_ROLE_") }
            } +
                valuesList.map { v ->
                    "V ${v.span.text} ${v.kind.name.removePrefix("VALUE_KIND_")} → " +
                        v.attributionsList.joinToString { if (it.hasBinding()) it.binding.ref else it.attributeRef } +
                        v.grounding.ref
                            .ifBlank { null }
                            ?.let { " ⟵ $it" }
                            .orEmpty()
                }

        /** The summary line of the one mention over [text]. */
        fun ResolutionState.mention(text: String): String = summary().single { it.startsWith("M $text →") }

        fun resolve(
            question: Question,
            estate: Estate = CURRENT,
        ): Turn {
            val pipeline =
                ResolverPipeline(
                    FakeNlp(question.parse),
                    WireMatcher(estate),
                    SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), ResolverThresholds.LIVE),
                    emptyMap(),
                    ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                    grounding = YearKernel(),
                    defaultPackage = "hartland",
                )
            val response =
                runBlocking {
                    pipeline.resolve(
                        ResolveRequest
                            .newBuilder()
                            .setConversationId("declared-phrase-modifier")
                            .setFresh(FreshQuestion.newBuilder().setText(question.text).setLocale(question.lang))
                            .setContext(ResolveContext.newBuilder().setReferenceDatetime("2026-10-14T10:00:00Z"))
                            .setRegistry(estate.registry)
                            .build(),
                    )
                }
            return Turn(response)
        }
    }
}
