// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.core.AlgorithmType
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.SourceTag
import org.tatrman.fuzzy.core.StringRepository

/**
 * The static catalog loader (`fuzzy.loader.source = static`) reads the in-repo
 * `fuzzy-catalog.json`. This locks two things: (1) the shipped seed loads and is
 * non-empty (so `/ready` flips true instead of the 0-category empty boot), and
 * (2) an entry with `targetRef` becomes a VOCABULARY candidate while a plain
 * `{id,value}` stays a MEMBER — the distinction the resolver's provenance relies on.
 */
class FuzzyCatalogTest :
    StringSpec({

        "the shipped fuzzy-catalog.json loads with MEMBER products and a VOCABULARY branch term" {
            val catalog = FuzzyCatalog.fromResource("/fuzzy-catalog.json")

            catalog.keys shouldContainAll setOf("er.product", "er.branch")

            val octavia = catalog.getValue("er.product").single { it.id == "p-octavia" }
            octavia.source shouldBe SourceTag.MEMBER
            octavia.targetRef.shouldBeNull()

            val pobocka = catalog.getValue("er.branch").single()
            pobocka.id shouldBe "term-pobocka"
            pobocka.source shouldBe SourceTag.VOCABULARY
            pobocka.targetRef shouldBe "er.branch#term-pobocka"
            // MV-T2 — the shipped seed names its product method, so it matches as it always did.
            octavia.matchMethod shouldBe "TOKENS"
            pobocka.matchMethod.shouldBeNull()
        }

        "MV-T2 — `methods` stamps a category's member rows; a category it does not name is EXACT" {
            val catalog = FuzzyCatalog.fromResource("/mv/methods-catalog.json")
            catalog.getValue("er.entity.store.state").map { it.matchMethod }.toSet() shouldBe setOf("EXACT")
            catalog.getValue("er.entity.store.state_typos").single().matchMethod shouldBe "TYPOS(1)"
            catalog.getValue("er.entity.store.state_unnamed").single().matchMethod shouldBe "EXACT"
        }

        "MV-T2 T4 (§9.4 exact-code) — the method a member category carries is what the matcher honours" {
            val repo =
                StringRepository(
                    AppConfig(
                        serverPort = 7131,
                        grpcPort = 7231,
                        grpcReflectionEnabled = false,
                        refreshIntervalSeconds = 0,
                        tokenBasedConfig = TokenBasedConfig(),
                        nlp = NlpConfig(),
                        loaderSource = LoaderSourceConfig(source = "static"),
                        metadata = MetadataConfig(),
                    ),
                    StaticLoaderSource(FuzzyCatalog.fromResource("/mv/methods-catalog.json")),
                )
            try {
                runBlocking { repo.forceRefresh() }
                val matcher = FuzzyMatcher(repo)

                fun ids(
                    query: String,
                    category: String,
                ) = runBlocking { matcher.match(query, category, AlgorithmType.TATRMAN, 10).map { it.candidateId } }

                // EXACT: the code as written, case aside — and no slack at all.
                ids("tn", "er.entity.store.state") shouldBe listOf("47")
                ids("tx", "er.entity.store.state").shouldBeEmpty()
                ids("Taxas", "er.entity.store.state").shouldBeEmpty()
                // One edit is inside TYPOS(1), and anything goes under TOKENS…
                ids("Taxas", "er.entity.store.state_typos") shouldBe listOf("48")
                ids("Taxas", "er.entity.store.state_tokens") shouldBe listOf("48")
                // …while a member category nobody gave a method is EXACT, not unrestricted.
                ids("Taxas", "er.entity.store.state_unnamed").shouldBeEmpty()
                ids("texas", "er.entity.store.state_unnamed") shouldBe listOf("48")
                // (§9.4's legacy `TYPOS(1)`: `TM` → `TN` does not carry over: a TYPOS row of three
                // characters or fewer admits only itself here — RV-44's short-term guard.)
            } finally {
                repo.close()
            }
        }
    })
