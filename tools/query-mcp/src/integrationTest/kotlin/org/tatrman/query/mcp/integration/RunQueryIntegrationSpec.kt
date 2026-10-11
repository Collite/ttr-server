// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp.integration

import io.kotest.core.annotation.Tags
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.tatrman.testkit.integration.RequiresContext
import org.tatrman.testkit.integration.RequiresContextExtension
import org.tatrman.testkit.integration.contextHandle

/**
 * Stage 2.2 — end-to-end `run_query` (MCP tool `query`) through the **real**
 * forked chain: query-mcp → Query → Translate → Validate → Dispatch → Mssql →
 * MSSQL. Gated by `@RequiresContext("query-runquery")` — compiles + skips until
 * olymp stands the context up (Stage 2.3), then runs green.
 *
 * **No WireMock stubs are needed for this path** (corrected from the original
 * plan): the `query` tool never enables Validate's LLM-guard, and modeler/TTR is not
 * a runtime call — so there is no external HTTP dependency to stub. The only true
 * external is MSSQL, which is the context's `mssql` platform member + seed. The
 * Stage 2.1 in-cluster WireMock loader stays available for any future scenario
 * that turns LLM-guard on.
 *
 * ## Context requirements (T6 — reconciled with olymp `test-contexts/query-runquery/` as wired in Stage 2.3)
 *  - Services (real): query-mcp, query, translate, validate, dispatch, mssql. **Veles is NOT
 *    deployed** — Validate runs its fixture model (`VALIDATE_USE_FIXTURE_MODEL=true`); the raw-SQL
 *    `query` path resolves no metadata through Veles.
 *  - Platform: `mssql` (the base `mssql-init` Job seeds the sample dataset) + `wiremock` (empty;
 *    unused on this path). The suite expects `dbo.sample_orders` with 4 rows incl. `tenant_id`
 *    `t-alpha` — **coordinate the olymp mssql seed with this shape (Stage 2.3 T5 live check).**
 *  - Identity: `query-mcp` runs with `requireIdentity=true` (the chart default) — this is what
 *    the one ACTIVE assertion (missing-bearer fail-closed) proves end-to-end.
 *  - `readiness`: the kantheon gate **derives** readiness from the namespace (every Deployment
 *    Available + the `mssql-init` Job Complete) — it reads no handshake annotation; the only
 *    cross-repo surface is the `olymp.collite/context`/`run` ns labels (contracts §6).
 *
 * **Scoped close (Stage 2.3).** The query *result* + RLS assertions are disabled behind
 * `modelAlignedContext` (see the body) until the context's Model matches its seed: Translate' fixture
 * model has no `dbo.sample_orders` (→ `detection_failed`), and the deployed Validate policy is
 * `tenant_isolation` (row-level), not the column-DENY the RLS case assumed. Re-enabling is the
 * Phase 3 follow-up (plan.md): an Veles model — or a fixture model — aligned with the seed.
 */
@RequiresContext("query-runquery")
@ApplyExtension(RequiresContextExtension::class)
@Tags("integration")
class RunQueryIntegrationSpec :
    StringSpec({

        // ── Two scoped gates (WS-C2 T6, Bora 2026-07-08) ─────────────────────────────────────
        // resultAlignedContext (ON) — the query *result* path, now wired to REAL MSSQL end-to-end
        // (query→translate→validate→dispatch→Mssql→mssql). Reaching green took four live runs:
        //   1. `detection_failed` → added dbo.sample_orders to Translate BootFixtureModel (aligned with
        //      the mssql-init seed: id/tenant_id/region/amount, 4 rows incl 't-alpha').
        //   2. Calcite "Object 'dbo' not found" → reference the table UNQUALIFIED (a namespace is not
        //      a Calcite schema; Translate re-qualifies to dbo.sample_orders on MSSQL unparse).
        //   3. `no_worker_for_connection: mssql-default` → Mssql ran fixture mode + advertised no
        //      connection. Now the real-MSSQL variant is wired: the shipped `mssql-default` connection
        //      template in mssql application.conf activates from MSSQL_DB_* (host=mssql,
        //      db=kantheon_local, password from the per-run mssql-sa-secret), `MSSQL_USE_FIXTURE
        //      =false`, and `DISPATCH_USE_FIXTURE=false` so Dispatch's poller learns mssql-default from
        //      Mssql' GetCapabilities. (Needs mssql `:testing` republished from the new fixture.)
        //
        // rlsPolicyContext (OFF) — the RLS path. Still NOT testable here: query-runquery runs Validate
        // with `VALIDATE_USE_FIXTURE_MODEL=true`, whose fixture SecurityClient applies **no policies at
        // all** ("no row-level policies applied", Validate Application.kt) — so neither the column-DENY
        // the deny-case assumes NOR row-level tenant_isolation is enforced (the earlier "fixture
        // policy is tenant_isolation" note was wrong — that policy loads only in NON-fixture mode).
        // Real RLS needs Validate non-fixture + a metadata source (Veles) + loaded policies + role-
        // based column rules (the engine's column rules are unconditional today) — a richer,
        // Veles-backed context. Deferred beyond C2 (tracking: testing plan.md Phase 3).
        val resultAlignedContext = true
        val rlsPolicyContext = false

        // T1 — happy path: real rows from real MSSQL, real columns from Translate translation.
        // NOTE: the table is referenced UNQUALIFIED (`sample_orders`, not `dbo.sample_orders`) — the
        // proven form (Translate WARMUP_SQL `FROM customer`, tpcds-query `FROM store_sales`). Calcite
        // resolves it against the model's default DB namespace (dbo); a `dbo.` prefix fails with
        // "Object 'dbo' not found" because the namespace isn't a literal Calcite schema. Translate
        // re-qualifies to `dbo.sample_orders` on unparse to the MSSQL dialect, so Mssql hits the
        // right table. (First live run 2026-07-08 failed on the qualified form.)
        "query returns the seeded rows from real MSSQL with the expected columns"
            .config(enabled = resultAlignedContext) {
                val handle = contextHandle()
                val bearer = unsignedJwt("alice", roles = listOf("analyst"))

                val res =
                    runBlocking {
                        handle.callQuery(
                            sqlQueryArgs("SELECT id, tenant_id, region, amount FROM sample_orders ORDER BY id"),
                            bearer,
                        )
                    }

                res.isError shouldBe false
                res.ok() shouldBe true
                res.rowCount() shouldBe 4
                res.columnNames() shouldContainAll listOf("id", "tenant_id", "region", "amount")
                // A real seeded value proves the chain reached MSSQL and didn't short-circuit
                // (the substitute for a WireMock request-journal check, which is N/A here — T5).
                res.bodyText() shouldContain "t-alpha"
            }

        // T3 — OBO/bearer discipline: missing identity fails closed at the query-mcp edge.
        // (Token `exp` is enforced at ingress, not in-service in v1 — see kantheon-security.md.)
        "a missing OBO bearer fails closed with missing_user_identity" {
            val handle = contextHandle()

            val res = runBlocking { handle.callQuery(sqlQueryArgs("SELECT id FROM sample_orders"), bearer = null) }

            res.isError shouldBe true
            res.ok() shouldBe false
            res.firstMessageCode() shouldBe "missing_user_identity"
        }

        // T4 — RLS negative path. Assumes a column-DENY Validate policy. GATED (rlsPolicyContext):
        // fixture-mode Validate applies no policies, so this needs an Veles-backed non-fixture context.
        "a role denied a column gets a column_denied error envelope (no leaked data)"
            .config(enabled = rlsPolicyContext) {
                val handle = contextHandle()
                val restricted = unsignedJwt("bob", roles = listOf("restricted"))

                val res =
                    runBlocking {
                        handle.callQuery(
                            sqlQueryArgs("SELECT id, amount FROM sample_orders"),
                            restricted,
                        )
                    }

                res.isError shouldBe true
                res.firstMessageCode() shouldBe "column_denied"
            }

        // T4 — the permitted-role variant of the same query returns rows. GATED with the deny case
        // (rlsPolicyContext): only a meaningful contrast to the deny when real policies are loaded —
        // in fixture mode every role gets rows, which T1 already proves.
        "a permitted role gets rows for the same column query"
            .config(enabled = rlsPolicyContext) {
                val handle = contextHandle()
                val analyst = unsignedJwt("alice", roles = listOf("analyst"))

                val res =
                    runBlocking { handle.callQuery(sqlQueryArgs("SELECT id, amount FROM sample_orders"), analyst) }

                res.isError shouldBe false
                (res.rowCount() ?: 0) shouldBeGreaterThan 0
            }
    })
