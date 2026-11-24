// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.bundle.*
import org.tatrman.automation.identity.AuthorMap
import org.tatrman.automation.identity.ServiceTokens
import org.tatrman.ttr.metadata.model.ErSchema
import java.nio.file.Path

/** Autocommit SQL on the component database (the wet specs' ERP and `automation_db` schemas). */
internal fun wetSql(text: String) =
    LedgerTestDb.db.getDataSource().connection.use { c ->
        c.autoCommit = true
        c.createStatement().use { it.execute(text) }
    }

/** Every row of [text], as strings. */
internal fun wetQuery(text: String): List<List<String?>> =
    LedgerTestDb.db.getDataSource().connection.use { c ->
        c.createStatement().use { s ->
            s.executeQuery(text).use { rs ->
                buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) }) }
            }
        }
    }

internal fun dropWetRuntime() =
    wetSql("DROP SCHEMA IF EXISTS wet_runtime CASCADE; DROP TABLE IF EXISTS public.sales_order")

/**
 * The default H-1 fixture (route `release` → `release`, a transition placed/held → released, with history) plus
 * the generated verdict catalog entities of the approval fixture's `release_order`, which the writer lowers
 * against. ERP = public (sales_order), automation_pg = wet_runtime.
 */
internal fun wetRuntimeFixture(
    temp: Path,
    protocol: String? = null,
    routed: Boolean = true,
    initial: String = "held",
    keys: List<String> = listOf("4711"),
    wire: String = "release",
    engineHistory: org.tatrman.automation.engine.EngineHistory = org.tatrman.automation.engine.NoEngine,
    tokens: ServiceTokens = ServiceTokens(emptyMap(), { error("unused") }),
    runAsCaller: Boolean = true,
    /** S3.5 review-354 (o): the candidates each evaluation reads (the fake Query ignores the port); default [keys]. */
    candidates: () -> List<String> = { keys },
    ledgerFor: (org.tatrman.automation.ledger.Ledger) -> org.tatrman.automation.ledger.Ledger = { it },
    afterPrepare: (Bundle) -> Bundle = { it },
): Triple<DurableRuntimeFixture, org.tatrman.automation.writer.RowWritesQueryFake, Unit> {
    val root = ApprovalFixture.copyTo(temp.resolve("branch"))
    val catalog =
        BundleLoader(AuthorMap(emptyMap())).load(
            Checkout(root, "master", "0123456789abcdef", "po", "po@example.org"),
            BundleKind.SERVED,
        )
    org.tatrman.automation.writer
        .Ddl(LedgerTestDb.db.getDataSource(), { listOf("wet_runtime") })
        .refresh(catalog.copy(recipes = catalog.recipes.filterKeys { it == "release_order" }))
    val verdicts =
        (catalog.model.schemas.getValue("er") as ErSchema).entities.filterValues {
            it.qname.name.startsWith("release_order_verdict")
        }
    wetSql(
        "CREATE SCHEMA IF NOT EXISTS public; DROP TABLE IF EXISTS public.sales_order; " +
            "CREATE TABLE public.sales_order " +
            "(order_number text PRIMARY KEY, state text NOT NULL, held_for text); " +
            "INSERT INTO public.sales_order VALUES ('4711', '$initial', 'AR')",
    )
    val fake =
        org.tatrman.automation.writer.RowWritesQueryFake(
            LedgerTestDb.db.getDataSource(),
            mapOf("w-erp" to "public", "w-auto" to "wet_runtime"),
            bundle = { catalog },
        )
    val program = "release_on_placement"
    val f =
        DurableRuntimeFixture(
            transformBundle = { b ->
                val er = b.model.schemas.getValue("er") as ErSchema
                val r = b.recipes.getValue("release_order")
                val wired =
                    r.recipe.copy(
                        wiring =
                            r.recipe.wiring.map {
                                it.copy(
                                    action = wire,
                                    columns =
                                        mapOf("order" to "order_number") +
                                            // release_confirmed also takes the order's value (a literal here).
                                            if (wire ==
                                                "release_confirmed"
                                            ) {
                                                mapOf("value" to "100000")
                                            } else {
                                                emptyMap()
                                            },
                                )
                            },
                    )
                // The Core 0.22.3 fixture's release_confirmed `dry_run` names `{by}`, not a column, which the
                // preview refuses (fixed in hartland content by ttr-demo#171); the spec uses a column-only text.
                val actions =
                    b.actions.mapValues { (name, a) ->
                        if (name == "release_confirmed") {
                            a.copy(
                                dryRun =
                                    org.tatrman.ttr.metadata.model.LocalizedText(
                                        mapOf("en" to "would confirm the release of {order} ({value})"),
                                    ),
                            )
                        } else {
                            a
                        }
                    }
                val base = b.withActions(actions)
                base.copy(
                    recipes =
                        b.recipes +
                            (
                                "release_order" to
                                    r.copy(recipe = wired, manifest = r.manifest.copy(recipe = wired))
                            ),
                    model =
                        base.model.copy(
                            schemas =
                                base.model.schemas + ("er" to er.copy(entities = er.entities + verdicts)),
                        ),
                    programs =
                        b.programs + (
                            program to
                                b.programs
                                    .getValue(
                                        program,
                                    ).let { p ->
                                        p.copy(
                                            protocol = protocol,
                                            port =
                                                if (keys.size >
                                                    1
                                                ) {
                                                    "keys"
                                                } else {
                                                    p.port
                                                },
                                        )
                                    }
                        ),
                )
            },
            afterPrepare = afterPrepare,
            queryTable = { request ->
                Table(
                    listOf(ManifestColumn("order_number", "text"), ManifestColumn("verdict_reason", "text")),
                    if (routed ||
                        "candidates" in request.source
                    ) {
                        candidates().map { listOf(it, "SQL reason") }
                    } else {
                        emptyList()
                    },
                )
            },
            writeRoutes = "demo/erp_db=w-erp,demo/automation_db=w-auto",
            writes = fake,
            engineHistory = engineHistory,
            tokens = tokens,
            runAsCaller = runAsCaller,
            ledgerFor = ledgerFor,
        )
    return Triple(f, fake, Unit)
}
