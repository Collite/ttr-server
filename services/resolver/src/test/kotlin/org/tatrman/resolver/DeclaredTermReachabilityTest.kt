// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.pipeline.MentionLayer
import org.tatrman.resolver.pipeline.SpanProposal

/**
 * **Two ways a correctly-declared term could not reach the lattice**, found by reading a live
 * lattice against the estate's own lexicon.
 *
 * Both are matcher-side: in each case the estate authored the term correctly and *nothing in the
 * pipeline could ever produce the span it is keyed on*. They are independent of whether the
 * declared vocabulary is fed at all — the first one reproduces with an EMPTY registry, which is
 * why it went unnoticed while the registry was empty.
 *
 *  - **(1) a proper-noun modifier ate its own phrase.** `Marketplace revenue` never formed,
 *    because `SpanProposal`'s PROPN branch claims `Marketplace` as a candidate of its own and
 *    `MentionLayer` then refuses to fold a *claimed* token into a neighbour's phrase. The head
 *    noun was left alone, and a bare measure word is ambiguous on any estate that declines to
 *    declare bare measure words. **Lowercase the same word and it binds** — the common-noun
 *    tagging skips the PROPN branch entirely.
 *  - **(2) a multi-word anchor could not be matched.** The anchor index was keyed on a single
 *    folded token, so `by month` — or any two-word declared term — could not be a key any token
 *    matches. An estate can author it exactly right and nothing will ever see it.
 */
private fun tok(
    text: String,
    start: Int,
    end: Int,
    lemma: String,
    upos: String,
    depHead: Int,
    depRelation: String,
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
        .build()

private fun parse(vararg tokens: Token): AnalyzeResponse =
    AnalyzeResponse.newBuilder().addAllTokens(tokens.toList()).build()

class DeclaredTermReachabilityTest :
    StringSpec({

        // ── (1) the proper-noun modifier ──────────────────────────────────────────────

        // "Why did Marketplace revenue drop in 2025?" — the live parse, verbatim.
        // 0 Why(advmod→5) 1 did(aux→5) 2 Marketplace(PROPN,compound→4) 3 revenue(NOUN,nsubj→5)
        // 4 drop(VERB,root) 5 in(case→7) 6 2025(NUM,obl→5) 7 ?
        val capitalised =
            parse(
                tok("Why", 0, 3, "why", "ADV", 5, "advmod"),
                tok("did", 4, 7, "do", "AUX", 5, "aux"),
                tok("Marketplace", 8, 19, "Marketplace", "PROPN", 4, "compound"),
                tok("revenue", 20, 27, "revenue", "NOUN", 5, "nsubj"),
                tok("drop", 28, 32, "drop", "VERB", 0, "root"),
                tok("in", 33, 35, "in", "ADP", 7, "case"),
                tok("2025", 36, 40, "2025", "NUM", 5, "obl"),
                tok("?", 40, 41, "?", "PUNCT", 5, "punct"),
            )

        // The same question with the channel written as a common noun — the phrasing that
        // already worked. Kept as the CONTROL: the fix must make the two agree, not make the
        // capitalised one merely different.
        val lowercased =
            parse(
                tok("Why", 0, 3, "why", "ADV", 5, "advmod"),
                tok("did", 4, 7, "do", "AUX", 5, "aux"),
                tok("marketplace", 8, 19, "marketplace", "NOUN", 4, "compound"),
                tok("revenue", 20, 27, "revenue", "NOUN", 5, "nsubj"),
                tok("drop", 28, 32, "drop", "VERB", 0, "root"),
                tok("in", 33, 35, "in", "ADP", 7, "case"),
                tok("2025", 36, 40, "2025", "NUM", 5, "obl"),
                tok("?", 40, 41, "?", "PUNCT", 5, "punct"),
            )

        fun mentions(p: AnalyzeResponse): List<String> {
            val gated = SpanProposal.proposeDomainSpans(p, emptyList())
            return MentionLayer.propose(p, gated).map { it.text }
        }

        "⚑ a PROPN compound modifier no longer eats the phrase it modifies" {
            withClue("the whole term is what the estate declares; the head noun alone is ambiguous") {
                mentions(capitalised) shouldContain "Marketplace revenue"
            }
        }

        "capitalisation does not change what reaches the gate" {
            // The defect in one assertion. ⚑ Compared over BOTH layers, not just mentions: the
            // two-layer model (RV-2) routes the same word differently by tag — a proper noun is a
            // span-proposal candidate, a common noun is a leftover mention — and both reach the
            // one BatchMatch. Comparing mentions alone would assert that the layers agree, which
            // they are designed not to. What must agree is the set of SPANS the estate gets asked
            // about, and before the fix that set was missing `Marketplace revenue` entirely.
            fun asked(p: AnalyzeResponse): List<String> {
                val gated = SpanProposal.proposeDomainSpans(p, emptyList())
                return (gated + MentionLayer.propose(p, gated)).map { it.text.lowercase() }.distinct()
            }
            asked(capitalised) shouldContainExactlyInAnyOrder asked(lowercased)
        }

        "the proper noun SURVIVES as its own candidate — it is still a filter value" {
            // The narrow fix must not be "stop proposing proper nouns". `Marketplace` names the
            // channel and is a legitimate value; losing it would trade one binding for another.
            SpanProposal
                .proposeDomainSpans(capitalised, emptyList())
                .map { it.text } shouldContain "Marketplace"
        }

        "a standalone proper noun is untouched — no phrase to be part of" {
            // 0 Show(VERB,root) 1 Memphis(PROPN,obj→1)
            val p =
                parse(
                    tok("Show", 0, 4, "show", "VERB", 0, "root"),
                    tok("Memphis", 5, 12, "Memphis", "PROPN", 1, "obj"),
                )
            SpanProposal.proposeDomainSpans(p, emptyList()).map { it.text } shouldContain "Memphis"
        }

        // ── (2) multi-word anchors ────────────────────────────────────────────────────

        val dateDim =
            ResolverEntityType(
                ref = "er.entity.date_dim.month",
                categories = listOf("er.entity.date_dim.month"),
                // Authored as a PHRASE on purpose: a bare `month` is already a chrono grounding
                // trigger, and declaring it twice would put two classes in competition on one span.
                anchors = listOf("by month", "monthly"),
            )
        val sales =
            ResolverEntityType(
                ref = "er.entity.catalog_sales.ext_sales_price",
                categories = listOf("er.entity.catalog_sales.ext_sales_price"),
                anchors = listOf("marketplace revenue"),
            )

        // "Show me marketplace revenue by month" —
        // 0 Show(root) 1 me(iobj→1) 2 marketplace(NOUN,compound→4) 3 revenue(NOUN,obj→1)
        // 4 by(ADP,case→6) 5 month(NOUN,nmod→4)
        val byMonth =
            parse(
                tok("Show", 0, 4, "show", "VERB", 0, "root"),
                tok("me", 5, 7, "I", "PRON", 1, "iobj"),
                tok("marketplace", 8, 19, "marketplace", "NOUN", 4, "compound"),
                tok("revenue", 20, 27, "revenue", "NOUN", 1, "obj"),
                tok("by", 28, 30, "by", "ADP", 6, "case"),
                tok("month", 31, 36, "month", "NOUN", 4, "nmod"),
            )

        "⚑ a TWO-WORD anchor is matched, and proposes the span it actually covers" {
            val cands = SpanProposal.proposeDomainSpans(byMonth, listOf(dateDim, sales))
            val grain = cands.single { it.text == "by month" }

            grain.anchored shouldBe true
            grain.gatedEntityRefs shouldBe listOf("er.entity.date_dim.month")
            withClue("the span must cover BOTH tokens — a phrase anchor that proposes one word is the old bug") {
                grain.start shouldBe 28
                grain.end shouldBe 36
            }
        }

        "a two-word anchor crossing a preposition is why this is not a phrase-relation fix" {
            // `by` attaches with `case`, which is not a phrase relation and must not become one:
            // adding it would drag prepositions into every leftover mention. The anchor index is
            // the right place, because the estate has NAMED this exact word sequence.
            MentionLayer
                .propose(byMonth, SpanProposal.proposeDomainSpans(byMonth, listOf(dateDim, sales)))
                .map { it.text }
                .forEach {
                    withClue("leftover phrases must not start swallowing prepositions") {
                        it.startsWith("by ") shouldBe
                            false
                    }
                }
        }

        "⛑ a multi-word anchor's HEAD is the syntactic head, not the first word" {
            // Found live, on the first drill after the registry was fed. The span was right and
            // the binding was right, and every FRAME ROLE was wrong — because `headToken` drives
            // the deprel rules and the mention's lemma, and a phrase's first word is usually a
            // modifier (`marketplace revenues`) or a preposition (`by month`).
            //
            // The live lattice: `marketplace revenues` — the MEASURE — came back FILTER, because
            // its head was `marketplace`, whose deprel is `compound`, so R5 fired. And `by month`
            // — the GROUPING — came back SUBJECT, because its head was `by`. Composition on that
            // reads "select date_dim.month filtered by ext_sales_price": nonsense, confidently.
            val cands = SpanProposal.proposeDomainSpans(byMonth, listOf(dateDim, sales))

            val grain = cands.single { it.text == "by month" }
            withClue("the head of `by month` is `month`, not the preposition") {
                byMonth.tokensList[grain.headToken].text shouldBe "month"
                grain.lemma shouldBe "month"
            }

            val measure = cands.single { it.text == "marketplace revenue" }
            withClue("the head of `marketplace revenue` is the noun it is about") {
                byMonth.tokensList[measure.headToken].text shouldBe "revenue"
                measure.lemma shouldBe "revenue"
            }
        }

        "the longest anchor wins when two overlap" {
            val overlapping =
                ResolverEntityType(
                    ref = "er.entity.date_dim.month",
                    categories = listOf("er.entity.date_dim.month"),
                    anchors = listOf("by month", "by month end"),
                )
            val p =
                parse(
                    tok("by", 0, 2, "by", "ADP", 3, "case"),
                    tok("month", 3, 8, "month", "NOUN", 0, "root"),
                    tok("end", 9, 12, "end", "NOUN", 2, "compound"),
                )
            SpanProposal
                .proposeDomainSpans(p, listOf(overlapping))
                .filter { it.anchored }
                .map { it.text } shouldContain "by month end"
        }

        "a single-word anchor still behaves exactly as before" {
            // The regression guard. Q-20's anchored path is the precision path, and this change
            // must not widen it: one-word anchors take the same route they always did.
            val branch =
                ResolverEntityType(ref = "er.branch", categories = listOf("er.branch"), anchors = listOf("pobočka"))
            val p =
                parse(
                    tok("v", 0, 1, "v", "ADP", 3, "case"),
                    tok("pražských", 2, 11, "pražský", "ADJ", 3, "amod"),
                    tok("pobočkách", 12, 21, "pobočka", "NOUN", 0, "root"),
                )
            val cands = SpanProposal.proposeDomainSpans(p, listOf(branch))
            val branchCand = cands.single { it.text == "pražských pobočkách" }
            branchCand.anchored shouldBe true
            branchCand.gatedEntityRefs shouldBe listOf("er.branch")
        }

        "an anchor phrase whose words are not contiguous does NOT match" {
            // "revenue by marketplace" is not "marketplace revenue". A bag-of-words anchor would
            // bind the wrong object confidently, which is the failure this whole layer avoids.
            val p =
                parse(
                    tok("revenue", 0, 7, "revenue", "NOUN", 0, "root"),
                    tok("by", 8, 10, "by", "ADP", 3, "case"),
                    tok("marketplace", 11, 22, "marketplace", "NOUN", 1, "nmod"),
                )
            SpanProposal
                .proposeDomainSpans(p, listOf(sales))
                .none { it.anchored && it.text == "marketplace revenue" } shouldBe true
        }

        // ── (3) a declared phrase covers the words inside it ──────────────────────────

        // hartland, 2026-10-01: the demo's headline question asked "I don't recognise "the revenues"".
        // The demo estate (2026-09-24) declared the bare word (`revenue` → the all-channel `channel_sales`) and
        // relied, in its own comment, on "the longer span wins". It did only where both anchors
        // START on the same word. Here the bare word is the HEAD of the declared phrase, so it
        // started an anchor phrase of its own: `det` folded `the` in, the sibling anchor
        // `marketplace` was left out, and the hull `the [marketplace] revenues` went to the gate
        // against `channel_sales` — matched nothing, gapped G1, and the turn asked.
        val channelRevenue =
            ResolverEntityType(
                ref = "er.entity.channel_sales.ext_sales_price",
                categories = listOf("er.entity.channel_sales.ext_sales_price"),
                anchors = listOf("revenue", "revenues"),
                objectKind = "measure",
            )
        val marketplaceRevenue = sales.copy(objectKind = "measure")

        // "What are the marketplace revenues for 2025 by month?" — the live Stanza parse, verbatim.
        // 0 What(PRON,root) 1 are(cop→1) 2 the(det→5) 3 marketplace(NOUN,compound→5)
        // 4 revenues(NOUN,nsubj→1) 5 for(case→7) 6 2025(NUM,nmod→5) 7 by(case→9) 8 month(nmod→5) 9 ?
        val headline =
            parse(
                tok("What", 0, 4, "what", "PRON", 0, "root"),
                tok("are", 5, 8, "be", "AUX", 1, "cop"),
                tok("the", 9, 12, "the", "DET", 5, "det"),
                tok("marketplace", 13, 24, "marketplace", "NOUN", 5, "compound"),
                tok("revenues", 25, 33, "revenue", "NOUN", 1, "nsubj"),
                tok("for", 34, 37, "for", "ADP", 7, "case"),
                tok("2025", 38, 42, "2025", "NUM", 5, "nmod"),
                tok("by", 43, 45, "by", "ADP", 9, "case"),
                tok("month", 46, 51, "month", "NOUN", 5, "nmod"),
                tok("?", 51, 52, "?", "PUNCT", 1, "punct"),
            ).toBuilder()
                // …and Stanza's one entity: `2025` is a DATE, so a universal, and no literal run.
                .addEntities(
                    NerEntity
                        .newBuilder()
                        .setText("2025")
                        .setLabel("DATE")
                        .setCharStart(38)
                        .setCharEnd(42)
                        .setSourceEngine("stanza"),
                ).build()

        "⛑ a word inside a declared phrase is not proposed again on its own" {
            val cands = SpanProposal.proposeDomainSpans(headline, listOf(dateDim, marketplaceRevenue, channelRevenue))
            withClue("the bare word's owner must not be asked about a word the longer phrase already names") {
                cands.none { "er.entity.channel_sales.ext_sales_price" in it.gatedEntityRefs } shouldBe true
            }
            val measure = cands.single { "er.entity.catalog_sales.ext_sales_price" in it.gatedEntityRefs }
            measure.text shouldBe "marketplace revenues"
            measure.start shouldBe 13
            withClue("nothing reaches the gate or the mention layer as `the revenues`") {
                (cands + MentionLayer.propose(headline, cands)).none { it.start == 9 } shouldBe true
            }
        }

        "the bare word still anchors wherever no declared phrase covers it" {
            // The control: "What are the revenues by month?" is the all-channel question.
            // 0 What(root) 1 are(cop→1) 2 the(det→4) 3 revenues(nsubj→1) 4 by(case→6) 5 month(nmod→4) 6 ?
            val bare =
                parse(
                    tok("What", 0, 4, "what", "PRON", 0, "root"),
                    tok("are", 5, 8, "be", "AUX", 1, "cop"),
                    tok("the", 9, 12, "the", "DET", 4, "det"),
                    tok("revenues", 13, 21, "revenue", "NOUN", 1, "nsubj"),
                    tok("by", 22, 24, "by", "ADP", 6, "case"),
                    tok("month", 25, 30, "month", "NOUN", 4, "nmod"),
                    tok("?", 30, 31, "?", "PUNCT", 1, "punct"),
                )
            val cands = SpanProposal.proposeDomainSpans(bare, listOf(dateDim, marketplaceRevenue, channelRevenue))
            val measure = cands.single { "er.entity.channel_sales.ext_sales_price" in it.gatedEntityRefs }
            bare.tokensList[measure.headToken].text shouldBe "revenues"
        }

        "a declared phrase inside a LONGER declared phrase yields to it" {
            // The same rule one size up: `web sales revenue` names its object; `sales revenue`
            // inside it is not a second mention.
            val longer =
                ResolverEntityType(
                    ref = "er.entity.web_sales.ext_sales_price",
                    categories = listOf("er.entity.web_sales.ext_sales_price"),
                    anchors = listOf("web sales revenue"),
                )
            val inner =
                ResolverEntityType(
                    ref = "er.entity.sales.revenue",
                    categories = listOf("er.entity.sales.revenue"),
                    anchors = listOf("sales revenue"),
                )
            // 0 web(compound→3) 1 sales(compound→3) 2 revenue(root)
            val p =
                parse(
                    tok("web", 0, 3, "web", "NOUN", 3, "compound"),
                    tok("sales", 4, 9, "sales", "NOUN", 3, "compound"),
                    tok("revenue", 10, 17, "revenue", "NOUN", 0, "root"),
                )
            SpanProposal
                .proposeDomainSpans(p, listOf(longer, inner))
                .filter { it.anchored }
                .map { it.text } shouldBe listOf("web sales revenue")
        }

        "two declared phrases that only OVERLAP both stand — that is the gate's to settle" {
            // Neither phrase contains the other, so neither covers the other: `marketplace
            // revenue growth` may be read either way, and this layer does not pick.
            val growth =
                ResolverEntityType(
                    ref = "er.entity.catalog_sales.revenue_growth",
                    categories = listOf("er.entity.catalog_sales.revenue_growth"),
                    anchors = listOf("revenue growth"),
                )
            // 0 marketplace(compound→3) 1 revenue(compound→3) 2 growth(root)
            val p =
                parse(
                    tok("marketplace", 0, 11, "marketplace", "NOUN", 3, "compound"),
                    tok("revenue", 12, 19, "revenue", "NOUN", 3, "compound"),
                    tok("growth", 20, 26, "growth", "NOUN", 0, "root"),
                )
            SpanProposal
                .proposeDomainSpans(p, listOf(sales, growth))
                .filter { it.anchored }
                .map { it.text } shouldContainExactlyInAnyOrder listOf("marketplace revenue", "revenue growth")
        }
    })
