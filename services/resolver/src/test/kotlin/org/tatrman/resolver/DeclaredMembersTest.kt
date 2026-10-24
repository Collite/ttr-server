// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.resolver.pipeline.DeclaredMembers

/** md/hop HP-H — the one rule between a value label's archive spelling and the lattice's member spelling. */
class DeclaredMembersTest :
    StringSpec({
        "an er value label splits into its attribute and its code, the code whole" {
            DeclaredMembers.split("er.entity.sales.channel.marketplace") shouldBe
                ("er.entity.sales.channel" to "marketplace")
            DeclaredMembers.split("er.entity.item.size.5.5") shouldBe ("er.entity.item.size" to "5.5")
            DeclaredMembers.split("er.entity.course.lang.C#") shouldBe ("er.entity.course.lang" to "C#")
        }

        "anything else is no value label" {
            for (ref in listOf(
                "er.entity.sales.channel",
                "er.entity.sales",
                "er.entity.sales.channel#marketplace",
                "er.entity.item.size#5.5",
                "er.entity.course.lang.",
                "md.dimension.DistributionCentre.dcCode.5",
                "cn.WebSale",
                "op:compare",
                "",
            )) {
                DeclaredMembers.split(ref).shouldBeNull()
                DeclaredMembers.spelled(ref) shouldBe ref
            }
        }

        "the two spellings round-trip" {
            DeclaredMembers.spelled("er.entity.sales.channel.web") shouldBe "er.entity.sales.channel#web"
            DeclaredMembers.archiveSpelling("er.entity.sales.channel#web") shouldBe "er.entity.sales.channel.web"
            DeclaredMembers.archiveSpelling("er.entity.item.size#5.5") shouldBe "er.entity.item.size.5.5"
            DeclaredMembers.archiveSpelling("er.entity.sales.channel").shouldBeNull()
            DeclaredMembers.archiveSpelling("er.entity.sales.channel#").shouldBeNull()
            DeclaredMembers.archiveSpelling("md.dimension.Account.code#501001").shouldBeNull()
            DeclaredMembers.archiveSpelling("er.branch#term-pobocka").shouldBeNull()
        }
    })
