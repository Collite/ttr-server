// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.Provenance
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
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.GroundingClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.EntityType
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.ResolveContext
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.UniversalEntityType
import org.tatrman.resolver.v1.ValueFinding
import org.tatrman.resolver.v1.ValueKind

/**
 * ttr-server#206 — a year the NER typed a number is a date on the estate's date anchor, the same as
 * `in 2025`: written straight after the measure ("Store revenue 2025"), or after a temporal preposition
 * the NER did not count as making it a date („Tržby z prodejen za 2025“).
 *
 * Through the whole pipeline, over the parses the engines actually return. The NER is where the
 * phrasings part: for the SAME words around the year,
 *
 *  - Stanza (en, OntoNotes) types `2025` a `CARDINAL` in "Marketplace revenue 2025" and "Store revenue
 *    2025", but a `DATE` in "Marketplace revenue in 2025", "2025 marketplace revenue" and "Revenue 2025
 *    by channel";
 *  - NameTag 3 (cs, CNEC 2.0) types it `n_` / `nc` (a number) in „Tržby z tržiště 2025“, „Tržby 2025“,
 *    „… za 2025“, „… ve 2025“ and „… během 2025“, but `ty` (a year) in „… v roce 2025“ and „… v 2025“.
 *
 * A number is never offered to the date kernel. After a noun with no preposition it reads as a code
 * naming that noun (`Gaps.namesSomething`, #139): grounded, unattributed, G3. After a preposition
 * nothing reads it at all: the year was dropped with no gap, and the consumer's default period
 * answered instead.
 *
 * **Provenance of the parses (2026-10-13).** en: Stanza 1.11.0, `en` default package (tokenize, lemma,
 * pos, depparse, ner `ontonotes-ww-multi_charlm`), the engine hartland routes every en op to; it
 * reproduces the live parse `DeclaredTermReachabilityTest` quotes token for token. cs: dependencies from
 * Stanza 1.11.0 `cs` (hartland's cs DEP_PARSE engine), entities from NameTag 3
 * `nametag3-czech-cnec2.0-240830` (hartland's cs NER model, via LINDAT), carried the way the nlp front
 * hands them over — the coarse label plus `cnec:<tag>` (`ttrnlp.doc.labels`). The lemmas and POS on
 * these words agree with MorphoDiTa's (cf. `CsGrainPhraseTest`'s live quote). One label in this spec is
 * substituted, and says so where it is.
 */
class MistypedYearAttributionTest :
    StringSpec({

        // ── the bug: the bare year after the measure ──────────────────────────────────────────────

        "en `Marketplace revenue 2025` — the bare year is a date on the anchor, like `in 2025`" {
            val turn = resolve(MARKETPLACE_REVENUE_2025)

            turn.year().shouldBeAnchored()
            turn.gapsOnYear().shouldBeEmpty()
            turn.asked shouldContainExactly listOf("2025")
            // …and the resolution says what the lattice says
            turn.universalTypeOf("2025") shouldBe UniversalEntityType.DATE
        }

        "en `Store revenue 2025` — the corpus question (e2-store-revenue-2025)" {
            val turn = resolve(STORE_REVENUE_2025)

            turn.year().shouldBeAnchored()
            turn.gapsOnYear().shouldBeEmpty()
        }

        "en `Marketplace revenue, 2025` — a comma between the measure and the year changes nothing" {
            resolve(MARKETPLACE_REVENUE_COMMA_2025).year().shouldBeAnchored()
        }

        "cs „Tržby z tržiště 2025“ — the year hangs off a word INSIDE the measure's phrase" {
            val turn = resolve(TRZBY_Z_TRZISTE_2025)

            turn.year().shouldBeAnchored()
            turn.gapsOnYear().shouldBeEmpty()
        }

        "cs „Tržby 2025“" {
            val turn = resolve(TRZBY_2025)

            turn.year().shouldBeAnchored()
            turn.gapsOnYear().shouldBeEmpty()
        }

        // ── a year after a temporal preposition the NER typed a number ──────────────────────────

        "cs „Tržby z prodejen za 2025“ — the corpus question (e2): `za 2025` is a date, not dropped" {
            val turn = resolve(TRZBY_Z_PRODEJEN_ZA_2025)

            turn.year().shouldBeAnchored()
            turn.gapsOnYear().shouldBeEmpty()
            turn.asked shouldContainExactly listOf("2025")
        }

        "cs „Tržby z tržiště za 2025“" {
            resolve(TRZBY_Z_TRZISTE_ZA_2025).year().shouldBeAnchored()
        }

        "cs „Tržby ve 2025“ — `ve` reads by its lemma `v`" {
            resolve(TRZBY_VE_2025).year().shouldBeAnchored()
        }

        "cs „Tržby během 2025“" {
            resolve(TRZBY_BEHEM_2025).year().shouldBeAnchored()
        }

        "en: the table is read for English too — `Marketplace revenue for 2025` as a CARDINAL" {
            // ⚠ The ONE substituted label in this spec. Stanza typed every English year after a
            // preposition we tried a DATE (18 phrasings, 2026-10-13), so no real en input reaches this
            // rule today; the tree is Stanza's, the CARDINAL is not. Kept so the en row of the table
            // is proven read, not merely present.
            val turn = resolve(MARKETPLACE_REVENUE_FOR_2025.copy(entities = listOf(stanza("2025", 24, "CARDINAL"))))

            turn.year().shouldBeAnchored()
            turn.gapsOnYear().shouldBeEmpty()
        }

        "cs „Tržby za 2000 Kč“ — four digits in the year range, but `Kč` hangs off it: an amount" {
            val turn = resolve(TRZBY_ZA_2000_KC)

            turn.value("2000").grounding.kind shouldBe "MISC"
            turn.value("2000").attributionsList.shouldBeEmpty()
            turn.asked.shouldBeEmpty()
        }

        "cs „Tržby za 1500 Kč“ — an amount" {
            val turn = resolve(TRZBY_ZA_1500_KC)

            turn.value("1500").grounding.kind shouldBe "MISC"
            turn.asked.shouldBeEmpty()
        }

        "en `Revenue for 2000 dollars` — the NER types the amount MONEY, so it is never a candidate" {
            val turn = resolve(REVENUE_FOR_2000_DOLLARS)

            turn.value("2000 dollars").grounding.kind shouldBe "MONEY"
            turn.asked.shouldBeEmpty()
        }

        "cs „Objednávky v 2025 kusech“ — the preposition belongs to `kusech`; `2025` counts them" {
            val turn = resolve(OBJEDNAVKY_V_2025_KUSECH)

            turn.value("2025").grounding.kind shouldBe "MISC"
            turn.asked.shouldBeEmpty()
        }

        "cs „Prodejna 1042“ — no preposition and no measure: still the code reading" {
            val turn = resolve(PRODEJNA_1042)

            turn.value("1042").grounding.kind shouldBe "MISC"
            turn.gapKinds("1042") shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)
            turn.asked.shouldBeEmpty()
        }

        "cs „Tržby do 2025“ — a range preposition is not in the table: never offered as the year 2025" {
            // The kernel reads the year alone, so „do 2025“ (until 2025) would come back as the
            // calendar year 2025 — a different period, told as an answer.
            val turn = resolve(TRZBY_DO_2025)

            turn.value("2025").grounding.kind shouldBe "MISC"
            turn.asked.shouldBeEmpty()
        }

        // ── the phrasings that already attributed stay exactly as they were ─────────────────────

        listOf(
            MARKETPLACE_REVENUE_IN_2025,
            MARKETPLACE_REVENUE_FOR_2025,
            YEAR_MARKETPLACE_REVENUE,
            REVENUE_2025_BY_CHANNEL,
            STORE_REVENUE_IN_2025,
            TRZBY_Z_TRZISTE_V_ROCE_2025,
            TRZBY_V_2025,
        ).forEach { case ->
            "unchanged: `${case.text}` — NER typed the year a DATE, and it attributes as before" {
                val turn = resolve(case)

                turn.year().shouldBeAnchored()
                turn.gapsOnYear().shouldBeEmpty()
                turn.asked shouldContainExactly listOf("2025")
            }
        }

        // ── numbers that are not a bare year next to a measure are left alone ───────────────────

        "a four-digit CODE after a non-measure stays a G3 and is never offered as a date" {
            // `Revenue of store 1042`: Stanza hangs `1042` off `Revenue`, across `store`. The number
            // stands next to the STORE, and a number after a thing is what names it (#139).
            val turn = resolve(REVENUE_OF_STORE_1042)

            turn.value("1042").grounding.kind shouldBe "MISC"
            turn.value("1042").attributionsList.shouldBeEmpty()
            turn.gapKinds("1042") shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)
            turn.asked.shouldBeEmpty()
        }

        "an entity that only HAS measures is not a measure — a number after it may be its id" {
            // `Marketplace order 2025`: the mention binds the marketplace sales FACT
            // (`entity_with_measures`), and a fact row can be named by a number — order 2025. Only a
            // measure is never named by one, so this stays the code reading it was.
            val turn = resolve(MARKETPLACE_ORDER_2025)

            turn.year().grounding.kind shouldBe "MISC"
            turn.gapsOnYear() shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)
            turn.asked.shouldBeEmpty()
        }

        "`Store sales 2025` — the sales FACT, not a measure: it asks (G3) rather than attach or drop" {
            // The boundary above, on a phrasing people do use: "store sales" binds the store-sales
            // entity (`entity_with_measures`). The year is not attached, but it is not lost either —
            // it is the G3 the turn can ask about.
            val turn = resolve(STORE_SALES_2025)

            turn.year().grounding.kind shouldBe "MISC"
            turn.gapsOnYear() shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)
            turn.asked.shouldBeEmpty()
        }

        "cs „Tržby prodejny 2025“ — after the store, not the measure: still the code reading" {
            val turn = resolve(TRZBY_PRODEJNY_2025)

            turn.value("2025").grounding.kind shouldBe "MISC"
            turn.gapKinds("2025") shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)
            turn.asked.shouldBeEmpty()
        }

        "`Top 10 products by revenue` — a count is not a year" {
            val turn = resolve(TOP_10_PRODUCTS)

            turn.value("10").grounding.kind shouldBe "MISC"
            turn.asked.shouldBeEmpty()
        }

        "`Revenue over 1000000` — an amount is not a year" {
            val turn = resolve(REVENUE_OVER_A_MILLION)

            turn.value("1000000").grounding.kind shouldBe "MONEY"
            turn.asked.shouldBeEmpty()
        }

        "cs „Tržby za účet 501001“ — a six-digit code is not a year" {
            val turn = resolve(TRZBY_ZA_UCET_501001)

            turn.value("501001").grounding.kind shouldBe "MISC"
            turn.asked.shouldBeEmpty()
        }

        "a year the kernel does not anchor goes back to what NER said" {
            // The rule only proposes; the kernel decides. An estate with no date anchor (or a kernel
            // that does not read the span) leaves the lattice exactly as it was before #206.
            val turn = resolve(MARKETPLACE_REVENUE_2025, kernel = UnanchoredKernel())

            turn.year().grounding.kind shouldBe "MISC"
            turn.year().attributionsList.shouldBeEmpty()
            turn.gapsOnYear() shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)
        }

        // ── two dated measures in one question ─────────────────────────────────────────────────

        "two measures: the year right after the one it hangs off attaches — to the one anchor" {
            // `Stock on hand and revenue 2025`: a stock measure and a flow measure. Stanza hangs the
            // year off `revenue` and it stands right after it, so it is that measure's year. The kernel
            // names ONE anchor for the estate, so the attribution is the column `in 2025` gets in the
            // same question; nothing chooses between the two measures' dates.
            val bare = resolve(STOCK_AND_REVENUE_2025)
            val withIn = resolve(STOCK_AND_REVENUE_IN_2025)

            bare.year().shouldBeAnchored()
            bare.year().attributionsList.map { it.attributeRef } shouldBe
                withIn.year().attributionsList.map { it.attributeRef }
        }

        "two measures: hung off one but standing after the other — ambiguous, so it stays G3" {
            // `Revenue and stock on hand 2025`: Stanza hangs `2025` off `Revenue` while it stands after
            // `stock on hand`. Which measure's year it is cannot be told, so it is not attached at all.
            val turn = resolve(REVENUE_AND_STOCK_2025)

            turn.year().grounding.kind shouldBe "MISC"
            turn.gapsOnYear() shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)
            turn.asked.shouldBeEmpty()
        }

        "cs „Stav zásob a tržby 2025“ — the same disagreement in Czech stays G3" {
            val turn = resolve(STAV_ZASOB_A_TRZBY_2025)

            turn.year().grounding.kind shouldBe "MISC"
            turn.gapsOnYear() shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)
        }

        "cs „Tržby a stav zásob 2025“ — right after the measure it hangs off: attached" {
            resolve(TRZBY_A_STAV_ZASOB_2025).year().shouldBeAnchored()
        }
    }) {
    private data class Case(
        val text: String,
        val lang: String,
        val tokens: List<Token>,
        val entities: List<NerEntity>,
    )

    private class Turn(
        val response: ResolveResponse,
        val asked: List<String>,
    ) {
        val state: ResolutionState get() = response.resolutionState

        fun universalTypeOf(text: String): UniversalEntityType =
            response.resolution.bindingsList
                .single { it.hasUniversal() && it.span.text == text }
                .universal.entityType

        fun value(text: String): ValueFinding = state.valuesList.single { it.span.text == text }

        fun year(): ValueFinding = value("2025")

        fun gapKinds(text: String): List<GapKind> = state.gapsList.filter { it.span.text == text }.map { it.kind }

        fun gapsOnYear(): List<GapKind> = gapKinds("2025")
    }

    private companion object {
        const val CATALOG_REVENUE = "er.entity.catalog_sales.ext_sales_price"
        const val STORE_REVENUE = "er.entity.store_sales.ext_sales_price"
        const val CHANNEL_REVENUE = "er.entity.channel_sales.ext_sales_price"
        const val ON_HAND = "er.entity.inventory.quantity_on_hand"
        const val CHANNEL = "er.entity.channel_sales.channel"
        const val STORE = "er.entity.store"
        const val ITEM = "er.entity.item"
        const val CATALOG_SALES = "er.entity.catalog_sales"
        const val STORE_SALES = "er.entity.store_sales"

        /** The ref the kernel's anchor column renders to (`GroundingRung.attributeRef`). */
        const val DATE_ANCHOR = "er.entity.date_dim.cal_date"

        fun ValueFinding.shouldBeAnchored() {
            withClue("the year is a grounded DATE on the anchor column: $this") {
                kind shouldBe ValueKind.VALUE_KIND_GROUNDED
                grounding.kind shouldBe "DATE"
                grounding.kernel shouldBe "chrono"
                grounding.normalizedValue shouldBe "2025-01-01T00:00:00Z/2026-01-01T00:00:00Z"
                attributionsList.map { it.attributeRef } shouldContainExactly listOf(DATE_ANCHOR)
            }
        }

        /**
         * Hartland-shaped vocabulary: the refs and object kinds the estate's archive declares, with the
         * terms these questions need (one type per ref, `category == ref`, anchors = terms).
         */
        val REGISTRY: Registry =
            Registry
                .newBuilder()
                .addEntityTypes(type(CATALOG_REVENUE, "measure", "marketplace revenue", "tržby z tržiště"))
                .addEntityTypes(type(STORE_REVENUE, "measure", "store revenue"))
                .addEntityTypes(type(CHANNEL_REVENUE, "measure", "revenue", "tržby"))
                .addEntityTypes(type(ON_HAND, "measure", "stock on hand", "stav zásob"))
                .addEntityTypes(type(CHANNEL, "attribute", "channel"))
                .addEntityTypes(type(STORE, "entity", "store", "prodejna", "prodejny", "prodejen"))
                .addEntityTypes(type(ITEM, "entity", "products"))
                .addEntityTypes(type(CATALOG_SALES, "entity_with_measures", "marketplace", "marketplace order"))
                .addEntityTypes(type(STORE_SALES, "entity_with_measures", "store sales"))
                .addLocales("en")
                .addLocales("cs")
                .setSnapshotHash("snap-bare-year")
                .build()

        fun type(
            ref: String,
            kind: String,
            vararg terms: String,
        ): EntityType =
            EntityType
                .newBuilder()
                .setRef(ref)
                .addCategories(ref)
                .addAllAnchors(terms.toList())
                .setObjectKind(kind)
                .build()

        // ── the parses ────────────────────────────────────────────────────────────────────────

        /** One token; [head] is 1-based (0 = root), as the parse carries it. */
        fun t(
            text: String,
            start: Int,
            lemma: String,
            upos: String,
            head: Int,
            rel: String,
        ): Token =
            Token
                .newBuilder()
                .setText(text)
                .setCharStart(start)
                .setCharEnd(start + text.length)
                .setLemma(lemma)
                .setUpos(upos)
                .setDepHead(head)
                .setDepRelation(rel)
                .build()

        /** An entity as Stanza hands it over: the OntoNotes label, no normalization. */
        fun stanza(
            text: String,
            start: Int,
            label: String,
        ): NerEntity =
            NerEntity
                .newBuilder()
                .setText(text)
                .setCharStart(start)
                .setCharEnd(start + text.length)
                .setLabel(label)
                .setSourceEngine("stanza")
                .build()

        /** An entity as the nlp front hands NameTag's over: the coarse label, the tag as `cnec:<tag>`. */
        fun nametag(
            text: String,
            start: Int,
            tag: String,
        ): NerEntity =
            NerEntity
                .newBuilder()
                .setText(text)
                .setCharStart(start)
                .setCharEnd(start + text.length)
                .setLabel(if (tag.startsWith("t")) "DATE" else "MISC")
                .setNormalizedValue("cnec:$tag")
                .setSourceEngine("nametag3")
                .build()

        val MARKETPLACE_REVENUE_2025 =
            Case(
                "Marketplace revenue 2025",
                "en",
                listOf(
                    t("Marketplace", 0, "marketplace", "NOUN", 2, "compound"),
                    t("revenue", 12, "revenue", "NOUN", 0, "root"),
                    t("2025", 20, "2025", "NUM", 2, "nmod:unmarked"),
                ),
                listOf(stanza("2025", 20, "CARDINAL")),
            )

        val STORE_REVENUE_2025 =
            Case(
                "Store revenue 2025",
                "en",
                listOf(
                    t("Store", 0, "store", "NOUN", 2, "compound"),
                    t("revenue", 6, "revenue", "NOUN", 0, "root"),
                    t("2025", 14, "2025", "NUM", 2, "nmod:unmarked"),
                ),
                listOf(stanza("2025", 14, "CARDINAL")),
            )

        val MARKETPLACE_REVENUE_COMMA_2025 =
            Case(
                "Marketplace revenue, 2025",
                "en",
                listOf(
                    t("Marketplace", 0, "marketplace", "NOUN", 2, "compound"),
                    t("revenue", 12, "revenue", "NOUN", 0, "root"),
                    t(",", 19, ",", "PUNCT", 4, "punct"),
                    t("2025", 21, "2025", "NUM", 2, "nmod:unmarked"),
                ),
                listOf(stanza("2025", 21, "CARDINAL")),
            )

        val MARKETPLACE_REVENUE_IN_2025 =
            Case(
                "Marketplace revenue in 2025",
                "en",
                listOf(
                    t("Marketplace", 0, "marketplace", "NOUN", 2, "compound"),
                    t("revenue", 12, "revenue", "NOUN", 0, "root"),
                    t("in", 20, "in", "ADP", 4, "case"),
                    t("2025", 23, "2025", "NUM", 2, "nmod"),
                ),
                listOf(stanza("2025", 23, "DATE")),
            )

        val MARKETPLACE_REVENUE_FOR_2025 =
            Case(
                "Marketplace revenue for 2025",
                "en",
                listOf(
                    t("Marketplace", 0, "marketplace", "NOUN", 2, "compound"),
                    t("revenue", 12, "revenue", "NOUN", 0, "root"),
                    t("for", 20, "for", "ADP", 4, "case"),
                    t("2025", 24, "2025", "NUM", 2, "nmod"),
                ),
                listOf(stanza("2025", 24, "DATE")),
            )

        val YEAR_MARKETPLACE_REVENUE =
            Case(
                "2025 marketplace revenue",
                "en",
                listOf(
                    t("2025", 0, "2025", "NUM", 3, "nummod"),
                    t("marketplace", 5, "marketplace", "NOUN", 3, "compound"),
                    t("revenue", 17, "revenue", "NOUN", 0, "root"),
                ),
                listOf(stanza("2025", 0, "DATE")),
            )

        val REVENUE_2025_BY_CHANNEL =
            Case(
                "Revenue 2025 by channel",
                "en",
                listOf(
                    t("Revenue", 0, "revenue", "NOUN", 0, "root"),
                    t("2025", 8, "2025", "NUM", 1, "nummod"),
                    t("by", 13, "by", "ADP", 4, "case"),
                    t("channel", 16, "channel", "NOUN", 1, "nmod"),
                ),
                listOf(stanza("2025", 8, "DATE")),
            )

        val STORE_REVENUE_IN_2025 =
            Case(
                "Store revenue in 2025",
                "en",
                listOf(
                    t("Store", 0, "store", "NOUN", 2, "compound"),
                    t("revenue", 6, "revenue", "NOUN", 0, "root"),
                    t("in", 14, "in", "ADP", 4, "case"),
                    t("2025", 17, "2025", "NUM", 2, "nmod"),
                ),
                listOf(stanza("2025", 17, "DATE")),
            )

        val REVENUE_OF_STORE_1042 =
            Case(
                "Revenue of store 1042",
                "en",
                listOf(
                    t("Revenue", 0, "revenue", "NOUN", 0, "root"),
                    t("of", 8, "of", "ADP", 3, "case"),
                    t("store", 11, "store", "NOUN", 1, "nmod"),
                    t("1042", 17, "1042", "NUM", 1, "nmod:unmarked"),
                ),
                listOf(stanza("1042", 17, "CARDINAL")),
            )

        val MARKETPLACE_ORDER_2025 =
            Case(
                "Marketplace order 2025",
                "en",
                listOf(
                    t("Marketplace", 0, "marketplace", "NOUN", 2, "compound"),
                    t("order", 12, "order", "NOUN", 0, "root"),
                    t("2025", 18, "2025", "NUM", 2, "nmod:unmarked"),
                ),
                listOf(stanza("2025", 18, "CARDINAL")),
            )

        val STORE_SALES_2025 =
            Case(
                "Store sales 2025",
                "en",
                listOf(
                    t("Store", 0, "store", "NOUN", 2, "compound"),
                    t("sales", 6, "sale", "NOUN", 0, "root"),
                    t("2025", 12, "2025", "NUM", 2, "nmod:unmarked"),
                ),
                listOf(stanza("2025", 12, "CARDINAL")),
            )

        val TOP_10_PRODUCTS =
            Case(
                "Top 10 products by revenue",
                "en",
                listOf(
                    t("Top", 0, "top", "ADJ", 3, "amod"),
                    t("10", 4, "10", "NUM", 3, "nummod"),
                    t("products", 7, "product", "NOUN", 0, "root"),
                    t("by", 16, "by", "ADP", 5, "case"),
                    t("revenue", 19, "revenue", "NOUN", 3, "nmod"),
                ),
                listOf(stanza("10", 4, "CARDINAL")),
            )

        val REVENUE_OVER_A_MILLION =
            Case(
                "Revenue over 1000000",
                "en",
                listOf(
                    t("Revenue", 0, "revenue", "NOUN", 0, "root"),
                    t("over", 8, "over", "ADP", 3, "case"),
                    t("1000000", 13, "1000000", "NUM", 1, "nmod"),
                ),
                listOf(stanza("1000000", 13, "MONEY")),
            )

        val STOCK_AND_REVENUE_2025 =
            Case(
                "Stock on hand and revenue 2025",
                "en",
                listOf(
                    t("Stock", 0, "stock", "NOUN", 0, "root"),
                    t("on", 6, "on", "ADP", 3, "case"),
                    t("hand", 9, "hand", "NOUN", 1, "nmod"),
                    t("and", 14, "and", "CCONJ", 5, "cc"),
                    t("revenue", 18, "revenue", "NOUN", 3, "conj"),
                    t("2025", 26, "2025", "NUM", 5, "nummod"),
                ),
                listOf(stanza("2025", 26, "CARDINAL")),
            )

        val STOCK_AND_REVENUE_IN_2025 =
            Case(
                "Stock on hand and revenue in 2025",
                "en",
                listOf(
                    t("Stock", 0, "stock", "NOUN", 0, "root"),
                    t("on", 6, "on", "ADP", 3, "case"),
                    t("hand", 9, "hand", "NOUN", 1, "nmod"),
                    t("and", 14, "and", "CCONJ", 5, "cc"),
                    t("revenue", 18, "revenue", "NOUN", 3, "conj"),
                    t("in", 26, "in", "ADP", 7, "case"),
                    t("2025", 29, "2025", "NUM", 1, "nmod"),
                ),
                listOf(stanza("2025", 29, "DATE")),
            )

        val REVENUE_AND_STOCK_2025 =
            Case(
                "Revenue and stock on hand 2025",
                "en",
                listOf(
                    t("Revenue", 0, "revenue", "NOUN", 0, "root"),
                    t("and", 8, "and", "CCONJ", 3, "cc"),
                    t("stock", 12, "stock", "NOUN", 1, "conj"),
                    t("on", 18, "on", "ADP", 5, "case"),
                    t("hand", 21, "hand", "NOUN", 1, "nmod"),
                    t("2025", 26, "2025", "NUM", 1, "nmod:unmarked"),
                ),
                listOf(stanza("2025", 26, "CARDINAL")),
            )

        val TRZBY_Z_TRZISTE_2025 =
            Case(
                "Tržby z tržiště 2025",
                "cs",
                listOf(
                    t("Tržby", 0, "tržba", "NOUN", 0, "root"),
                    t("z", 6, "z", "ADP", 3, "case"),
                    t("tržiště", 8, "tržiště", "NOUN", 1, "nmod"),
                    t("2025", 16, "2025", "NUM", 3, "nummod"),
                ),
                listOf(nametag("2025", 16, "n_")),
            )

        val TRZBY_2025 =
            Case(
                "Tržby 2025",
                "cs",
                listOf(
                    t("Tržby", 0, "tržba", "NOUN", 0, "root"),
                    t("2025", 6, "2025", "NUM", 1, "nummod"),
                ),
                listOf(nametag("2025", 6, "nc")),
            )

        val TRZBY_Z_TRZISTE_V_ROCE_2025 =
            Case(
                "Tržby z tržiště v roce 2025",
                "cs",
                listOf(
                    t("Tržby", 0, "tržba", "NOUN", 0, "root"),
                    t("z", 6, "z", "ADP", 3, "case"),
                    t("tržiště", 8, "tržiště", "NOUN", 1, "nmod"),
                    t("v", 16, "v", "ADP", 5, "case"),
                    t("roce", 18, "rok", "NOUN", 1, "nmod"),
                    t("2025", 23, "2025", "NUM", 5, "nummod"),
                ),
                listOf(nametag("2025", 23, "ty")),
            )

        val TRZBY_Z_PRODEJEN_ZA_2025 =
            Case(
                "Tržby z prodejen za 2025",
                "cs",
                listOf(
                    t("Tržby", 0, "tržba", "NOUN", 0, "root"),
                    t("z", 6, "z", "ADP", 3, "case"),
                    t("prodejen", 8, "prodejna", "NOUN", 1, "nmod"),
                    t("za", 17, "za", "ADP", 5, "case"),
                    t("2025", 20, "2025", "NUM", 1, "dep"),
                ),
                listOf(nametag("2025", 20, "nc")),
            )

        val TRZBY_Z_TRZISTE_ZA_2025 =
            Case(
                "Tržby z tržiště za 2025",
                "cs",
                listOf(
                    t("Tržby", 0, "tržba", "NOUN", 0, "root"),
                    t("z", 6, "z", "ADP", 3, "case"),
                    t("tržiště", 8, "tržiště", "NOUN", 1, "nmod"),
                    t("za", 16, "za", "ADP", 5, "case"),
                    t("2025", 19, "2025", "NUM", 1, "dep"),
                ),
                listOf(nametag("2025", 19, "nc")),
            )

        val TRZBY_VE_2025 =
            Case(
                "Tržby ve 2025",
                "cs",
                listOf(
                    t("Tržby", 0, "tržba", "NOUN", 0, "root"),
                    t("ve", 6, "v", "ADP", 3, "case"),
                    t("2025", 9, "2025", "NUM", 1, "nummod"),
                ),
                listOf(nametag("2025", 9, "nc")),
            )

        val TRZBY_BEHEM_2025 =
            Case(
                "Tržby během 2025",
                "cs",
                listOf(
                    t("Tržby", 0, "tržba", "NOUN", 0, "root"),
                    t("během", 6, "během", "ADP", 3, "case"),
                    t("2025", 12, "2025", "NUM", 1, "nummod"),
                ),
                listOf(nametag("2025", 12, "nc")),
            )

        val TRZBY_V_2025 =
            Case(
                "Tržby v 2025",
                "cs",
                listOf(
                    t("Tržby", 0, "tržba", "NOUN", 0, "root"),
                    t("v", 6, "v", "ADP", 3, "case"),
                    t("2025", 8, "2025", "NUM", 1, "nummod"),
                ),
                listOf(nametag("2025", 8, "ty")),
            )

        val TRZBY_DO_2025 =
            Case(
                "Tržby do 2025",
                "cs",
                listOf(
                    t("Tržby", 0, "tržba", "NOUN", 0, "root"),
                    t("do", 6, "do", "ADP", 3, "case"),
                    t("2025", 9, "2025", "NUM", 1, "nummod"),
                ),
                listOf(nametag("2025", 9, "nc")),
            )

        val TRZBY_ZA_2000_KC =
            Case(
                "Tržby za 2000 Kč",
                "cs",
                listOf(
                    t("Tržby", 0, "tržba", "NOUN", 0, "root"),
                    t("za", 6, "za", "ADP", 3, "case"),
                    t("2000", 9, "2000", "NUM", 1, "nummod"),
                    t("Kč", 14, "Kč", "NOUN", 3, "nmod"),
                ),
                listOf(nametag("2000", 9, "nc"), nametag("Kč", 14, "om")),
            )

        val TRZBY_ZA_1500_KC =
            Case(
                "Tržby za 1500 Kč",
                "cs",
                listOf(
                    t("Tržby", 0, "tržba", "NOUN", 0, "root"),
                    t("za", 6, "za", "ADP", 3, "case"),
                    t("1500", 9, "1500", "NUM", 1, "dep"),
                    t("Kč", 14, "Kč", "NOUN", 3, "nmod"),
                ),
                listOf(nametag("1500", 9, "nc"), nametag("Kč", 14, "om")),
            )

        val OBJEDNAVKY_V_2025_KUSECH =
            Case(
                "Objednávky v 2025 kusech",
                "cs",
                listOf(
                    t("Objednávky", 0, "objednávka", "NOUN", 0, "root"),
                    t("v", 11, "v", "ADP", 4, "case"),
                    t("2025", 13, "2025", "NUM", 4, "nummod"),
                    t("kusech", 18, "kus", "NOUN", 1, "nmod"),
                ),
                listOf(nametag("2025", 13, "nc")),
            )

        val PRODEJNA_1042 =
            Case(
                "Prodejna 1042",
                "cs",
                listOf(
                    t("Prodejna", 0, "prodejna", "NOUN", 0, "root"),
                    t("1042", 9, "1042", "NUM", 1, "nummod"),
                ),
                listOf(nametag("1042", 9, "n_")),
            )

        val REVENUE_FOR_2000_DOLLARS =
            Case(
                "Revenue for 2000 dollars",
                "en",
                listOf(
                    t("Revenue", 0, "revenue", "NOUN", 0, "root"),
                    t("for", 8, "for", "ADP", 4, "case"),
                    t("2000", 12, "2000", "NUM", 4, "nummod"),
                    t("dollars", 17, "dollar", "NOUN", 1, "nmod"),
                ),
                listOf(stanza("2000 dollars", 12, "MONEY")),
            )

        val TRZBY_PRODEJNY_2025 =
            Case(
                "Tržby prodejny 2025",
                "cs",
                listOf(
                    t("Tržby", 0, "tržba", "NOUN", 0, "root"),
                    t("prodejny", 6, "prodejna", "NOUN", 1, "nmod"),
                    t("2025", 15, "2025", "NUM", 2, "nummod"),
                ),
                listOf(nametag("2025", 15, "nc")),
            )

        val TRZBY_ZA_UCET_501001 =
            Case(
                "Tržby za účet 501001",
                "cs",
                listOf(
                    t("Tržby", 0, "tržba", "NOUN", 0, "root"),
                    t("za", 6, "za", "ADP", 3, "case"),
                    t("účet", 9, "účet", "NOUN", 1, "nmod"),
                    t("501001", 14, "501001", "NUM", 3, "nummod"),
                ),
                listOf(nametag("501001", 14, "n_")),
            )

        val STAV_ZASOB_A_TRZBY_2025 =
            Case(
                "Stav zásob a tržby 2025",
                "cs",
                listOf(
                    t("Stav", 0, "stav", "NOUN", 0, "root"),
                    t("zásob", 5, "zásoba", "NOUN", 1, "nmod"),
                    t("a", 11, "a", "CCONJ", 4, "cc"),
                    t("tržby", 13, "tržba", "NOUN", 1, "conj"),
                    t("2025", 19, "2025", "NUM", 1, "dep"),
                ),
                listOf(nametag("2025", 19, "n_")),
            )

        val TRZBY_A_STAV_ZASOB_2025 =
            Case(
                "Tržby a stav zásob 2025",
                "cs",
                listOf(
                    t("Tržby", 0, "tržba", "NOUN", 0, "root"),
                    t("a", 6, "a", "CCONJ", 3, "cc"),
                    t("stav", 8, "stav", "NOUN", 1, "conj"),
                    t("zásob", 13, "zásoba", "NOUN", 1, "nmod"),
                    t("2025", 19, "2025", "NUM", 4, "nummod"),
                ),
                listOf(nametag("2025", 19, "n_")),
            )

        // ── the services ──────────────────────────────────────────────────────────────────────

        fun resolve(
            case: Case,
            kernel: RecordingKernel = YearKernel(),
        ): Turn {
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage(case.lang)
                    .setDetectedLanguage(case.lang)
                    .setTraceId("bare-year")
                    .addAllTokens(case.tokens)
                    .addAllEntities(case.entities)
                    .build()
            case.tokens.forEach {
                check(case.text.substring(it.charStart, it.charEnd) == it.text) { "offsets of '${it.text}'" }
            }
            val pipeline =
                ResolverPipeline(
                    FakeNlp(parse, case.lang),
                    TermFuzzy(REGISTRY),
                    SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), ResolverThresholds.LIVE),
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
                            .setConversationId("bare-year")
                            .setFresh(FreshQuestion.newBuilder().setText(case.text).setLocale(case.lang))
                            .setContext(ResolveContext.newBuilder().setReferenceDatetime("2026-10-13T00:00:00Z"))
                            .setRegistry(REGISTRY)
                            .build(),
                    )
                }
            return Turn(response, kernel.seen.map { it.spanText })
        }

        /**
         * The estate's vocabulary, matched the way an EXACT declared term matches: a span binds the
         * refs it was asked about whose term IS its text. Nothing else binds, so a span the estate
         * does not name stays unbound, as it would live.
         */
        class TermFuzzy(
            registry: Registry,
        ) : FuzzyClient {
            private val terms = registry.entityTypesList.associate { it.ref to it.anchorsList.map(String::lowercase) }

            override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
                val builder = BatchMatchResponse.newBuilder()
                for (span in request.spansList) {
                    val query = span.query.lowercase()
                    val matches =
                        span.categoriesList
                            .filter { query in terms[it].orEmpty() }
                            .map { ref ->
                                FuzzyMatch
                                    .newBuilder()
                                    .setCandidateId("lex:$ref")
                                    .setCandidate(span.query)
                                    .setScore(1.0)
                                    .setCategory(ref)
                                    .setTargetRef(ref)
                                    .setSource(SourceTag.DECLARED)
                                    .setMatchMethod("EXACT")
                                    .setProvenance(Provenance.newBuilder().setProducer("fuzzy").setMethod("TATRMAN"))
                                    .build()
                            }
                    builder.addResults(FuzzyMatchResponse.newBuilder().addAllMatches(matches))
                }
                return builder.build()
            }

            override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
        }

        abstract class RecordingKernel : GroundingClient {
            val seen = mutableListOf<GroundRequest>()

            abstract fun answer(request: GroundRequest): GroundResponse

            override suspend fun ground(request: GroundRequest): GroundResponse {
                seen += request
                return answer(request)
            }

            override suspend fun getStatus(): GetStatusResponse = GetStatusResponse.getDefaultInstance()
        }

        /**
         * chrono on a four-digit year: the calendar year, on the estate's event-date column — the
         * anchor shape quoted from the live wire (`GroundingRungTest`). Anything else it does not read.
         */
        class YearKernel : RecordingKernel() {
            override fun answer(request: GroundRequest): GroundResponse {
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
        }

        /** chrono reading the year but naming no column: an estate that declares no event date. */
        class UnanchoredKernel : RecordingKernel() {
            override fun answer(request: GroundRequest): GroundResponse =
                GroundResponse
                    .newBuilder()
                    .setStatus(GroundResponse.Status.OK)
                    .setResult(
                        GroundingResult.newBuilder().setNormalized(
                            Normalized.newBuilder().setInterval(
                                DateTimeInterval
                                    .newBuilder()
                                    .setStart("2025-01-01T00:00:00Z")
                                    .setEnd("2026-01-01T00:00:00Z"),
                            ),
                        ),
                    ).build()
        }

        class FakeNlp(
            private val parse: AnalyzeResponse,
            private val lang: String,
        ) : NlpClient {
            override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse = parse

            override suspend fun getStatus(): StatusResponse =
                StatusResponse
                    .newBuilder()
                    .setReady(true)
                    .addCapabilities(Capability.newBuilder().setLanguage(lang).setOp(NlpOp.NER))
                    .addCapabilities(Capability.newBuilder().setLanguage(lang).setOp(NlpOp.DEP_PARSE))
                    .build()
        }
    }
}
