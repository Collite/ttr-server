// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.config

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.fuzzy.core.MatchVersion
import org.tatrman.fuzzy.core.RetrievalMode

/**
 * review-103 L1 · L2 · L3 — the service's config reader: blank is unset and takes the SHIPPED value,
 * a malformed key stops startup naming the key, and settings that parse but do nothing say so.
 */
class ConfigLoaderSpec :
    StringSpec({
        fun fuzzy(hocon: String) = ConfigFactory.parseString(hocon)

        /** The token-based block + match version, exactly as `ConfigLoader.load` composes them. */
        fun engine(hocon: String) =
            ConfigLoader.withMatchVersion(ConfigLoader.loadTokenBasedConfig(fuzzy(hocon)), fuzzy(hocon))

        fun app(tokenBased: TokenBasedConfig) =
            AppConfig(
                serverPort = 0,
                grpcPort = 0,
                grpcReflectionEnabled = false,
                refreshIntervalSeconds = 0,
                tokenBasedConfig = tokenBased,
            )

        // ---- L1 — blank / absent is the shipped engine, never the library's V1 -------------------

        "L1 — a config with no fuzzy.match and no token-based block runs the shipped v2 + index-first" {
            // An operator's own -Dconfig.file REPLACES application.conf; before the fix this ran v1
            // + legacy — silently the engine the service stopped shipping.
            val tokenBased = engine("")
            tokenBased.matchVersion shouldBe MatchVersion.V2
            tokenBased.retrieval shouldBe RetrievalMode.INDEX_FIRST
        }

        "L1 — FUZZY_MATCH_VERSION exported empty is unset, not v1" {
            engine("match.version = \"\"").matchVersion shouldBe MatchVersion.V2
            engine("match.version = \"  \"").matchVersion shouldBe MatchVersion.V2
            engine("match.version = v1").matchVersion shouldBe MatchVersion.V1
        }

        "L1 — the shipped application.conf still resolves to v2 + index-first" {
            val shipped = ConfigFactory.parseResources("application.conf").resolve().getConfig("fuzzy")
            val tokenBased = ConfigLoader.withMatchVersion(ConfigLoader.loadTokenBasedConfig(shipped), shipped)
            tokenBased.matchVersion shouldBe MatchVersion.V2
            tokenBased.retrieval shouldBe RetrievalMode.INDEX_FIRST
            tokenBased.idfEnabled shouldBe true
        }

        // ---- L2 — a malformed token-based key fails naming the key ----------------------------------

        "L2 — a malformed token-based key stops startup naming it (it used to collapse to LEGACY)" {
            val cases =
                mapOf(
                    "token-based { distance-threshold = abc }" to "fuzzy.token-based.distance-threshold",
                    "token-based { order-bonus-multiplier = fast }" to "fuzzy.token-based.order-bonus-multiplier",
                    "token-based { max-order-bonus = [1] }" to "max-order-bonus",
                    "token-based { idf-enabled = maybe }" to "fuzzy.token-based.idf-enabled",
                    "token-based { retrieval = sideways }" to "fuzzy.token-based.retrieval",
                )
            for ((hocon, key) in cases) {
                val refused = shouldThrow<Exception> { engine(hocon) }
                (refused is IllegalArgumentException || refused is ConfigException) shouldBe true
                refused.message!! shouldContain key
                // The pre-L2 symptom: the typo surfaced as the §4.1 pair refusal instead of naming itself.
                refused.message!!.contains("v2 requires index-first") shouldBe false
            }
        }

        "L2 — token-based that is not a block stops startup" {
            shouldThrow<ConfigException> { engine("token-based = 5") }.message!! shouldContain "token-based"
        }

        "L2 — a PARTIAL block keeps its other defaults; blank values are unset" {
            val partial = ConfigLoader.loadTokenBasedConfig(fuzzy("token-based { idf-enabled = false }"))
            partial.idfEnabled shouldBe false
            partial.retrieval shouldBe RetrievalMode.INDEX_FIRST
            partial.distanceThreshold shouldBe TokenBasedConfig().distanceThreshold

            val blanks =
                ConfigLoader.loadTokenBasedConfig(fuzzy("token-based { retrieval = \"\", idf-enabled = \"\" }"))
            blanks.retrieval shouldBe RetrievalMode.INDEX_FIRST
            blanks.idfEnabled shouldBe true

            ConfigLoader.loadTokenBasedConfig(fuzzy("token-based { retrieval = legacy }")).retrieval shouldBe
                RetrievalMode.LEGACY
            ConfigLoader.loadTokenBasedConfig(fuzzy("token-based { retrieval = INDEX_FIRST }")).retrieval shouldBe
                RetrievalMode.INDEX_FIRST
        }

        "L2 — an explicit legacy retrieval under the shipped v2 is still the §4.1 refusal, with its own message" {
            shouldThrow<IllegalArgumentException> {
                engine(
                    "token-based { retrieval = legacy }",
                )
            }.message!! shouldContain
                "v2 requires index-first"
        }

        "L2 — idf-enabled=false under v2 is WARNED, not silently ignored; under v1 it is a real switch" {
            val v2NoIdf =
                TokenBasedConfig(
                    idfEnabled = false,
                    retrieval = RetrievalMode.INDEX_FIRST,
                    matchVersion = MatchVersion.V2,
                )
            val warnings = ConfigLoader.startupWarnings(app(v2NoIdf))
            warnings shouldHaveSize 1
            warnings.single() shouldContain "has NO effect under fuzzy.match.version=v2"
            warnings.single() shouldContain "FUZZY_TOKEN_BASED_IDF_ENABLED"

            ConfigLoader.startupWarnings(app(v2NoIdf.copy(matchVersion = MatchVersion.V1))).shouldBeEmpty()
            ConfigLoader.startupWarnings(app(v2NoIdf.copy(idfEnabled = true))).shouldBeEmpty()
        }

        // ---- L3 — the uniqueness floor must sit above v2's ε ------------------------------------------

        "L3 — uniqueness-margin-floor at or under ε (0.01) refuses to start" {
            for (floor in listOf("0.01", "0.005", "0", "-0.1")) {
                shouldThrow<IllegalArgumentException> {
                    ConfigLoader.loadLexiconConfig(fuzzy("lexicon.uniqueness-margin-floor = $floor"))
                }.message!! shouldContain "fuzzy.lexicon.uniqueness-margin-floor must be > 0.01"
            }
            ConfigLoader
                .loadLexiconConfig(
                    fuzzy("lexicon.uniqueness-margin-floor = 0.011"),
                ).uniquenessMarginFloor shouldBe
                0.011
            ConfigLoader.loadLexiconConfig(fuzzy("")).uniquenessMarginFloor shouldBe 0.05
            val shipped = ConfigFactory.parseResources("application.conf").resolve().getConfig("fuzzy")
            ConfigLoader.loadLexiconConfig(shipped).uniquenessMarginFloor shouldBe 0.05
        }
    })
