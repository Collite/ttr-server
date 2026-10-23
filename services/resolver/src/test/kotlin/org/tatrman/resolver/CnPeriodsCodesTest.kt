// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.resolver.MhMembers.tok
import org.tatrman.resolver.v1.UniversalEntityType
import java.nio.file.Path

/**
 * CN-P4 S4.4 T6 — rulebook R-cn-6 (cn contracts §5, the UD seam): **periods come from the universal layer, codes from
 * the governed one.** A code under a vocabulary-bound kind's concept resolves as a governed member of the code entity
 * the concept lands on — through A-CN-6's normalization, before any other reading — and a date beside it stays the
 * universal layer's, for chrono to ground.
 *
 * Over `cn-clinic` (invented codes): `I21` is a member of BOTH code vocabularies, so only the concept's governance
 * can say which one "diagnóza I21" means.
 */
class CnPeriodsCodesTest :
    StringSpec({
        val clinic = Path.of(CnPeriodsCodesTest::class.java.getResource("/cn-clinic")!!.toURI())
        val archive = CnEstate.pack(CnEstate.build(clinic))
        val members =
            mapOf(
                "i21" to
                    listOf(
                        CnEstate.Member("I21", "I21", "er.entity.diagnosis.code"),
                        CnEstate.Member("I21", "I21", "er.entity.procedure.code"),
                    ),
            )

        "a code under the concept is a governed member of the code entity it lands on; MISC pre-empts nothing" {
            val (r, matcher) =
                CnEstate.resolve(
                    "diagnóza I21",
                    arrayOf(
                        tok("diagnóza", 0, 8, "diagnóza", "NOUN", 0, "root"),
                        tok("I21", 9, 12, "I21", "PROPN", 1, "appos"),
                    ),
                    archive = archive,
                    members = members,
                    entities = listOf(MhMembers.ner("I21", 9, 12, "MISC")),
                )
            // The governed lookup asks the concept's own term, then the diagnosis's vocabularies — the concept's landing
            // (A-CN-6, ⚑R130-c) — and binds there.
            matcher.log
                .filter {
                    it.query == "I21" &&
                        it.categories.size in 1..3 &&
                        it.categories.none { c -> c.startsWith("ground:") }
                }.map { it.categories } shouldContainExactly
                listOf(listOf("cn.Diagnosis", "er.entity.diagnosis", "er.entity.diagnosis.code"))
            r.hasAwaiting() shouldBe false
            val code = r.resolution.bindingsList.single { it.span.text == "I21" }
            code.domain.memberOf shouldBe "er.entity.diagnosis.code"
            code.domain.resolvedId shouldBe "I21"
            r.resolution.bindingsList.none { it.hasUniversal() } shouldBe true
            // The concept term binds the concept, and the door names the entity it lands on (A-CN-70).
            val concept = r.resolution.bindingsList.single { it.span.text == "diagnóza" }
            concept.conceptsList.map { it.conceptQname } shouldContainExactly listOf("cn.Diagnosis")
            concept.domain.entityTypeRef shouldBe "er.entity.diagnosis"
        }

        "a period beside the concept stays the universal layer's; the code beside it is still the governed member" {
            val (r, _) =
                CnEstate.resolve(
                    "diagnózy I21 v lednu 2025",
                    arrayOf(
                        tok("diagnózy", 0, 8, "diagnóza", "NOUN", 0, "root"),
                        tok("I21", 9, 12, "I21", "PROPN", 1, "appos"),
                        tok("v", 13, 14, "v", "ADP", 4, "case"),
                        tok("lednu", 15, 20, "leden", "NOUN", 1, "obl"),
                        tok("2025", 21, 25, "2025", "NUM", 4, "nummod"),
                    ),
                    archive = archive,
                    members = members,
                    entities = listOf(MhMembers.ner("lednu 2025", 15, 25, "DATE", "cnec:tm")),
                )
            r.hasAwaiting() shouldBe false
            r.resolution.bindingsList
                .filter { it.hasUniversal() }
                .map { it.universal.entityType to it.universal.rawText } shouldContainExactly
                listOf(UniversalEntityType.DATE to "lednu 2025")
            r.resolution.bindingsList
                .single { it.span.text == "I21" }
                .domain.memberOf shouldBe "er.entity.diagnosis.code"
        }
    })
