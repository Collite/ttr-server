# automation

The automation service (AU): it runs recipes through the door, keeps the ledger and the protocol of every
run, and is the exchange — the run API in, the connector SPI out. The design, contracts, plan and status live
in the project folder:
[`project/kantheon/features/automation-agents/`](../../../project/kantheon/features/automation-agents/)
(the service: `implementation/ttr-server/` — `contracts.md` is the API, the ledger and the config).

> **Built so far:** P0 (ledger, bundle load/refresh, descriptor, identity, chart/image). P1 S1.1 now has the
> executor, Open policy, pinned run sessions and bounded part/island handlers: governed Query reads, shared
> storage parts, embedded DuckDB combines, typed Arrow staging and immutable `run_outputs` journalling.
> All five read/candidate guards and synchronous evaluation summaries are implemented. Evaluation shares the
> pinned runner, returns route counts/refs plus unmatched candidates and guard evidence, and records the decision
> atomically with EVALUATED status. Detail snapshots now apply pinned limits per route/context/key before staging,
> and authenticated route/detail readers page the verified Arrow. Production runtime/storage wiring, output
> downloads, async execution and the remaining protocol/lifecycle orchestration remain. Run handlers,
> including evaluate and route/detail readers, are not mounted in Application.

The executor requires an explicit `world/storage` → Query world alias map. Query resolves those aliases through
its existing `QUERY_WORLDS` table. Missing routes fail before a query is issued; there is no fallback to Query's
default connection. Recipe `:name` bindings are translated lexically to the door's `{name}` syntax. Named runs
use the IdP token's own roles, not the caller's. Open expands `port: keys` into scalar bindings; every keyed
output is filtered before staging.

Before each governed read, QueryDoor compiles the exact scoped/bound request and requires explicit validated
ER read evidence contained in the pinned manifest's `objectsRead`. Missing evidence (including an older Query
service), compile errors or undeclared reads prevent execution. The same allow-list goes on Run; Query checks
that call's validated ER tree before dispatch, including on cache hits. This covers expression subqueries,
expanded logical-query bodies and entity boundaries without mapping physical DB names back to ER. Policy
validation stays fresh per caller. Scope refusals latch on the run before staging, alongside restricted-view,
ceiling, empty scoped candidates and duplicate-key guards. Production wiring must deploy the matching Query
contract before mounting these handlers.

Evaluation reuses scheduled part/output promises, including across concurrent calls and overlapping part reads.
In manifest v3, a decision island emits a port for each route and additional `<route>.<context>` detail ports.
Only route ports contribute to counts. `none` counts candidate keys absent from the union of routes, so an
overlapping switch cannot double-subtract a key. Composite numeric keys compare by exact normalized decimal
value. Unknown/missing ports, duplicate/non-candidate route keys and overlapping exclusive routes fail before
a decision is recorded. SQL's `verdict_reason` stays in the staged output; the server does not render `because`.

Detail snapshots retain at most `detail_limit` data rows and one extra `truncated: true` marker per
(route, context, composite key). Normal rows carry `truncated: false`; markers retain the key and set other
source columns to null. Route/context stamps and non-null key columns are checked before bounding; evaluation
also refuses detail keys absent from their route. Sources and local combines must still pass the general read
ceiling before any intentional snapshot truncation, so the bound cannot conceal an incomplete context.
Open pins `budgets.detailLimit` (default 200) and effective route limits in its context/opening evidence. Runtime
must pass the configured `detailLimit` to Open. Authored action `detail_limit` is read from the model's preserved
properties; malformed, nonpositive or overflowing values refuse before claiming. A route shared by several
actions retains the largest required limit. P2's writer must separately apply each action's own smaller bound.

`GET /automation/runs/{id}/routes/{route}?offset=0&limit=200` requires a successful durable evaluation (starting
it if needed), then returns key columns, SQL's `verdict_reason`, and `detail: { <context>: ref }` for each row.
Summary `verdict.routes.<route>.detail` maps contexts to the whole bounded snapshot. Row refs resolve through
`GET …/routes/{route}/details/{context}?row=<route-row-offset>` and select only that immutable route row's composite
key. Omitting `row` reads the whole bounded context snapshot. Neither form reissues governed reads after the
shared evaluation. These P1 refs address staged run data; P2's model detail keys are a separate writer contract.

Both readers accept nonnegative integer `offset` and `limit` in `1..displayRows` (default `displayRows`). Invalid
names/parameters refuse before execution and do not fail the run. Offset beyond the result returns empty rows.
`rowCount` counts the whole selected snapshot, including markers; `truncated` is true when the page omits rows
or any selected detail key has a marker, even if that marker lies on another page. Detail rows never mix keys
when following a row ref. The initiating verified subject owns all reads; missing/released/terminal/other-owner
handles refuse. Readers decode published Arrow and verify its byte count, SHA-256 and row count, refusing
corruption instead of falling back to cached rows. Retention/restart recovery remain lifecycle work.

Runtime construction must pass `recordEvaluation = ledger::completeEvaluation` to PartRunner. Without the
journal callback, evaluate fails closed. The callback stores the typed evaluate result (including `mode` and
`guards`) in the protocol's INSERT-only `decision` section and sets EVALUATED in one locked transaction.
Identical durable retries are harmless; changed evidence, sealed protocols and terminal/waiting runs refuse.
This supplies the durable summary for later status/protocol readers without a second mutable summary column.
A journal failure is shared like other executor failures and the session fails the active run. This synchronous
handler still needs the async budget/poll wrapper before production mounting.

Local staging publishes immutable Arrow blobs before journalling their metadata. A journal failure leaves an
unreferenced blob for the S1.3 retention job; it never deletes content another claimant might reference. The
object-store adapter and cleanup/close policy remain separate pending work. The checked-in real bundle fixture
records its provenance under `src/test/resources/runner/hartland/README.md`.

## Operating it

**Configuration** is `automation.*` in `src/main/resources/application.conf`, every key overridable by an
`AUTOMATION_*` variable (AU contracts §8). Five keys have no default, and a pod missing one fails at start
naming all of them: `db.host`, `door.queryUrl`, `door.velesUrl`, `auth.jwksUrl`, `auth.issuer`. The chart
(`k8s/`, also the `automation` key of the umbrella `helm/tatrman-server`) renders them from values and fails
the render by key when one is missing.

**The database migrates itself.** At start the service runs Flyway against its own database (one
`automation` database, schema `automation`): `V1` the ledger, `V2` pgmq 1.13.0 installed **SQL-only** —
upstream's `pgmq.sql`, vendored unchanged and hash-checked, PostgreSQL License (see NOTICE) — `V3` the
four queues, and `V4` D-98's one-open-task index with NULLs held equal (`NULLS NOT DISTINCT`, PostgreSQL 15+).
No extension and no custom Postgres image are needed; the database user must own the database.
The HTTP port opens only after the migration, so the chart's startup probe covers it.

**Identity, in five lines** (AU contracts §7):
1. Every route but `/health`, `/ready` and `/automation/health` needs a bearer that verifies — RS256 against
   the realm's JWKS, the configured issuer, unexpired, the audience when one is set. Anything else is `401
   unauthenticated`; there is no header or argument fallback. ⚑ Without `auth.audience`, a token the realm
   issued to any client verifies (the service warns at start). `POST /automation/refresh` also needs one of
   `auth.refreshRoles` (default `ops`) — else `403 refresh-forbidden`.
2. The caller may always run a deployment **as caller** — the door sees the caller's own token.
3. **As author** (the deployer, from the refresh commit via `AUTOMATION_AUTHOR_MAP`) or **as a named identity**
   (`def identity` in the world) needs a `def grant`; the deployer may always run their own deployment as author.
4. A named identity runs on its client credentials, `TTR_CONN_<NAME>` = `<client id>:<client secret>`; unset
   → `403 identity · unmapped`. Running as someone else's *person* author needs IdP token exchange →
   `403 identity · token-exchange`.
5. Debug runs are as the caller, or with a grant as the deployment's `run_as`; the protocol records both.

**Ports:** HTTP 7131. **Image:** `ghcr.io/collite/automation` (a tag `automation/v<x.y.z>` publishes it).
