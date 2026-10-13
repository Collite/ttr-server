// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.Provenance
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.fuzzy.v1.TokenHit
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.EvidenceClasses
import org.tatrman.resolver.v1.EvidenceClass

/**
 * RV-P2.2.T2 — method + layer + anchoring → evidence class (RV-32 feeding RV-14).
 *
 * The derivation reads three facts a P1.4 matcher row now carries and the pre-RV one did not: the
 * **authored method** the estate declared for that row, the **layer** it came from, and — from the
 * core's own span proposal — whether the span was **anchored**. Score enters in exactly two places
 * and neither is a blend: the matcher's own bind floor, and the class floor that separates
 * "similar enough to be evidence" from WEAK on the one layer that authored no method at all.
 */
class EvidenceClassesTest :
    StringSpec({

        val thresholds = ResolverThresholds.LIVE

        "an authored EXACT method that hit is the EXACT class, whatever layer declared it" {
            EvidenceClasses.of(
                fm(source = SourceTag.DECLARED, score = 1.0, method = "EXACT", targetRef = "md.dimension.Account"),
                anchored = true,
                thresholds = thresholds,
            ) shouldBe EvidenceClass.EVIDENCE_CLASS_EXACT
        }

        "a declared-entry TYPOS hit is a DECLARED_ALIAS — the estate vouched for the term, not the surface" {
            EvidenceClasses.of(
                fm(source = SourceTag.DECLARED, score = 0.86, method = "TYPOS(1)", targetRef = "ground:chrono"),
                anchored = true,
                thresholds = thresholds,
            ) shouldBe EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS
        }

        "a declared TYPOS hit stays DECLARED_ALIAS below the class floor: TYPOS(n) already bounded it" {
            // `rok`~`roce` is one edit on a three-letter word, which scores far lower than a one-edit
            // difference on a long one. The estate authored TYPOS(1) and the matcher enforced it; a
            // similarity floor applied here would silently overrule the estate's own rule, and short
            // Czech anchor words are precisely where it would bite.
            EvidenceClasses.of(
                fm(source = SourceTag.METADATA, score = 0.67, method = "TYPOS(1)", targetRef = "md.dim.Calendar.year"),
                anchored = true,
                thresholds = thresholds,
            ) shouldBe EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS
        }

        "an overlay hit is a LEARNED_ALIAS — one confirmation activates it, promotion is what raises it" {
            EvidenceClasses.of(
                fm(source = SourceTag.LEARNED, score = 0.95, method = "TYPOS(1)", targetRef = "md.measure.cost"),
                anchored = true,
                thresholds = thresholds,
            ) shouldBe EvidenceClass.EVIDENCE_CLASS_LEARNED_ALIAS
        }

        "a member value that matched itself is EXACT: a data PK needs no alias to vouch for it" {
            EvidenceClasses.of(
                fm(source = SourceTag.MEMBER, score = 1.0),
                anchored = true,
                thresholds = thresholds,
            ) shouldBe EvidenceClass.EVIDENCE_CLASS_EXACT
        }

        "a member fuzzy hit WITH an anchor is ANCHORED_FUZZY_STRONG" {
            EvidenceClasses.of(
                fm(source = SourceTag.MEMBER, score = 0.72),
                anchored = true,
                thresholds = thresholds,
            ) shouldBe EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG
        }

        "the same hit WITHOUT an anchor is UNANCHORED_FUZZY_STRONG — nothing scoped the lookup" {
            EvidenceClasses.of(
                fm(source = SourceTag.MEMBER, score = 0.72),
                anchored = false,
                thresholds = thresholds,
            ) shouldBe EvidenceClass.EVIDENCE_CLASS_UNANCHORED_FUZZY_STRONG
        }

        "below the class floor a member hit is WEAK — this is issues.md's 0.667 středisko garbage" {
            EvidenceClasses.of(
                fm(source = SourceTag.MEMBER, score = 0.667, candidateId = "5AU 5001"),
                anchored = true,
                thresholds = thresholds,
            ) shouldBe EvidenceClass.EVIDENCE_CLASS_WEAK
        }

        "below the matcher's own bind floor everything is WEAK, whatever authored it" {
            EvidenceClasses.of(
                fm(source = SourceTag.DECLARED, score = 0.40, method = "EXACT", targetRef = "er.branch"),
                anchored = true,
                thresholds = thresholds,
            ) shouldBe EvidenceClass.EVIDENCE_CLASS_WEAK
        }

        "a TOKENS hit the matcher reported NON-discriminative is WEAK, however well it scored" {
            EvidenceClasses.of(
                fm(
                    source = SourceTag.DECLARED,
                    score = 0.99,
                    method = "TOKENS",
                    targetRef = "er.product",
                    autoBindable = false,
                    uniquenessMargin = 0.01,
                ),
                anchored = true,
                thresholds = thresholds,
            ) shouldBe EvidenceClass.EVIDENCE_CLASS_WEAK
        }

        "a TOKENS hit the matcher reported discriminative keeps its layer's class" {
            EvidenceClasses.of(
                fm(
                    source = SourceTag.DECLARED,
                    score = 0.90,
                    method = "TOKENS",
                    targetRef = "er.product",
                    autoBindable = true,
                    uniquenessMargin = 0.45,
                ),
                anchored = true,
                thresholds = thresholds,
            ) shouldBe EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS
        }

        "an ABSENT auto_bindable is not a false one: no uniqueness decision applies, so nothing is demoted" {
            EvidenceClasses.of(
                fm(source = SourceTag.DECLARED, score = 0.90, method = "TOKENS", targetRef = "er.product"),
                anchored = true,
                thresholds = thresholds,
            ) shouldBe EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS
        }

        "a declared row with NO authored method keeps the pre-RV reading: DECLARED_ALIAS, never EXACT" {
            // The under-claiming direction the P2.1 mapper chose deliberately. A snapshot built before
            // the 0.12 grammar carries no method at all, and inferring EXACT from a 1.0 score would
            // hand the strongest class in the order to the one row that never declared anything.
            EvidenceClasses.of(
                fm(source = SourceTag.DECLARED, score = 1.0, targetRef = "er.branch"),
                anchored = true,
                thresholds = thresholds,
            ) shouldBe EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS
        }

        // ── ttr-server#208 — a misspelt word of a longer member value is not that value ─────────────

        "#208 — a typo on one of two equally telling words is WEAK, though it scored over the floor" {
            // `Allen` → the product `ablen stpriationought`: one edit on `ablen`, `stpriationought`
            // untouched, each word in one product only — coverage exactly one half.
            val row = member(0.855, "ablen stpriationought", coverage = 0.5, hit("allen", "ablen", "typo", 1))

            EvidenceClasses.of(row, anchored = false, thresholds = thresholds) shouldBe
                EvidenceClass.EVIDENCE_CLASS_WEAK
            // …and an anchor does not vouch for the other half of the name either
            EvidenceClasses.of(row, anchored = true, thresholds = thresholds) shouldBe EvidenceClass.EVIDENCE_CLASS_WEAK
        }

        "#208 — a typo on one word of three is WEAK" {
            EvidenceClasses.of(
                member(0.853, "Hradec Králové DC", coverage = 0.40, hit("hradek", "hradec", "typo", 1)),
                anchored = false,
                thresholds = thresholds,
            ) shouldBe EvidenceClass.EVIDENCE_CLASS_WEAK
        }

        "#208 guard — a typo on the word that carries most of the value keeps its class (`Alentown` → Allentown DC)" {
            // `DC` ends every warehouse name, so `allentown` is most of what tells this one apart.
            EvidenceClasses.of(
                member(0.857, "Allentown DC", coverage = 0.677, hit("alentown", "allentown", "typo", 1)),
                anchored = false,
                thresholds = thresholds,
            ) shouldBe EvidenceClass.EVIDENCE_CLASS_UNANCHORED_FUZZY_STRONG
        }

        "#208 guard — a typo over the whole value keeps its class (`Nashvile` → Nashville)" {
            EvidenceClasses.of(
                member(0.86, "Nashville", coverage = 1.0, hit("nashvile", "nashville", "typo", 1)),
                anchored = true,
                thresholds = thresholds,
            ) shouldBe EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG
        }

        "#208 guard — an exact word vouches for a typo beside it, even when part of the value is left out" {
            // `Voltaic Monitr` → `Voltaic Monitor MX-OGD`: the model number is not typed, `voltaic` is.
            EvidenceClasses.of(
                member(
                    0.93,
                    "Voltaic Monitor MX-OGD",
                    coverage = 0.45,
                    hit("voltaic", "voltaic", "exact", 0),
                    hit("monitr", "monitor", "typo", 1),
                ),
                anchored = false,
                thresholds = thresholds,
            ) shouldBe EvidenceClass.EVIDENCE_CLASS_UNANCHORED_FUZZY_STRONG
        }

        "#208 guard — a prefix is not a typo: the start of the word, typed right (`Allen` → Allentown DC)" {
            EvidenceClasses.of(
                member(0.867, "Allentown DC", coverage = 0.5, hit("allen", "allentown", "prefix", 4)),
                anchored = false,
                thresholds = thresholds,
            ) shouldBe EvidenceClass.EVIDENCE_CLASS_UNANCHORED_FUZZY_STRONG
        }

        "#208 guard — a row with no v2 provenance is judged on its score, as before" {
            // the v1 engine sends neither hits nor coverage; hits without a coverage cannot say what is left out
            EvidenceClasses.of(member(0.855, "ablen stpriationought", coverage = null), false, thresholds) shouldBe
                EvidenceClass.EVIDENCE_CLASS_UNANCHORED_FUZZY_STRONG
            EvidenceClasses.of(
                member(0.855, "ablen stpriationought", coverage = null, hit("allen", "ablen", "typo", 1)),
                anchored = false,
                thresholds = thresholds,
            ) shouldBe EvidenceClass.EVIDENCE_CLASS_UNANCHORED_FUZZY_STRONG
        }

        "#208 guard — a declared row is the estate's own rule, and the rule is the data layer's only" {
            val declared =
                member(0.855, "net revenue", coverage = 0.5, hit("revenu", "revenue", "typo", 1))
                    .toBuilder()
                    .setSource(SourceTag.DECLARED)
                    .setMatchMethod("TOKENS")
                    .setTargetRef("md.measure.net_revenue")
                    .build()

            EvidenceClasses.of(declared, anchored = true, thresholds = thresholds) shouldBe
                EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS
        }
    }) {
    private companion object {
        /** A v2 MEMBER row: its score, its per-word hits, and its candidate coverage `C` (null = absent). */
        private fun member(
            score: Double,
            value: String,
            coverage: Double?,
            vararg hits: TokenHit,
        ): FuzzyMatch {
            val provenance =
                Provenance
                    .newBuilder()
                    .setProducer("fuzzy")
                    .setMethod("TATRMAN_V2")
                    .setRawScore(score)
                    .addAllTokenHits(hits.toList())
            if (coverage != null) provenance.coverage = coverage
            return FuzzyMatch
                .newBuilder()
                .setCandidateId(value)
                .setCandidate(value)
                .setScore(score)
                .setCategory("er.entity.warehouse.warehouse_name")
                .setSource(SourceTag.MEMBER)
                .setMatchMethod("TOKENS")
                .setProvenance(provenance)
                .build()
        }

        private fun hit(
            query: String,
            candidate: String,
            kind: String,
            distance: Int,
        ): TokenHit =
            TokenHit
                .newBuilder()
                .setQueryToken(query)
                .setCandidateToken(candidate)
                .setKind(kind)
                .setDistance(distance)
                .build()

        private fun fm(
            source: SourceTag,
            score: Double,
            method: String? = null,
            targetRef: String = "",
            candidateId: String = "c1",
            autoBindable: Boolean? = null,
            uniquenessMargin: Double? = null,
        ): FuzzyMatch {
            val b =
                FuzzyMatch
                    .newBuilder()
                    .setCandidateId(candidateId)
                    .setCandidate(candidateId)
                    .setScore(score)
                    .setCategory("md.dimension.Account.code")
                    .setSource(source)
            if (method != null) b.matchMethod = method
            if (targetRef.isNotBlank()) b.targetRef = targetRef
            if (autoBindable != null) b.autoBindable = autoBindable
            if (uniquenessMargin != null) b.uniquenessMargin = uniquenessMargin
            return b.build()
        }
    }
}
