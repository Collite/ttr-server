// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.metadata.parse.queryPathHandle
import org.tatrman.translate.v1.Language as TranslatorLanguage
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Path

/**
 * WS-T2 T3 — the four TPC-DS curated queries parse to a RelNode plan against the
 * `tpcds` model. Reconciles the `model-tpcds` fixture tree, applies the `{name}` → `?`
 * parameter bridge (the rewrite Translate's orchestrator performs before parse —
 * `TranslatorServiceImpl` §432; the raw `QueryParseWorker` leaves parameterised
 * queries FAILED because Calcite can't parse `{year}`), then parses each via the
 * same [Translator] the service uses.
 *
 * All four shapes parse — the join+group-by, the join+agg+ORDER/LIMIT, the
 * **window function** (`customer_running_total`), and the **CTE + UNION ALL**
 * (`channel_revenue_cte`). The last one relies on the `plan.v1` `Union` op added
 * in tatrman `ttr-plan-proto`/`ttr-translator` 0.8.1 and consumed via the
 * ttr-translator Phase B swap (docs/architecture/fork/ttr-translator-extraction.md).
 */
class TpcdsQueryParseSpec :
    StringSpec({

        val curated =
            setOf(
                "store_sales_by_month",
                "top_items_by_revenue",
                "customer_running_total",
                "channel_revenue_cte",
            )

        // The Translate parameter bridge: `{name}` → positional `?`.
        val paramPlaceholder = Regex("""\{[A-Za-z_][A-Za-z0-9_]*}""")

        fun reconciledModel() =
            run {
                val root: Path =
                    Path
                        .of(checkNotNull(this::class.java.classLoader.getResource("model-tpcds/tpcds")).toURI())
                        .parent
                val source =
                    FileBasedSource(
                        sourceId = "model-tpcds",
                        priority = 100,
                        storage = LocalFsStorage(id = "model-tpcds", rootPath = root),
                    )
                ModelReconciler(ModelDescriptor(id = "test", name = "test", description = "tpcds parse fixture"))
                    .reconcile(listOf(source.load()))
                    .model
            }

        "all four TPC-DS curated queries parse to RelNode (incl. window + CTE/UNION)" {
            val model = reconciledModel()
            val translator = Translator(queryPathHandle(model))

            val results =
                model.queries.values
                    .filter { it.qname.name in curated }
                    .associate { query ->
                        val bridged = query.sourceText.replace(paramPlaceholder, "?")
                        query.qname.name to translator.parseToRelNode(bridged, TranslatorLanguage.SQL)
                    }

            results.keys shouldBe curated
            curated.forEach { name ->
                withClue("query $name should parse; got: ${results[name]}") {
                    results.getValue(name).shouldBeInstanceOf<ParseResult.Success>()
                }
            }
        }
    })
