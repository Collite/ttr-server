// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.Provenance
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.fuzzy.v1.TargetClass
import org.tatrman.grounding.v1.GetStatusResponse
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.GroundingClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.Binder
import org.tatrman.resolver.pipeline.DomainSpanCandidate
import org.tatrman.resolver.pipeline.FrameRolePreps
import org.tatrman.resolver.pipeline.FrameRoles
import org.tatrman.resolver.pipeline.GatedSpan
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.pipeline.UniversalBinding
import org.tatrman.resolver.pipeline.UniversalSeam
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.EntityType
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolveContext
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.UniversalEntityType
import org.tatrman.resolver.v1.TargetClass as LatticeTargetClass

private const val MARKETPLACE_REVENUE = "er.entity.catalog_sales.ext_sales_price"
private const val STORE_REVENUE = "er.entity.store_sales.ext_sales_price"
private const val REVENUE = "er.entity.channel_sales.ext_sales_price"
private const val MONTH = "er.entity.date_dim.month"
private const val QUARTER = "er.entity.date_dim.quarter"
private const val WEEK = "er.entity.date_dim.week_seq"
private const val YEAR = "er.entity.date_dim.year"

private val GROUPING = FrameRole.FRAME_ROLE_GROUPING

/**
 * ttr-server#152 — **a calendar word inside a bound grain is the grain, not a date.**
 *
 * English NER (Stanza) types the calendar word of a grain phrase a DATE: `month` in *by month*,
 * `quarter in 2025` (one span) in *by quarter in 2025*, `Monthly` in *Monthly … revenue*. The words
 * were then read twice — once as the grouping the estate declared, once as a date the grounding
 * kernel read against today:
 *
 *  - *by month in 2025* grounded the CURRENT month beside 2025: two ANDed ranges;
 *  - *by quarter in 2025* sent the kernel `quarter in 2025`, which it read as the current quarter,
 *    and 2025 was lost: one confident, wrong range;
 *  - *Store revenue by month* restricted the grouping to this month: one row;
 *  - *Monthly …* took the declared grain word for a date, and the grain never bound.
 *
 * The question parses are the live hartland ones (Stanza and UDPipe/NameTag, 2026-10-12), quoted
 * from the resolver probe; the edge cases say where a parse is built by hand. The estate is
 * hartland's own grain vocabulary (`by month`, `monthly`, `po měsících`, …). The assertions are on
 * what the grounding kernel RECEIVES — the one place both wrong readings had in common — and on the
 * lattice the caller reads.
 */
class GrainWordTest :
    StringSpec({

        // ── en: the phrasings Stanza tags ────────────────────────────────────────────────────────

        "`Marketplace sales by quarter in 2025` — the kernel is asked about 2025, not `quarter in 2025`" {
            val r =
                resolve(
                    "Marketplace sales by quarter in 2025",
                    arrayOf(
                        tok("Marketplace", 0, 11, "marketplace", "NOUN", 2, "compound"),
                        tok("sales", 12, 17, "sale", "NOUN", 0, "root"),
                        tok("by", 18, 20, "by", "ADP", 4, "case"),
                        tok("quarter", 21, 28, "quarter", "NOUN", 2, "nmod"),
                        tok("in", 29, 31, "in", "ADP", 6, "case"),
                        tok("2025", 32, 36, "2025", "NUM", 2, "nmod"),
                    ),
                    date("quarter in 2025", 21, 36),
                )

            r.grounded shouldContainExactly listOf("2025")
            r.dateValues shouldContainExactly listOf("2025")
            r.roles(QUARTER) shouldContainExactly listOf(GROUPING)
        }

        "`Marketplace revenue by quarter in 2025` — the issue's own row: one span swallowed the grain" {
            val r =
                resolve(
                    "Marketplace revenue by quarter in 2025",
                    arrayOf(
                        tok("Marketplace", 0, 11, "marketplace", "NOUN", 2, "compound"),
                        tok("revenue", 12, 19, "revenue", "NOUN", 0, "root"),
                        tok("by", 20, 22, "by", "ADP", 4, "case"),
                        tok("quarter", 23, 30, "quarter", "NOUN", 2, "nmod"),
                        tok("in", 31, 33, "in", "ADP", 6, "case"),
                        tok("2025", 34, 38, "2025", "NUM", 2, "nmod"),
                    ),
                    date("quarter in 2025", 23, 38),
                )

            r.grounded shouldContainExactly listOf("2025")
            // the trimmed date is the year the user wrote, at its own offsets
            r.response.resolutionState.valuesList
                .single()
                .span
                .let { (it.start to it.end) shouldBe (34 to 38) }
            r.roles(QUARTER) shouldContainExactly listOf(GROUPING)
        }

        "`Marketplace revenue by month in 2025` — `month` inside `by month` is not a second period" {
            val r =
                resolve(
                    "Marketplace revenue by month in 2025",
                    arrayOf(
                        tok("Marketplace", 0, 11, "marketplace", "NOUN", 2, "compound"),
                        tok("revenue", 12, 19, "revenue", "NOUN", 0, "root"),
                        tok("by", 20, 22, "by", "ADP", 4, "case"),
                        tok("month", 23, 28, "month", "NOUN", 2, "nmod"),
                        tok("in", 29, 31, "in", "ADP", 6, "case"),
                        tok("2025", 32, 36, "2025", "NUM", 4, "nmod"),
                    ),
                    date("month", 23, 28),
                    date("2025", 32, 36),
                )

            r.grounded shouldContainExactly listOf("2025")
            r.dateValues shouldContainExactly listOf("2025")
            r.roles(MONTH) shouldContainExactly listOf(GROUPING)
            // …and the Resolution says the same as the lattice: one universal, the year
            r.universalBindings shouldContainExactly listOf("2025")
        }

        "`Marketplace sales in 2025 by quarter` — the grain after the year" {
            val r =
                resolve(
                    "Marketplace sales in 2025 by quarter",
                    arrayOf(
                        tok("Marketplace", 0, 11, "marketplace", "NOUN", 2, "compound"),
                        tok("sales", 12, 17, "sale", "NOUN", 0, "root"),
                        tok("in", 18, 20, "in", "ADP", 4, "case"),
                        tok("2025", 21, 25, "2025", "NUM", 2, "nmod"),
                        tok("by", 26, 28, "by", "ADP", 6, "case"),
                        tok("quarter", 29, 36, "quarter", "NOUN", 2, "nmod"),
                    ),
                    date("2025", 21, 25),
                    date("quarter", 29, 36),
                )

            r.grounded shouldContainExactly listOf("2025")
            r.roles(QUARTER) shouldContainExactly listOf(GROUPING)
        }

        "`Marketplace revenue by week in 2025` — the week is the grain, not this week" {
            val r =
                resolve(
                    "Marketplace revenue by week in 2025",
                    arrayOf(
                        tok("Marketplace", 0, 11, "marketplace", "NOUN", 2, "compound"),
                        tok("revenue", 12, 19, "revenue", "NOUN", 0, "root"),
                        tok("by", 20, 22, "by", "ADP", 4, "case"),
                        tok("week", 23, 27, "week", "NOUN", 2, "nmod"),
                        tok("in", 28, 30, "in", "ADP", 6, "case"),
                        tok("2025", 31, 35, "2025", "NUM", 4, "nmod"),
                    ),
                    date("week", 23, 27),
                    date("2025", 31, 35),
                )

            r.grounded shouldContainExactly listOf("2025")
            r.roles(WEEK) shouldContainExactly listOf(GROUPING)
        }

        "`Store revenue by month` (p-store-sales-by-month) — nothing is grounded, so nothing restricts to this month" {
            val r =
                resolve(
                    "Store revenue by month",
                    arrayOf(
                        tok("Store", 0, 5, "store", "NOUN", 2, "compound"),
                        tok("revenue", 6, 13, "revenue", "NOUN", 0, "root"),
                        tok("by", 14, 16, "by", "ADP", 4, "case"),
                        tok("month", 17, 22, "month", "NOUN", 2, "nmod"),
                    ),
                    date("month", 17, 22),
                )

            r.grounded.shouldBeEmpty()
            r.dateValues.shouldBeEmpty()
            r.universalBindings.shouldBeEmpty()
            r.roles(MONTH) shouldContainExactly listOf(GROUPING)
        }

        "`What was marketplace revenue in 2025 by month?` (a1-1-marketplace-by-month)" {
            val r =
                resolve(
                    "What was marketplace revenue in 2025 by month?",
                    arrayOf(
                        tok("What", 0, 4, "what", "PRON", 0, "root"),
                        tok("was", 5, 8, "be", "AUX", 1, "cop"),
                        tok("marketplace", 9, 20, "marketplace", "NOUN", 4, "compound"),
                        tok("revenue", 21, 28, "revenue", "NOUN", 1, "nsubj"),
                        tok("in", 29, 31, "in", "ADP", 6, "case"),
                        tok("2025", 32, 36, "2025", "NUM", 4, "nmod"),
                        tok("by", 37, 39, "by", "ADP", 8, "case"),
                        tok("month", 40, 45, "month", "NOUN", 4, "nmod"),
                        tok("?", 45, 46, "?", "PUNCT", 1, "punct"),
                    ),
                    date("2025", 32, 36),
                    date("month", 40, 45),
                )

            r.grounded shouldContainExactly listOf("2025")
            r.roles(MONTH) shouldContainExactly listOf(GROUPING)
        }

        "`Marketplace revenue by month in 2025 for Memphis DC` (a2-2-member-dc-by-month)" {
            val r =
                resolve(
                    "Marketplace revenue by month in 2025 for Memphis DC",
                    arrayOf(
                        tok("Marketplace", 0, 11, "marketplace", "NOUN", 2, "compound"),
                        tok("revenue", 12, 19, "revenue", "NOUN", 0, "root"),
                        tok("by", 20, 22, "by", "ADP", 4, "case"),
                        tok("month", 23, 28, "month", "NOUN", 2, "nmod"),
                        tok("in", 29, 31, "in", "ADP", 6, "case"),
                        tok("2025", 32, 36, "2025", "NUM", 4, "nmod"),
                        tok("for", 37, 40, "for", "ADP", 9, "case"),
                        tok("Memphis", 41, 48, "Memphis", "PROPN", 9, "compound"),
                        tok("DC", 49, 51, "DC", "PROPN", 2, "nmod"),
                    ),
                    date("month", 23, 28),
                    date("2025", 32, 36),
                    MhMembers.ner("Memphis", 41, 48, "GPE"),
                )

            r.grounded shouldContainExactly listOf("2025")
            r.roles(MONTH) shouldContainExactly listOf(GROUPING)
        }

        "`How did marketplace revenue develop this year by month?` (r4-this-year-by-month) — this year stands" {
            val r =
                resolve(
                    "How did marketplace revenue develop this year by month?",
                    arrayOf(
                        tok("How", 0, 3, "how", "ADV", 5, "advmod"),
                        tok("did", 4, 7, "do", "AUX", 5, "aux"),
                        tok("marketplace", 8, 19, "marketplace", "NOUN", 4, "compound"),
                        tok("revenue", 20, 27, "revenue", "NOUN", 5, "nsubj"),
                        tok("develop", 28, 35, "develop", "VERB", 0, "root"),
                        tok("this", 36, 40, "this", "DET", 7, "det"),
                        tok("year", 41, 45, "year", "NOUN", 5, "obl:unmarked"),
                        tok("by", 46, 48, "by", "ADP", 9, "case"),
                        tok("month", 49, 54, "month", "NOUN", 5, "obl"),
                        tok("?", 54, 55, "?", "PUNCT", 5, "punct"),
                    ),
                    date("this year", 36, 45),
                    date("month", 49, 54),
                )

            r.grounded shouldContainExactly listOf("this year")
            r.roles(MONTH) shouldContainExactly listOf(GROUPING)
        }

        "`Revenue by month for 2025` — the issue's repro" {
            val r =
                resolve(
                    "Revenue by month for 2025",
                    arrayOf(
                        tok("Revenue", 0, 7, "revenue", "NOUN", 0, "root"),
                        tok("by", 8, 10, "by", "ADP", 3, "case"),
                        tok("month", 11, 16, "month", "NOUN", 1, "nmod"),
                        tok("for", 17, 20, "for", "ADP", 5, "case"),
                        tok("2025", 21, 25, "2025", "NUM", 1, "nmod"),
                    ),
                    date("month", 11, 16),
                    date("2025", 21, 25),
                )

            r.grounded shouldContainExactly listOf("2025")
            r.roles(MONTH) shouldContainExactly listOf(GROUPING)
        }

        // ── en: a cut date is the user's date — its range word, and no one else's normalisation ──

        "`Revenue by month since 2024`, one span across the grain — `since` stays with the date it bounds (built)" {
            val r =
                resolve(
                    "Revenue by month since 2024",
                    arrayOf(
                        tok("Revenue", 0, 7, "revenue", "NOUN", 0, "root"),
                        tok("by", 8, 10, "by", "ADP", 3, "case"),
                        tok("month", 11, 16, "month", "NOUN", 1, "nmod"),
                        tok("since", 17, 22, "since", "ADP", 5, "case"),
                        tok("2024", 23, 27, "2024", "NUM", 1, "obl"),
                    ),
                    date("month since 2024", 11, 27),
                )

            // the kernel is asked about the range the user wrote, never the year alone
            r.grounded shouldContainExactly listOf("since 2024")
            r.roles(MONTH) shouldContainExactly listOf(GROUPING)
        }

        "a cut date carries none of the wider span's normalisation — what the kernel cannot ground stays unnormalised" {
            // an engine that normalised `quarter in 2025` as the current quarter: the words that
            // said quarter are the grain's now, so that value describes nothing the date still holds
            val r =
                resolve(
                    "Marketplace revenue by quarter in 2025",
                    arrayOf(
                        tok("Marketplace", 0, 11, "marketplace", "NOUN", 2, "compound"),
                        tok("revenue", 12, 19, "revenue", "NOUN", 0, "root"),
                        tok("by", 20, 22, "by", "ADP", 4, "case"),
                        tok("quarter", 23, 30, "quarter", "NOUN", 2, "nmod"),
                        tok("in", 31, 33, "in", "ADP", 6, "case"),
                        tok("2025", 34, 38, "2025", "NUM", 2, "nmod"),
                    ),
                    date("quarter in 2025", 23, 38, normalizedValue = "2026-Q4"),
                )

            r.grounded shouldContainExactly listOf("2025")
            // RecordingKernel answers UNGROUNDABLE: the lattice falls back to the NER's normalisation
            r.response.resolutionState.valuesList
                .single()
                .grounding.normalizedValue shouldBe ""
            r.response.resolution.bindingsList
                .single { it.hasUniversal() }
                .universal.normalizedValue shouldBe ""
        }

        // ── en: the phrasing Stanza does not tag — it was right, and stays right ─────────────────

        "`What are the marketplace revenues for 2025 by month?` — no DATE on `month`, the same lattice" {
            val r =
                resolve(
                    "What are the marketplace revenues for 2025 by month?",
                    arrayOf(
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
                    ),
                    date("2025", 38, 42),
                )

            r.grounded shouldContainExactly listOf("2025")
            r.roles(MONTH) shouldContainExactly listOf(GROUPING)
        }

        // ── en: a declared one-word grain (`monthly`) ───────────────────────────────────────────

        fun monthly(word: String = "Monthly") =
            arrayOf(
                tok(word, 0, word.length, word.lowercase(), "ADJ", 3, "amod"),
                tok("marketplace", word.length + 1, word.length + 12, "marketplace", "NOUN", 3, "compound"),
                tok("revenue", word.length + 13, word.length + 20, "revenue", "NOUN", 0, "root"),
                tok("in", word.length + 21, word.length + 23, "in", "ADP", 5, "case"),
                tok("2025", word.length + 24, word.length + 28, "2025", "NUM", 3, "nmod"),
            )

        "`Monthly marketplace revenue in 2025`, Stanza tags `Monthly` — the declared grain binds, as the grouping" {
            val r =
                resolve(
                    "Monthly marketplace revenue in 2025",
                    monthly(),
                    date("Monthly", 0, 7),
                    date("2025", 31, 35),
                )

            r.grounded shouldContainExactly listOf("2025")
            r.dateValues shouldContainExactly listOf("2025")
            r.roles(MONTH) shouldContainExactly listOf(GROUPING)
            r.response.resolutionState.gapsList
                .shouldBeEmpty()
        }

        "`Monthly marketplace revenue in 2025`, untagged (the live parse without its DATE) — the same lattice" {
            val r = resolve("Monthly marketplace revenue in 2025", monthly(), date("2025", 31, 35))

            r.grounded shouldContainExactly listOf("2025")
            r.roles(MONTH) shouldContainExactly listOf(GROUPING)
        }

        "`Quarterly marketplace revenue in 2025` — the same, for the quarter" {
            val r =
                resolve(
                    "Quarterly marketplace revenue in 2025",
                    monthly("Quarterly"),
                    date("Quarterly", 0, 9),
                    date("2025", 33, 37),
                )

            r.grounded shouldContainExactly listOf("2025")
            r.roles(QUARTER) shouldContainExactly listOf(GROUPING)
        }

        // ── the rule's edges: only a binding takes words from a date ────────────────────────────

        "a grain phrase the estate did not declare does not bind — the date stands as it was" {
            val r =
                resolve(
                    "Store revenue by month",
                    arrayOf(
                        tok("Store", 0, 5, "store", "NOUN", 2, "compound"),
                        tok("revenue", 6, 13, "revenue", "NOUN", 0, "root"),
                        tok("by", 14, 16, "by", "ADP", 4, "case"),
                        tok("month", 17, 22, "month", "NOUN", 2, "nmod"),
                    ),
                    date("month", 17, 22),
                    registry = registry(grains = false),
                )

            r.grounded shouldContainExactly listOf("month")
        }

        "a declared grain the matcher does not confirm is withdrawn — the date stands, and no new gap" {
            val r =
                resolve(
                    "Monthly marketplace revenue in 2025",
                    monthly(),
                    date("Monthly", 0, 7),
                    date("2025", 31, 35),
                    fuzzy = HartlandFuzzy(registry(), refuse = setOf("monthly")),
                )

            r.grounded shouldContainExactly listOf("Monthly", "2025")
            r.response.resolutionState.mentionsList
                .map { it.span.text } shouldContainExactly listOf("marketplace revenue")
            r.response.resolutionState.gapsList
                .none { it.kind == GapKind.GAP_KIND_G1_UNBOUND } shouldBe true
        }

        "a declared word inside a WIDER date is not a grain — `last year` stays a date under a bare `year` (built)" {
            val r =
                resolve(
                    "Revenue last year",
                    arrayOf(
                        tok("Revenue", 0, 7, "revenue", "NOUN", 0, "root"),
                        tok("last", 8, 12, "last", "ADJ", 3, "amod"),
                        tok("year", 13, 17, "year", "NOUN", 1, "obl:tmod"),
                    ),
                    date("last year", 8, 17),
                    registry = registry(extraYearAnchor = "year"),
                )

            r.grounded shouldContainExactly listOf("last year")
            r.response.resolutionState.mentionsList
                .none { m -> m.bindingsList.any { it.ref == YEAR } } shouldBe true
        }

        // ── the seam rule on its own ────────────────────────────────────────────────────────────

        fun grain(
            start: Int,
            end: Int,
            found: Boolean = true,
        ) = GatedSpan(
            DomainSpanCandidate(
                text = "by month",
                start = start,
                end = end,
                gatedEntityRefs = listOf(MONTH),
                categories = listOf(MONTH),
                anchored = true,
                origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE,
            ),
            if (found) {
                listOf(
                    Binder.ClassedMatch(
                        FuzzyMatch
                            .newBuilder()
                            .setCandidateId("lex:$MONTH")
                            .setCategory(MONTH)
                            .setTargetRef(MONTH)
                            .setTargetClass(TargetClass.TARGET_CLASS_MODEL_OBJECT)
                            .setSource(SourceTag.DECLARED)
                            .build(),
                        EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS,
                    ),
                )
            } else {
                emptyList()
            },
            ambiguous = false,
        )

        fun universal(
            text: String,
            start: Int,
            type: UniversalEntityType = UniversalEntityType.DATE,
        ) = UniversalBinding(start, start + text.length, text, type, text, "", "stanza")

        "with no parse to cut by, only a date the grain covers whole is superseded" {
            // "Revenue by month for 2025": `month` [11,16) inside `by month` [8,16)
            val month = universal("month", 11)
            val seam = UniversalSeam.supersede(listOf(month, universal("2025", 21)), listOf(grain(8, 16)))

            seam.universals.map { it.text } shouldContainExactly listOf("2025")
            seam.superseded.map { it.first } shouldContainExactly listOf(month)

            // …and a date that runs past the grain stands, uncut: there are no words to cut between
            val wide = universal("month in 2025", 11)
            UniversalSeam.supersede(listOf(wide), listOf(grain(8, 16))).universals shouldContainExactly listOf(wide)
        }

        "a grain in the MIDDLE of a date does not split it — the date stands as it came" {
            val q = "Revenue 2024 by month 2025"
            val tokens =
                listOf(
                    tok("Revenue", 0, 7, "revenue", "NOUN", 0, "root"),
                    tok("2024", 8, 12, "2024", "NUM", 1, "nmod"),
                    tok("by", 13, 15, "by", "ADP", 4, "case"),
                    tok("month", 16, 21, "month", "NOUN", 1, "nmod"),
                    tok("2025", 22, 26, "2025", "NUM", 1, "nmod"),
                )
            val parse = AnalyzeResponse.newBuilder().addAllTokens(tokens).build()
            val date = universal("2024 by month 2025", 8)

            UniversalSeam.supersede(listOf(date), listOf(grain(13, 21)), parse, q).universals shouldContainExactly
                listOf(date)
        }

        "only a DATE gives words to a grain — a place under the same characters is the dual reading's business" {
            val place = universal("month", 11, UniversalEntityType.LOCATION)
            UniversalSeam.supersede(listOf(place), listOf(grain(8, 16))).universals shouldContainExactly listOf(place)
        }

        "a grain that found nothing takes nothing, and stays where it was when it lies outside the date" {
            val month = universal("month", 11)
            val unbound = grain(8, 16, found = false)
            val seam = UniversalSeam.supersede(listOf(month), listOf(unbound))

            seam.universals shouldContainExactly listOf(month)
            // `by month` reaches past the date (`by`), so it was not proposed over it: it stays
            seam.gated shouldContainExactly listOf(unbound)
        }

        "at the cut, only a word that keeps the date's meaning goes — a range word stays with the date" {
            fun cut(
                q: String,
                tokens: List<Token>,
                date: UniversalBinding,
                grain: GatedSpan,
            ): UniversalBinding =
                UniversalSeam
                    .supersede(
                        listOf(date),
                        listOf(grain),
                        AnalyzeResponse.newBuilder().addAllTokens(tokens).build(),
                        q,
                    ).universals
                    .single()

            // "Revenue by quarter until 2025": `until` as Stanza tags it, a subordinator
            val until =
                cut(
                    "Revenue by quarter until 2025",
                    listOf(
                        tok("Revenue", 0, 7, "revenue", "NOUN", 0, "root"),
                        tok("by", 8, 10, "by", "ADP", 3, "case"),
                        tok("quarter", 11, 18, "quarter", "NOUN", 1, "nmod"),
                        tok("until", 19, 24, "until", "SCONJ", 5, "mark"),
                        tok("2025", 25, 29, "2025", "NUM", 1, "obl"),
                    ),
                    universal("quarter until 2025", 11),
                    grain(8, 18),
                )
            until.text shouldBe "until 2025"
            (until.start to until.end) shouldBe (19 to 29)

            // "Revenue by quarter during 2025": `during` adds nothing a year does not say — it goes
            val during =
                cut(
                    "Revenue by quarter during 2025",
                    listOf(
                        tok("Revenue", 0, 7, "revenue", "NOUN", 0, "root"),
                        tok("by", 8, 10, "by", "ADP", 3, "case"),
                        tok("quarter", 11, 18, "quarter", "NOUN", 1, "nmod"),
                        tok("during", 19, 25, "during", "ADP", 5, "case"),
                        tok("2025", 26, 30, "2025", "NUM", 1, "obl"),
                    ),
                    universal("quarter during 2025", 11),
                    grain(8, 18),
                )
            during.text shouldBe "2025"
        }

        "a cut date drops the NER's normalisation of the wider span" {
            val q = "Marketplace revenue by quarter in 2025"
            val tokens =
                listOf(
                    tok("Marketplace", 0, 11, "marketplace", "NOUN", 2, "compound"),
                    tok("revenue", 12, 19, "revenue", "NOUN", 0, "root"),
                    tok("by", 20, 22, "by", "ADP", 4, "case"),
                    tok("quarter", 23, 30, "quarter", "NOUN", 2, "nmod"),
                    tok("in", 31, 33, "in", "ADP", 6, "case"),
                    tok("2025", 34, 38, "2025", "NUM", 2, "nmod"),
                )
            val wide = universal("quarter in 2025", 23).copy(normalizedValue = "2026-Q4")
            val seam =
                UniversalSeam.supersede(
                    listOf(wide),
                    listOf(grain(20, 30)),
                    AnalyzeResponse.newBuilder().addAllTokens(tokens).build(),
                    q,
                )

            val kept = seam.universals.single()
            kept.text shouldBe "2025"
            kept.normalizedValue shouldBe ""
            // the log keeps the original, normalisation and all
            seam.trimmed.single().first shouldBe wide
        }

        // ── R6" — an adjective naming an attribute is the breakdown ─────────────────────────────

        /**
         * The roles of [word] (`amod` on a measure) binding [targetClass] / [kind]. [dateTyped]: the
         * NER typed the word a DATE by itself, as Stanza types `Monthly`.
         */
        fun amodRoles(
            targetClass: LatticeTargetClass = LatticeTargetClass.TARGET_CLASS_MODEL_OBJECT,
            kind: String = "attribute",
            word: String = "Monthly",
            lemma: String = word.lowercase(),
            lang: String = "en",
            dateTyped: Boolean = false,
        ): List<FrameRole> {
            val end = word.length
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .addTokens(tok(word, 0, end, lemma, "ADJ", 2, "amod"))
                    .addTokens(tok("revenue", end + 1, end + 8, "revenue", "NOUN", 0, "root"))
            if (dateTyped) parse.addEntities(date(word, 0, end))
            return FrameRoles
                .derive(
                    listOf(
                        FrameRoles.Input("adj", 0, 0, targetClass, kind, anchorsValue = false, charEnd = end),
                        FrameRoles.Input(
                            "rev",
                            end + 1,
                            1,
                            LatticeTargetClass.TARGET_CLASS_MODEL_OBJECT,
                            "measure",
                            false,
                            end + 8,
                        ),
                    ),
                    parse.build(),
                    lang,
                    FrameRolePreps.shipped(),
                ).getValue("adj")
        }

        "R6\": `Monthly revenue` — an attribute named by an adjective is the GROUPING" {
            amodRoles(LatticeTargetClass.TARGET_CLASS_MODEL_OBJECT, "attribute") shouldContainExactly listOf(GROUPING)
        }

        "R6\" reads the model object only: a member adjective is R1's FILTER, an entity gets nothing new" {
            amodRoles(LatticeTargetClass.TARGET_CLASS_MEMBER, "attribute") shouldContainExactly
                listOf(FrameRole.FRAME_ROLE_FILTER)
            amodRoles(LatticeTargetClass.TARGET_CLASS_MODEL_OBJECT, "entity") shouldNotContain GROUPING
        }

        "R6\" is the calendar's: `Promotional revenue` names an attribute, and is no breakdown by it" {
            // an estate that declares `promotional` for a promotion flag means a restriction, not a
            // GROUP BY the flag: the adjective keeps the role it had before R6" — none
            amodRoles(word = "Promotional").shouldBeEmpty()
        }

        "R6\" knows a grain adjective from the frame-role tables, typed a date or not" {
            amodRoles(word = "Annual") shouldContainExactly listOf(GROUPING)
            amodRoles(word = "Monthly", dateTyped = true) shouldContainExactly listOf(GROUPING)
            amodRoles(word = "Měsíční", lemma = "měsíční", lang = "cs") shouldContainExactly listOf(GROUPING)
            // a question typed without diacritics, and a lemmatiser that kept them off
            amodRoles(word = "Mesicni", lemma = "mesicni", lang = "cs") shouldContainExactly listOf(GROUPING)
        }

        "R6\" takes a word the NER typed a date by itself as a grain, off the tables too" {
            amodRoles(word = "Fortnightly", dateTyped = true) shouldContainExactly listOf(GROUPING)
            amodRoles(word = "Fortnightly").shouldBeEmpty()
        }

        // ── cs: not affected, and kept that way ─────────────────────────────────────────────────

        "„Tržby z tržiště po čtvrtletích v roce 2025“ grounds 2025 only, as it always did" {
            val r =
                resolve(
                    "Tržby z tržiště po čtvrtletích v roce 2025",
                    arrayOf(
                        tok("Tržby", 0, 5, "tržba", "NOUN", 0, "root", "Plur"),
                        tok("z", 6, 7, "z", "ADP", 3, "case"),
                        tok("tržiště", 8, 15, "tržiště", "NOUN", 1, "nmod", "Sing"),
                        tok("po", 16, 18, "po", "ADP", 5, "case"),
                        tok("čtvrtletích", 19, 30, "čtvrtletí", "NOUN", 1, "nmod", "Plur"),
                        tok("v", 31, 32, "v", "ADP", 7, "case"),
                        tok("roce", 33, 37, "rok", "NOUN", 5, "nmod", "Sing"),
                        tok("2025", 38, 42, "2025", "NUM", 7, "nummod"),
                    ),
                    MhMembers.ner("2025", 38, 42, "DATE", "cnec:ty"),
                    lang = "cs",
                )

            r.grounded shouldContainExactly listOf("2025")
            r.roles(QUARTER) shouldContainExactly listOf(GROUPING)
        }

        "„Jaké byly tržby z tržiště v roce 2025 po měsících?“ grounds 2025 only, as it always did" {
            val r =
                resolve(
                    "Jaké byly tržby z tržiště v roce 2025 po měsících?",
                    arrayOf(
                        tok("Jaké", 0, 4, "jaký", "DET", 0, "root"),
                        tok("byly", 5, 9, "být", "AUX", 1, "cop"),
                        tok("tržby", 10, 15, "tržba", "NOUN", 1, "nsubj", "Plur"),
                        tok("z", 16, 17, "z", "ADP", 5, "case"),
                        tok("tržiště", 18, 25, "tržiště", "NOUN", 3, "nmod", "Sing"),
                        tok("v", 26, 27, "v", "ADP", 7, "case"),
                        tok("roce", 28, 32, "rok", "NOUN", 5, "nmod", "Sing"),
                        tok("2025", 33, 37, "2025", "NUM", 7, "nummod"),
                        tok("po", 38, 40, "po", "ADP", 10, "case"),
                        tok("měsících", 41, 49, "měsíc", "NOUN", 1, "obl", "Plur"),
                        tok("?", 49, 50, "?", "PUNCT", 1, "punct"),
                    ),
                    MhMembers.ner("2025", 33, 37, "DATE", "cnec:ty"),
                    lang = "cs",
                )

            r.grounded shouldContainExactly listOf("2025")
            r.roles(MONTH) shouldContainExactly listOf(GROUPING)
        }
    }) {
    private class Resolved(
        val response: ResolveResponse,
        /** The span texts the grounding kernel was asked about, in order. */
        val grounded: List<String>,
    ) {
        /** The grounded values the lattice carries, by text. */
        val dateValues: List<String>
            get() =
                response.resolutionState.valuesList
                    .filter { it.hasGrounding() && it.grounding.kind == "DATE" }
                    .map { it.span.text }

        /** The universal bindings the Resolution carries, by text. */
        val universalBindings: List<String>
            get() =
                response.resolution.bindingsList
                    .filter { it.hasUniversal() }
                    .map { it.span.text }

        /** The frame roles of the one mention bound to [ref]. */
        fun roles(ref: String): List<FrameRole> =
            response.resolutionState.mentionsList
                .single { m -> m.bindingsList.any { it.ref == ref } }
                .frameRolesList
    }

    private companion object {
        fun tok(
            text: String,
            start: Int,
            end: Int,
            lemma: String,
            upos: String,
            depHead: Int,
            depRelation: String,
            number: String? = null,
        ): Token {
            val b = MhMembers.tok(text, start, end, lemma, upos, depHead, depRelation).toBuilder()
            if (number != null) b.putFeats("Number", number)
            return b.build()
        }

        /**
         * A Stanza DATE, as the live parse carries it: no CNEC code, `source_engine` stanza.
         * [normalizedValue] — what an engine that normalises its dates would have said of [text].
         */
        fun date(
            text: String,
            start: Int,
            end: Int,
            normalizedValue: String = "",
        ): NerEntity =
            MhMembers
                .ner(text, start, end, "DATE", normalizedValue)
                .toBuilder()
                .setSourceEngine("stanza")
                .build()

        fun et(
            ref: String,
            anchors: List<String>,
            kind: String,
            owner: String,
        ): EntityType =
            EntityType
                .newBuilder()
                .setRef(ref)
                .addCategories(ref)
                .addAllAnchors(anchors)
                .setObjectKind(kind)
                .setOwnerRef(owner)
                .build()

        /**
         * hartland's measures and its calendar grain vocabulary (`lexicon/aliases/hartland.lex.yaml`):
         * the grain PHRASES, never the bare calendar nouns, which are the time kernel's triggers.
         */
        fun registry(
            grains: Boolean = true,
            extraYearAnchor: String? = null,
        ): Registry {
            val b =
                Registry
                    .newBuilder()
                    .addEntityTypes(
                        et(
                            MARKETPLACE_REVENUE,
                            listOf("marketplace revenue", "marketplace sales", "tržby z tržiště", "tržba z tržiště"),
                            "measure",
                            "er.entity.catalog_sales",
                        ),
                    ).addEntityTypes(et(STORE_REVENUE, listOf("store revenue"), "measure", "er.entity.store_sales"))
                    .addEntityTypes(
                        et(REVENUE, listOf("revenue", "revenues", "tržby"), "measure", "er.entity.channel_sales"),
                    )
            if (grains) {
                b
                    .addEntityTypes(
                        et(
                            MONTH,
                            listOf("měsíčně", "po měsících", "monthly", "by month", "month by month"),
                            "attribute",
                            "er.entity.date_dim",
                        ),
                    ).addEntityTypes(
                        et(
                            QUARTER,
                            listOf("čtvrtletně", "po čtvrtletích", "quarterly", "by quarter"),
                            "attribute",
                            "er.entity.date_dim",
                        ),
                    ).addEntityTypes(
                        et(WEEK, listOf("týdně", "po týdnech", "weekly", "by week"), "attribute", "er.entity.date_dim"),
                    ).addEntityTypes(
                        et(
                            YEAR,
                            listOf("ročně", "po letech", "za rok", "yearly", "annual", "by year") +
                                listOfNotNull(extraYearAnchor),
                            "attribute",
                            "er.entity.date_dim",
                        ),
                    )
            }
            return b
                .addLocales("cs")
                .addLocales("en")
                .setSnapshotHash("snap-grain")
                .build()
        }

        fun resolve(
            text: String,
            tokens: Array<Token>,
            vararg entities: NerEntity,
            lang: String = "en",
            registry: Registry = registry(),
            fuzzy: FuzzyClient = HartlandFuzzy(registry),
        ): Resolved {
            val kernel = RecordingKernel()
            val parse =
                MhMembers
                    .parse(text, tokens, lang)
                    .toBuilder()
                    .addAllEntities(entities.toList())
                    .build()
            val pipeline =
                ResolverPipeline(
                    MhMembers.FakeNlp(parse, lang),
                    fuzzy,
                    SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), "snap-grain"), ResolverThresholds.LIVE),
                    emptyMap(),
                    ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                    grounding = kernel,
                    defaultPackage = "hartland",
                )
            val response =
                runBlocking {
                    pipeline.resolve(
                        ResolveRequest
                            .newBuilder()
                            .setConversationId("grain-$lang")
                            .setFresh(FreshQuestion.newBuilder().setText(text).setLocale(lang))
                            .setContext(ResolveContext.newBuilder().setReferenceDatetime("2026-10-12T00:00:00Z"))
                            .setRegistry(registry)
                            .build(),
                    )
                }
            return Resolved(response, kernel.seen.map { it.spanText })
        }

        /**
         * The matcher as hartland's answers it for these spans: a declared row for a span whose words
         * ARE one of the asked type's terms (the surface, or the lemma the parse gives it), and the
         * time kernel's trigger rows (`ground:chrono`) for the bare calendar nouns. Category-scoped
         * like the real one. [refuse] — terms it does not confirm, for the edge where the declared
         * grain never binds.
         */
        class HartlandFuzzy(
            private val registry: Registry,
            private val refuse: Set<String> = emptySet(),
        ) : FuzzyClient {
            private val triggers = setOf("month", "quarter", "week", "year", "roce", "rok", "měsíc", "čtvrtletí")

            override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
                val builder = BatchMatchResponse.newBuilder()
                for (span in request.spansList) {
                    val query = fold(span.query)
                    val asked = span.categoriesList.toSet()
                    val matches = mutableListOf<FuzzyMatch>()
                    if (query !in refuse) {
                        for (type in registry.entityTypesList) {
                            if (type.ref !in asked) continue
                            if (type.anchorsList.none { same(fold(it), query) }) continue
                            matches += row(span.query, type.ref, TargetClass.TARGET_CLASS_MODEL_OBJECT)
                        }
                    }
                    if ("ground:chrono" in asked && query in triggers) {
                        matches += row(span.query, "ground:chrono", TargetClass.TARGET_CLASS_GROUNDING_TRIGGER)
                    }
                    builder.addResults(FuzzyMatchResponse.newBuilder().addAllMatches(matches))
                }
                return builder.build()
            }

            override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()

            /** `marketplace revenues` is `marketplace revenue`, as the real matcher's lemma index has it. */
            private fun same(
                term: String,
                query: String,
            ): Boolean = term == query || term == query.removeSuffix("s")

            private fun fold(v: String) = v.trim().lowercase()

            private fun row(
                candidate: String,
                ref: String,
                targetClass: TargetClass,
            ): FuzzyMatch =
                FuzzyMatch
                    .newBuilder()
                    .setCandidateId("lex:$ref")
                    .setCandidate(candidate)
                    .setScore(1.0)
                    .setCategory(ref)
                    .setTargetRef(ref)
                    .setTargetClass(targetClass)
                    .setSource(SourceTag.DECLARED)
                    .setMatchMethod("EXACT")
                    .setProvenance(
                        Provenance
                            .newBuilder()
                            .setProducer("fuzzy")
                            .setMethod("TATRMAN")
                            .setRawScore(1.0),
                    ).build()
        }

        class RecordingKernel : GroundingClient {
            val seen = mutableListOf<GroundRequest>()

            override suspend fun ground(request: GroundRequest): GroundResponse {
                seen += request
                return GroundResponse.newBuilder().setStatus(GroundResponse.Status.UNGROUNDABLE).build()
            }

            override suspend fun getStatus(): GetStatusResponse = GetStatusResponse.getDefaultInstance()
        }
    }
}
