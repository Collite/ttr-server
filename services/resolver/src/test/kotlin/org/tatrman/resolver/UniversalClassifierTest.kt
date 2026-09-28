// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.resolver.pipeline.UniversalClassifier
import org.tatrman.resolver.v1.UniversalEntityType

/**
 * The CNEC-code precedence (SV-P3·F1). The nlp NameTag front collapses both
 * objects/other-proper names (`o*`, domain-eligible) and numbers (`n*`, universal MISC)
 * into the single coarse label "MISC", so the resolver must classify on the raw CNEC
 * container code (carried in `normalized_value` as `cnec:<code>`) to keep them apart —
 * otherwise the RG hero's `op`-tagged "Octavie" binds as a universal MISC instead of
 * reaching `er.product`.
 *
 * UD-P0 (ttr-server#118, option 3): without a code, a coarse `MISC` is domain-eligible —
 * the same call the code makes for `o*`. Only the number labels stay universal `MISC`.
 */
class UniversalClassifierTest :
    StringSpec({

        "cnec:op (object / other-proper name) is domain-eligible, NOT universal MISC — the hero fix" {
            UniversalClassifier.classify("MISC", "cnec:op") shouldBe null
            UniversalClassifier.isUniversal("MISC", "cnec:op") shouldBe false
        }

        "cnec:no (number) stays universal MISC — the q20 '12345' case is preserved" {
            UniversalClassifier.classify("MISC", "cnec:no") shouldBe UniversalEntityType.MISC
            UniversalClassifier.isUniversal("MISC", "cnec:no") shouldBe true
        }

        "the genuine universal CNEC containers still classify by their letter" {
            UniversalClassifier.classify("PERSON", "cnec:ps") shouldBe UniversalEntityType.PERSON
            UniversalClassifier.classify("LOCATION", "cnec:gu") shouldBe UniversalEntityType.LOCATION
            UniversalClassifier.classify("DATE", "cnec:tf") shouldBe UniversalEntityType.DATE
        }

        "cnec:if (institution) is domain-eligible (gated by fuzzy over declared vocabulary)" {
            UniversalClassifier.classify("ORGANIZATION", "cnec:if") shouldBe null
        }

        "a coarse MISC with no cnec code is domain-eligible; a code, when present, still decides (UD-P0, #118)" {
            // The coarse label alone no longer says universal: "MISC" is what every coarse-label
            // engine calls a name that is not a person, place or date — a store, a product, a
            // code. Only a CNEC code can say it is a number, and then the code wins.
            UniversalClassifier.classify("MISC", "") shouldBe null
            UniversalClassifier.classify("MISC", "cnec:op") shouldBe null
            UniversalClassifier.classify("MISC", "cnec:no") shouldBe UniversalEntityType.MISC
        }

        "a NameTag entity whose tag was lost (`cnec:` with an empty code) is domain-eligible (UD-P0)" {
            UniversalClassifier.classify("MISC", "cnec:") shouldBe null
            UniversalClassifier.isUniversal("MISC", "cnec:") shouldBe false
        }

        "the coarse number labels stay universal MISC — option 3 narrows MISC, not numbers" {
            UniversalClassifier.classify("CARDINAL") shouldBe UniversalEntityType.MISC
            UniversalClassifier.classify("NUMBER") shouldBe UniversalEntityType.MISC
            UniversalClassifier.classify("ORDINAL") shouldBe UniversalEntityType.MISC
            UniversalClassifier.classify("PERCENT") shouldBe UniversalEntityType.MISC
        }

        "coarse MISC is domain-eligible whatever its case, and isUniversal agrees" {
            UniversalClassifier.classify("misc") shouldBe null
            UniversalClassifier.classify(" MISC ") shouldBe null
            UniversalClassifier.isUniversal("MISC") shouldBe false
        }

        "dualReadingType: only a place or a person has a member reading to offer (⚑UD-5)" {
            UniversalClassifier.dualReadingType("GPE", "") shouldBe UniversalEntityType.LOCATION
            UniversalClassifier.dualReadingType("PERSON", "") shouldBe UniversalEntityType.PERSON
            UniversalClassifier.dualReadingType("LOCATION", "cnec:gu") shouldBe UniversalEntityType.LOCATION
            UniversalClassifier.dualReadingType("PERSON", "cnec:ps") shouldBe UniversalEntityType.PERSON
            // kernel-owned: a date is a range and an amount is an amount, never a member
            UniversalClassifier.dualReadingType("DATE", "") shouldBe null
            UniversalClassifier.dualReadingType("MONEY", "") shouldBe null
            // numbers ride the literal path, not the member path
            UniversalClassifier.dualReadingType("MISC", "cnec:no") shouldBe null
            UniversalClassifier.dualReadingType("CARDINAL", "") shouldBe null
            // not universal at all — nothing to read twice
            UniversalClassifier.dualReadingType("MISC", "") shouldBe null
            UniversalClassifier.dualReadingType("ORG", "") shouldBe null
            UniversalClassifier.DUAL_READING_TYPES shouldBe
                setOf(UniversalEntityType.LOCATION, UniversalEntityType.PERSON)
        }

        "entities without a cnec code fall back to the coarse label" {
            UniversalClassifier.classify("PERSON") shouldBe UniversalEntityType.PERSON
            UniversalClassifier.classify("DATE") shouldBe UniversalEntityType.DATE
            UniversalClassifier.classify("ORG") shouldBe null
            UniversalClassifier.classify("") shouldBe null
        }
    })
