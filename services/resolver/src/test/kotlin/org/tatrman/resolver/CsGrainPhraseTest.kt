// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.pipeline.FrameRolePreps
import org.tatrman.resolver.pipeline.DomainSpanCandidate
import org.tatrman.resolver.pipeline.FrameRoles
import org.tatrman.resolver.pipeline.SpanProposal
import org.tatrman.resolver.v1.EntityType
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.TargetClass

private const val MONTH = "er.entity.date_dim.month"
private const val REVENUE = "er.entity.catalog_sales.ext_sales_price"

private val GROUPING = FrameRole.FRAME_ROLE_GROUPING
private val FILTER = FrameRole.FRAME_ROLE_FILTER

private fun tok(
    text: String,
    start: Int,
    lemma: String,
    upos: String,
    depHead: Int,
    depRelation: String,
    number: String? = null,
): Token {
    val b = MhMembers.tok(text, start, start + text.length, lemma, upos, depHead, depRelation).toBuilder()
    if (number != null) b.putFeats("Number", number)
    return b.build()
}

/**
 * „Tržby z tržiště v roce 2025 po měsících“, the live MorphoDiTa + UDPipe shape (hartland CZ world,
 * 2026-10-09): `po` is `case` under `měsících`, which hangs off `Tržby`; lemmas are dictionary forms,
 * so `Tržby` → `tržba` and `měsících` → `měsíc`.
 */
private fun byMonth(
    month: String = "měsících",
    monthLemma: String = "měsíc",
    number: String? = "Plur",
): Array<Token> =
    arrayOf(
        tok("Tržby", 0, "tržba", "NOUN", 0, "root", "Plur"),
        tok("z", 6, "z", "ADP", 3, "case"),
        tok("tržiště", 8, "tržiště", "NOUN", 1, "nmod", "Sing"),
        tok("v", 16, "v", "ADP", 5, "case"),
        tok("roce", 18, "rok", "NOUN", 1, "nmod", "Sing"),
        tok("2025", 23, "2025", "NUM", 5, "nummod"),
        tok("po", 28, "po", "ADP", 8, "case"),
        tok(month, 31, monthLemma, "NOUN", 1, "nmod", number),
    )

private fun parse(tokens: Array<Token>): AnalyzeResponse =
    AnalyzeResponse
        .newBuilder()
        .setLanguage("cs")
        .addAllTokens(tokens.toList())
        .build()

/**
 * cs LR S5 — the Czech grain phrases, found on the first CZ-world smoke: „… po měsících“ answered one
 * total, and the undiacritised „… po mesicich“ failed in the database (`month = ''`).
 *
 *  - **The anchor index matches a declared word by its SURFACE as well as its lemma.** The estate
 *    declared `po měsících`; a lemmatising parser reads `po měsíc`, and the phrase never formed.
 *  - **R3' decides cs `po`** — per-X (GROUPING) or after-X (FILTER) — from two signals: A, the
 *    estate wrote the preposition into the phrase; B, the noun's grammatical number. Agreement or
 *    one voice decides; disagreement leaves BOTH roles, the "undecided" a consumer routes onward.
 */
class CsGrainPhraseTest :
    StringSpec({

        // ── the anchor index: surface OR lemma ────────────────────────────────────────────────

        val month = ResolverEntityType(ref = MONTH, categories = listOf(MONTH), anchors = listOf("po měsících"))
        val revenue =
            ResolverEntityType(
                ref = REVENUE,
                categories = listOf(REVENUE),
                // the surface form only — the estate's own `tržba z tržiště` lemma twin is not needed
                anchors = listOf("tržby z tržiště"),
                objectKind = "measure",
            )

        "a declared inflected phrase is matched by the words the user wrote, not only by their lemmas" {
            val anchored =
                SpanProposal
                    .proposeDomainSpans(parse(byMonth()), listOf(month, revenue))
                    .filter { it.origin == DomainSpanCandidate.Origin.ANCHOR_PHRASE }
                    .associate { it.text to it.gatedEntityRefs }

            anchored["po měsících"] shouldBe listOf(MONTH)
            anchored["Tržby z tržiště"] shouldBe listOf(REVENUE)
        }

        "a lemma-form declaration still matches, as it always did" {
            val lemmaForm = month.copy(anchors = listOf("po měsíc"))
            SpanProposal
                .proposeDomainSpans(parse(byMonth()), listOf(lemmaForm))
                .filter { it.origin == DomainSpanCandidate.Origin.ANCHOR_PHRASE }
                .map { it.text } shouldContainExactly listOf("po měsících")
        }

        // ── R3' — the distributive `po`, signal by signal ─────────────────────────────────────

        val preps = FrameRolePreps.shipped()

        fun roles(
            tokens: Array<Token>,
            charStart: Int = 28,
            anchorsValue: Boolean = false,
            targetClass: TargetClass = TargetClass.TARGET_CLASS_MODEL_OBJECT,
        ): List<FrameRole> {
            val head = tokens.lastIndex
            return FrameRoles
                .derive(
                    listOf(
                        FrameRoles.Input(
                            id = "m",
                            charStart = charStart,
                            headToken = head,
                            targetClass = targetClass,
                            objectKind = "attribute",
                            anchorsValue = anchorsValue,
                            charEnd = tokens[head].charEnd,
                        ),
                    ),
                    parse(tokens),
                    "cs",
                    preps,
                ).getValue("m")
        }

        "A and B agree — the declared `po měsících`, plural — GROUPING" {
            roles(byMonth()) shouldContainExactly listOf(GROUPING)
        }

        "A alone — `po mesicich` without diacritics, no number from the tagger — GROUPING" {
            roles(byMonth("mesicich", "mesicich", number = null)) shouldContainExactly listOf(GROUPING)
        }

        "B alone — the preposition outside the mention, plural — GROUPING" {
            // `měsících` matched on its own (charStart at the noun, `po` outside the extent)
            roles(byMonth(), charStart = 31) shouldContainExactly listOf(GROUPING)
        }

        "B alone — singular — FILTER, the after-X reading" {
            roles(byMonth("měsíci", number = "Sing"), charStart = 31) shouldContainExactly listOf(FILTER)
        }

        "A and B DISAGREE — declared, but singular — both roles: the resolver does not guess" {
            roles(byMonth("měsíci", number = "Sing")) shouldContainExactlyInAnyOrder listOf(GROUPING, FILTER)
        }

        "neither speaks — undeclared, no number — FILTER, exactly as before R3'" {
            roles(byMonth("mesicich", "mesicich", number = null), charStart = 31) shouldContainExactly listOf(FILTER)
        }

        "A is silent when a value hangs on the mention — then B alone decides" {
            roles(byMonth("měsíci", number = "Sing"), anchorsValue = true) shouldContainExactly listOf(FILTER)
        }

        "a grounding trigger under `po` keeps R4's FILTER — „po roce 2020“ is after the year" {
            roles(byMonth(), targetClass = TargetClass.TARGET_CLASS_GROUNDING_TRIGGER) shouldContainExactly
                listOf(FILTER)
        }

        "an English sentence never reads `po`: the table is per language" {
            FrameRoles
                .derive(
                    listOf(
                        FrameRoles.Input("m", 28, 7, TargetClass.TARGET_CLASS_MODEL_OBJECT, "attribute", false, 39),
                    ),
                    parse(byMonth()),
                    "en",
                    preps,
                ).getValue("m") shouldNotContainAnyOf listOf(GROUPING, FILTER)
        }

        // ── through the pipeline: the lattice hands R3' the mention's extent ──────────────────

        fun et(
            ref: String,
            anchors: List<String>,
            kind: String,
            owner: String = "",
        ): EntityType =
            EntityType
                .newBuilder()
                .setRef(ref)
                .addCategories(ref)
                .addAllAnchors(anchors)
                .setObjectKind(kind)
                .setOwnerRef(owner)
                .build()

        val registry =
            Registry
                .newBuilder()
                .addEntityTypes(et(MONTH, listOf("po měsících"), "attribute", owner = "er.entity.date_dim"))
                .addEntityTypes(et(REVENUE, listOf("tržby z tržiště"), "measure", owner = "er.entity.catalog_sales"))
                .addLocales("cs")
                .setSnapshotHash("snap-cs-grain")
                .build()

        // NameTag types the year (`cnec:ty`), so it is grounded, not a literal anchored on the phrase
        val year = listOf(MhMembers.ner("2025", 23, 27, "DATE", "cnec:ty"))

        fun monthRoles(tokens: Array<Token>): List<FrameRole> =
            MhMembers
                .resolve(
                    "Tržby z tržiště v roce 2025 po měsících",
                    tokens,
                    lang = "cs",
                    registry = registry,
                    entities = year,
                ).resolutionState.mentionsList
                .single { m -> m.bindingsList.any { it.ref == MONTH } }
                .frameRolesList

        "the live question: `po měsících` reaches the lattice bound to the month, as a GROUPING" {
            monthRoles(byMonth()) shouldContainExactly listOf(GROUPING)
        }

        "the undiacritised question: A alone carries it, so the lattice must pass the mention's extent" {
            monthRoles(byMonth("mesicich", "mesicich", number = null)) shouldContainExactly listOf(GROUPING)
        }
    })
