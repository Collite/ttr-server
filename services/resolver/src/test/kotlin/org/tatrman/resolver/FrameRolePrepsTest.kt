// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.tatrman.resolver.pipeline.FrameRolePreps
import java.io.File
import java.nio.file.Files

/**
 * RV-P2.1.T5 (p2-1 review) — the estate override, end to end from the config key an operator
 * actually sets.
 *
 * The review found the override unreachable: `load` read `resolver.frame-roles.config-path`,
 * the KDoc and `frame-roles.conf` both documented `RESOLVER_FRAME_ROLES_PATH`, and nothing in
 * `application.conf` declared either — so a chart that set the env var silently got the shipped
 * Czech/English tables. It went unnoticed because every test called [FrameRolePreps.shipped]
 * and none called [FrameRolePreps.load]. This spec starts from the packaged `application.conf`
 * for exactly that reason: an override nobody can reach is not an override, and asserting on a
 * hand-built `Config` would have passed the whole time.
 */
class FrameRolePrepsTest :
    StringSpec({

        "the packaged application.conf declares the key `load` reads — the override is reachable" {
            val config = ConfigFactory.parseResources("application.conf").resolve()
            config.hasPath("resolver.frame-roles.config-path") shouldBe true
            // blank by default: unset ⇒ the shipped tables, the pre-RV reading
            config.getString("resolver.frame-roles.config-path") shouldBe ""
            FrameRolePreps.load(config).grouping("cs") shouldContain "podle"
        }

        "MH — the shipped count-head tables, and a config that predates them" {
            FrameRolePreps.shipped().countHeads("cs") shouldContain "kolik"
            FrameRolePreps.shipped().countHeads("cs") shouldContain "počet"
            FrameRolePreps.shipped().countHeads("en") shouldContain "many"
            FrameRolePreps.shipped().countHeads("en") shouldContain "number"
            FrameRolePreps.shipped().countHeads("en") shouldContain "count"
            // A region tag reads its language's table, like every other table here.
            FrameRolePreps.shipped().countHeads("cs-CZ") shouldBe FrameRolePreps.shipped().countHeads("cs")

            // An estate override written before MH has no `count-heads` key. It must keep
            // loading — and yield NOTHING rather than inheriting the shipped Czech list, which
            // would make a wholesale override partly not wholesale.
            val preMh =
                write(
                    """
                    frame-roles {
                        grouping-preps { cs = ["podle"] }
                        filter-preps   { cs = ["v"] }
                        default-lang = "cs"
                    }
                    """.trimIndent(),
                )
            val preps = FrameRolePreps.load(configWith(preMh))
            preps.grouping("cs") shouldContain "podle"
            preps.countHeads("cs") shouldBe emptySet()
            preps.countHeads("en") shouldBe emptySet()
        }

        "#206 — the shipped temporal prepositions: a period, never a range; absent from an older config" {
            val shipped = FrameRolePreps.shipped()
            shipped.temporal("cs") shouldBe setOf("za", "v", "ve", "během")
            shipped.temporal("en") shouldBe setOf("in", "for", "during")
            shipped.temporal("cs-CZ") shouldBe shipped.temporal("cs")
            // The kernel reads the year alone: a range preposition would turn "until 2025" into 2025.
            listOf("od", "do", "since", "until", "through").forEach { range ->
                (shipped.temporal("cs") + shipped.temporal("en")) shouldNotContain range
            }

            val preTemporal =
                write(
                    """
                    frame-roles {
                        grouping-preps { cs = ["podle"] }
                        filter-preps   { cs = ["za"] }
                        default-lang = "cs"
                    }
                    """.trimIndent(),
                )
            FrameRolePreps.load(configWith(preTemporal)).temporal("cs") shouldBe emptySet()
        }

        "an estate override replaces the shipped tables wholesale" {
            val file =
                write(
                    """
                    frame-roles {
                        grouping-preps { pl = ["wedlug"] }
                        filter-preps   { pl = ["w"] }
                        default-lang = "pl"
                    }
                    """.trimIndent(),
                )

            val preps = FrameRolePreps.load(configWith(file))

            preps.grouping("pl") shouldBe setOf("wedlug")
            preps.filter("pl-PL") shouldBe setOf("w")
            // wholesale, not a merge: the shipped Czech table is gone, which is what
            // "the next language is a data change" means for an estate that ships its own.
            preps.grouping("cs") shouldNotContain "podle"
        }

        "an uppercase default-lang still resolves — table keys are folded, so this one must be too" {
            val file =
                write(
                    """
                    frame-roles {
                        grouping-preps { en = ["by"] }
                        filter-preps   { en = ["in"] }
                        default-lang = "EN"
                    }
                    """.trimIndent(),
                )

            // `sk` has no table of its own and borrows the default one.
            FrameRolePreps.load(configWith(file)).grouping("sk") shouldBe setOf("by")
        }

        "a missing file falls back to the shipped tables rather than failing the boot" {
            val preps = FrameRolePreps.load(configWith(File("/nonexistent/frame-roles.conf")))
            preps.grouping("cs") shouldContain "podle"
        }

        "a malformed override falls back too — a broken estate file must not take the core down" {
            val preps = FrameRolePreps.load(configWith(write("frame-roles { grouping-preps { cs = [ ")))
            preps.grouping("cs") shouldContain "podle"
        }
    }) {
    companion object {
        private fun write(content: String): File =
            Files
                .createTempFile("frame-roles", ".conf")
                .toFile()
                .apply {
                    writeText(content)
                    deleteOnExit()
                }

        private fun configWith(file: File) =
            ConfigFactory.parseString("resolver.frame-roles.config-path = \"${file.path}\"")
    }
}
