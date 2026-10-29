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
> and authenticated route/detail/output readers page the verified Arrow or download Arrow/CSV. Absolute
> evaluation deadlines, runtime assembly, durable execution evidence and the authenticated raw protocol reader
> are implemented, along with durable status and native dry-run preview batches. Application now installs the
> authenticated durable status/protocol/batch readers. Execution runtime installation, owner routing and remaining
> preview lowering remain. Durable dry-run close/sealing and local staging retention are assembled and tested.
> Budgeted evaluate, observation-only polling and durable abandonment/cleanup primitives are implemented. Run handlers,
> Open/evaluate/close and route/detail/output execution handlers are not mounted in Application.

The durable evaluation path has atomic pgmq enqueue/acknowledgement, a permanent execution claim and
bounded coroutine consumers. `RunRuntime` assembles this path with the pinned sessions, governed Query door,
staging and typed ledger callbacks. Application installation remains pending with the other P1 lifecycle work.

The bundle reader keeps its own manifest-v3 DTO as an external consumer. It skips unknown fields and uses
defaults for absent/null optional fields; the compiler's strict internal `RunManifest` reader is not a service
dependency. `RecipeManifestSpec` compares every published recipe field against all six real Hartland bundles,
including parts/combines, bindings, golden-test paths and the labels fingerprint. New compiler fixture versions
must pass that gate when adopted. Recipe detail exposes the golden paths as metadata; it does not execute them.
The descriptor exposes `fingerprints.labels` beside text/values (empty or omitted for older bundles). Labels
never enter approval/staleness checks; approval metadata continues to contain only text/values.

The executor requires an explicit `world/storage` → Query world alias map. Query resolves those aliases through
its existing `QUERY_WORLDS` table. Missing routes fail before a query is issued; there is no fallback to Query's
default connection. Recipe `:name` bindings are translated lexically to the door's `{name}` syntax. Named runs
use the IdP token's own roles, not the caller's. Open expands `port: keys` into scalar bindings; every keyed
output is filtered before staging.

Set `automation.door.routes` / `AUTOMATION_DOOR_ROUTES` to comma-separated aliases, for example
`demo/erp_db=us-erp,demo/automation_db=us-ledger`. Duplicate keys and malformed pairs fail configuration load.
Each alias must also exist in Query's `QUERY_WORLDS`; these values are logical names, never database URLs.

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

`GET /automation/runs/{id}/outputs/{island}.{port}` resolves an exact, unambiguous manifest handle, including
ports containing dots. It shares the pinned island work; decision outputs require durable evaluation first.
The default `format=json` returns `{ rows, rowCount, ref, class?, keyed, truncated }`, preserving decimal precision
and nulls. JSON/CSV accept the same bounded `offset`/`limit` as route readers. Full counts include snapshot markers;
`truncated` reports omitted page rows or any source marker, even outside the page. An empty/out-of-range page
keeps its full count and schema (CSV/Arrow). Invalid handles/formats/duplicate or invalid paging parameters refuse
before execution without failing the run. Ownership and active-session restrictions also apply to downloads.

`format=arrow` streams the exact staged Arrow IPC bytes after byte-count/hash/row-count verification, with
`application/vnd.apache.arrow.stream`; it rejects pagination rather than silently ignoring it. The current
staging store reads a blob into memory for verification before the response starts. CSV uses `text/csv; charset=UTF-8`,
a UTF-8 BOM, `sep=,`, CRLF and quoted comma-separated fields (embedded quotes/newlines are escaped). Text cells
and headers starting with formula characters `= + - @` (including after whitespace/BOM), tab, CR or LF get an
apostrophe prefix; typed signed numbers retain their representation. CSV retains explicit detail marker rows and
adds a `# truncated` trailer whenever the snapshot or page is truncated. Both downloads carry `X-Row-Count`,
`X-Truncated` and an attachment filename based on the run UUID. Corrupt data refuses with the JSON error envelope
before any download headers or bytes; formats never re-encode Arrow or repeat a governed read for another page.

Runtime construction pairs `preview = ApplyPlanner::preview` with the three-argument
`recordPreview = ledger::completeEvaluation` callback on PartRunner. It commits preview/action/write evidence
with the typed evaluate result (`mode` and `guards`) and EVALUATED status in one locked transaction.
The decision-only callback remains a foundation test seam; the assembled runtime always requires the preview.
Identical durable retries are harmless; changed evidence, sealed protocols and terminal/waiting runs refuse.
This supplies the durable summary for status/protocol readers without a second mutable summary column.
A journal failure is shared like other executor failures and the session fails the active run. Budgeted
evaluate/poll is described below; Application mounting and remaining lifecycle work are still pending.

`POST /automation/runs/{id}/evaluate` waits at most the program's pinned `budget.sync` (configured default when
absent). Open pins and journals that budget in milliseconds alongside the batch budget. One supervised session
promise begins evaluation with `ledger::startEvaluation` before Query; retries and concurrent callers share it,
including failures. The begin transition is idempotent and cannot regress an evaluated decision or resurrect a
terminal/waiting/sealed run. Runtime must provide `RunSessions(running = ledger::startEvaluation)`; missing
journalling fails closed before Query. Cancelling an HTTP waiter or exhausting its sync wait leaves shared work
running. Release/shutdown join that work, including a blocked begin journal, before closing the runner.

Fast success returns the existing `200 { verdict, guards, mode? }`. A sync wait expiring returns
`202 { runId, status: "running", location }` with `Location: /automation/runs/{id}` and `Retry-After: 15`.
An in-progress idempotent open also carries Location/Retry-After. While the begin journal is pending, the poll may
still report `open`; no Query read starts before RUNNING is durably accepted.

`GET /automation/runs/{id}` reads committed status from the ledger, including after restart, failure, abandonment
or seal. Its projection includes `{ runId, status, verdict?, guards, mode?, protocolRef }` plus pinned recipe,
program, deployment, runAs, author, asOf, bundle/fingerprints, dryRun, replayOf, startedAt/endedAt and stored error.
A linked batch contributes `{ id, state, rows }`. The verified initiating JWT subject is part of the first SQL
filter; unknown, other-owned and legacy rows without a subject all return `403 identity`. The read uses a single
repeatable-read snapshot for run, decision and batch. It needs no current bundle, local promise or credentials.
Open/running responses include `Retry-After: 15`; every other persisted status is a `200` observation without
a retry hint. An evaluated decision is visible as soon as its transaction commits, independently of the local
promise. Failed status carries the stored public error code; execution requests still replay their original
typed refusal. Polling never executes Query, applies an execution budget, retries a journal or mutates the run.
Preview actions now appear in `actions`; task/delivery projections and close remain later lifecycle work.

Engine polling contract (D-47):
1. Open with an idempotency key and retain it with the run id. After a new open, call evaluate.
2. On `202`, follow Location every 15–30 seconds, respecting Retry-After. Keep the same run id/key while it is
   running; do not create a new key to bypass a slow evaluation.
3. On evaluated, consume the verdict/route refs. On failed/abandoned status or a guard, identity or policy refusal, stop;
   never automatically restart a refusal.
4. A lost transport response can be retried with the same key/run id. A `503 run-abandoned` permits a bounded
   restart with that same key (bound attempts and elapsed time in the engine); stop when that bound is reached.
   Production queue/maintenance wiring and owner routing are pending. With these
   unmounted session handlers, a missing/released context fails closed; restarting cannot reconstruct today's recipe as
   the old run, and an existing key may remain held until the ledger's existing budget-plus-two-minute claim rule.

`RunSessions` enforces the batch budget from the **committed** `runs.started_at` returned by a new claim,
not from HTTP arrival, user `asOf`, delivery or evaluation start. Idle time, queue wait and all evaluation waves
share that deadline. The monitor rechecks the injected clock at most every 250ms, and API reads/polls check it
before starting work and before publishing success. Expiry latches `504 budget`, cancels the original run scope
(including shared reads, combines and queued dispatch), and retries its failure journal without a new Query.
A clock rollback cannot resume expired work or replace an earlier guard refusal. Disconnected HTTP waiters
still leave live work alone. Successful durable evaluations stop this execution timer and retain their result.

Runtime must pass the **same configured clock** to Open, Ledger, RunQueue and RunSessions, the configured batch
default to RunQueue, and `failed = ledger::failExecution` to RunSessions. The typed failure callback persists
only the public error code, never exception text/credentials. Begin, new decision, output publication, queue
enqueue and execution claim also enforce the pinned batch → sync → default budget under the PostgreSQL run
lock, at the exact deadline without grace. Lock waits cannot authorize a late commit. Identical already
committed decisions remain idempotent. Cancellation is rechecked after blocking blob publication before its
ledger insert; unreferenced blobs remain for retention. Release joins the deadline monitor and all cancelled
blocking work before closing DuckDB. The DB checks protect late results independently of timer scheduling.

The cancellation path is implemented for active P1 execution. Production clock-skew/runtime mounting,
preview/close lifecycle deadlines and later WAITING/task handoff remain integration work; the execution timer
must be disarmed when ownership passes to a waiting task. A lost process has no local timer or reconstructible
context: its row still waits for durable budget-plus-grace abandonment before a bounded same-key restart.

`Ledger.abandonExpiredRuns(limit = 100)` supplies the durable clock sweep. It selects at most `limit` expired
OPEN/RUNNING/EVALUATED rows with `FOR UPDATE SKIP LOCKED`, so pods can sweep concurrently without waiting on
each other's rows. Expiry is strictly after `started_at + budget + 2 minutes`, using the stored batch budget,
else stored sync budget, else configured batch default, and the injected clock (Application already applies
`automation.clock.skew` to that clock). Unkeyed runs expire too. WAITING and terminal runs are excluded.
Abandonment, endedAt and the stable error code commit together; telemetry is called after commit. A sweep
and a same-key reopen cannot abandon/count the predecessor twice or overwrite a competing close/waiting transition.

After each sweep, runtime must call `RunSessions.reapAbandoned()`, including when the sweep returns no IDs:
another pod or an idempotent claim may have abandoned a local run. Reaping joins cancelled work before closing
the executor, preserves a fresh same-key executor and leaves waiting/failed-refusal sessions intact. A failed
status lookup leaves that session for retry while cleanup continues for other sessions. Output publication now
requires an outermost READ COMMITTED ledger transaction and checks active status/sealing under the same run
lock; late writes cannot publish even an identical retry after abandonment/close/failure/waiting/sealing. Existing
immutable outputs remain evidence. A rejected journal can leave an unreferenced blob for retention, as below.
These are independently tested primitives. The worker loop below invokes a required runtime maintenance
callback; production mounting and blob retention remain integration/lifecycle work.

Durable evaluation uses `ledger/RunQueue` and V5's `run_evaluations` journal. Enqueue locks the run, checks
active/unsealed status, and commits one dispatch row plus one pgmq message together. Repeated enqueue by the
same owner reuses that ticket; another owner refuses. The payload contains only run/owner UUIDs. Each process
uses a fresh incarnation UUID, never a stable pod/host name; bearer tokens and the original pinned bundle,
bindings and execution resources stay in its `RunSessions`. pgmq reads filter by owner before hiding messages,
so another process cannot steal an original context's trigger or keep it invisible to its owner.

Before any evaluation Query, a worker locks the run and matches the owner/run/message against the dispatch
journal. It commits a permanent `claimed_at` and RUNNING together. A visibility expiry, concurrent consumer or
redelivery cannot grant another execution, even if acknowledgement was lost. Unknown handles and terminal/
waiting/sealed runs cannot acquire authority. A crash after claim is deliberately not retried: the run waits
for abandonment, then the engine can reopen the same key. A new process never reconstructs the old run from
today's bundle/caller. Unclaimed delivery can be retried while its original context still exists. Durable
terminal cleanup archives triggers for evaluated/waiting/terminal runs, including lost owners, in bounded
locked batches; acknowledgement and its journal stamp commit together.

`RunWorkers` starts exactly `automation.runner.workers` consumers (default 4, valid 1..256;
`AUTOMATION_RUNNER_WORKERS`, or the chart's `extraEnv`). Each consumes one job at a time. Runtime must pass
`dispatch = workers::evaluate` to every PartRunner; that single hook covers direct route/output reads as well
as POST evaluate, and the runner still shares one evaluation promise across callers. Without this optional
hook the existing isolated executor runs inline; production must install the hook before mounting run routes.
Each dispatch receives the original supervised per-run scope, not the application scope. HTTP disconnects
leave its work alive; missing/released contexts never acquire a claim. Refusals remain cached without new Query.

The worker's periodic loop requires a `maintenance` callback that performs the ledger sweep on IO and then
`sessions.reapAbandoned()`; it subsequently archives finished triggers. Construct the session registry before
starting workers so this callback cannot observe an uninitialized registry. Queue/maintenance errors back off
and retry without ending the consumers. Stop/join workers before `sessions.shutdown()` closes executors, and
finish both before closing the database. Shutdown joins in-flight enqueue and active evaluation cancellation.
`RunRuntime` installs this dispatcher and maintenance callback in one place, with the configured worker count,
batch/sync/default detail limits, explicit storage aliases, `ledger::failExecution` and the shared clock. It
constructs the registry before starting consumers; maintenance sweeps the ledger, reaps local abandoned
sessions even when the sweep returns no IDs, then archives finished tickets. Its default queue uses the same
clock and configured batch default. The caller must construct Ledger with that clock too, including clock.skew.

`api.runRoutes(runtime)` installs Open/evaluate/poll, part/island, route/detail/output and protocol handlers inside
`authenticate(AUTOMATION_AUTH)`. Authenticated HTTP assembly specs exercise the real runtime with mocked
ledger/queue/Query boundaries; PostgreSQL component specs use the real ledger, default queue and workers.
Runtime shutdown first fences new opens/reads, then joins worker cancellation and releases session resources.
Concurrent shutdown calls join the same cleanup; database, Query channel and staging must remain usable until
it returns. These dependencies belong to the application and are not closed by the runtime.

Application installs only the durable `GET /automation/runs/{id}`, `/protocol` and `/batch` readers inside
bearer authentication, using the same ledger as the service. They need no served bundle, local executor or
execution owner, and remain available after restart/seal/expiry. Status polling never starts execution or
maintenance. Open/evaluate/close, staged output reads and the worker/maintenance loops remain pending with
deployment owner routing and the remaining P1 lifecycle integration. Performed payloads, configured PT/ES
links, preview lowering and task deadline handoff still gate that execution installation.

The production-module component specs use the actual bootstrap/migrations and JWT verification against
the real PostgreSQL fixture, without test-only route assembly. They cover restart reads, pinned metadata,
exact numeric JSON, authentication/subject ownership, malformed handles, no-preview/PT refusals and the
absence of execution side effects. Database and Metadata cleanup runs at `ApplicationStopped`, after HTTP
drain, so a stopping notification does not cut off in-flight durable readers. Full runtime shutdown still
must join its workers and executors before dependency cleanup.

### Debug-run walkthrough

From the repository root, `scripts/debug-run.sh <base> <recipe> <deployment> [keys…]` opens a fresh
dry run, evaluates it, polls asynchronous completion, reads a one-row preview of each route and retrieves
the raw nine-section protocol. It prints route counts, unmatched candidates and up to ten authored English
would-lines. Linux/macOS, Bash and Python 3.10+ are sufficient; there are no Python package dependencies.
The bearer comes from `AUTOMATION_BEARER`, never a command-line argument. For example:

```bash
# Set AUTOMATION_BEARER in your shell using your existing login procedure.
scripts/debug-run.sh https://automation.example.test release_order native_debug 123 456 \
  --as-of 2026-10-23T00:00:00Z --output /tmp/au-debug-capture

# Exact single-key or event ports use the API's JSON spelling explicitly:
scripts/debug-run.sh https://automation.example.test release_order native_debug --port '{"key":123}'
```

Use a served recipe/deployment and a caller authorized for that deployment. Positional keys use a `keys`
port: JSON numbers/booleans/objects retain their type, other text is a string; use `'"001"'` for a numeric-looking
text key. With no keys the port is `{ "none": true }`. `--port` supplies the complete port object, including
composite keys, events or an explicitly empty keys array. Decimal key literals are sent exactly. `--as-of`
is optional; otherwise the program's as-of policy applies.

`--timeout` bounds the entire walkthrough (default 300 seconds), including HTTP reads and polling. Requests
are not retried automatically. Redirects and server-provided Location/ref URLs are not followed with the bearer.
The script reports refused HTTP requests and failed/abandoned/waiting runs as errors. It leaves the run open
for inspection and never calls perform, connectors or close. Each invocation uses a fresh idempotency key.

`--output` must name a new directory under an existing parent. It captures `open.json`, `evaluate.json`, final
`status.json`, numbered route previews and `protocol.json` as raw response bytes. Directory/file permissions
are 0700/0600; partial captures remain on failure. These files contain run data: sanitize a live capture before
adding it as a shared fixture. Exact decimal/large-integer response bytes are preserved. Closed replays use only
durable readers because their route snapshots are no longer executable.

The script is ready for a runtime with run routes installed. **Application currently leaves those routes
unmounted**, so this does not certify a live Hartland run. Remaining lowering, deployment/lifecycle installation
and the independent P1 exit still gate that proof. `just test-automation-debug` runs eighteen mocked HTTP contract
tests; the ordinary `automation-debug` CI job runs the same suite without services, network or Docker.

### Native dry-run previews (S1.3 foundation)

Open pins action definitions and entity key metadata with the recipe. After summarization,
`ApplyPlanner.preview()` observes completed output promises and rereads verified immutable Arrow. Calling
the planner before staging refuses without executing Query. Verdict/detail inserts and native keyed
set/transition effects form an ordered apply preview; detail rows retain composite keys, row numbers and
truncation markers. The native transition carries its from/to/history intent; P2 must do the real state read,
silent-self-transition handling, writable checks and apply lowering before any model write.

Each selected route row × wiring records `{ key, route, action, sink, arguments, batchRows, would?,
event: "preview", dryRun: true }`. `would` renders the authored English text from resolved arguments; missing
English text produces no would-text. Detail arguments contain shared snapshot references, each action's own limit,
retained data-row count and truncation flag. References use the existing paged snapshot URL; the action limit
is metadata and cannot bypass the HTTP page cap. Qualified manifest wiring now participates in authored
detail-limit resolution. Connector actions are evidence only; no task, delivery or connector is invoked.

The held run lock commits the batch header/rows, preview action/write evidence, decision and EVALUATED status
in one transaction. Deadline monitoring remains active through planning and completion, and the journal checks
the budget again after inserting preview rows. A failure rolls back the whole completion. Identical retries
publish once; changed rows or action text refuse. JSONB numeric spelling changes compare by exact decimal value.
The ledger JSON codec preserves large numeric literals at JDBC and HTTP boundaries without converting through
Double. Empty reads-only evaluations still leave a real preview batch with zero rows.

Status includes the preview batch summary and action descriptions. `GET /automation/runs/{id}/batch` returns
`{ batch, run, mode: "preview", rows: [{ seq, entity, op, key?, row?, set?, attribute?, from?, to?, history? }] }`.
It is a full ordered apply subset for the initiating verified subject, independent of a local executor/bundle.
Unknown/other/legacy subjects answer the same 403; an owned run without a batch answers `409 batch-not-ready`.
Reading never evaluates. In `batch_rows`, inserts store their row directly; update/transition rows store their
named operation payload in `row`, with key/group in the corresponding columns. Rows remain `staged`.

Scalar action arguments and predicates share the published TTR-P parser/type checker and `ttrp-emit` plan lowering at the existing
0.18.11 grammar / 0.12.11 translator pins. Each predicate runs once over its verified route snapshot in a private
DuckDB database, selecting row ordinals without another Query call. Boolean composition, SQL null semantics,
case, membership, text ordering, two-operand `concat` (0.18.11; strict NULL; cast numbers and dates explicitly),
addition/subtraction/multiplication and typed date casts are supported where the compiler
can lower them. Only referenced columns/bindings enter the scratch database. Decimal literals/bindings and
large integers remain exact typed columns, avoiding the plan literal's Double representation. Incompatible
scalar kinds require an explicit cast. Invalid predicates refuse even on empty routes; syntax, typing,
lowering and local execution failures return `501 preview-unsupported` before decision/batch completion.
Repeated wiring stays repeated and the selected rows retain original route order.

Action-typed wiring calls and explicit `on_due` calls now produce deferred preview evidence, for example
`{"action":"start","arguments":{"process":"purchasing"},"deferred":true}`. Supplied scalar leaves use
that same compiler projection over condition-selected rows; omitted partial-call arguments remain absent.
Relative `due` values preserve an exact count and unit, e.g. `{"amount":2,"unit":"working_days"}` or a
nonnegative integral pinned parameter via `working_days(:settlement)`. This is duration evidence, not an
absolute deadline: preview does not consult a calendar or turn working days into elapsed days.
When wiring omits `due` and the action declares no `due` column, its pinned action-level duration default
is used. Undeclared `due` accepts only a duration or NULL, and undeclared `on_due` only a call or NULL;
wrong explicit kinds refuse, including on empty routes. Explicit valid wiring presence wins, including NULL. Defaults must be finite nonnegative integral
Long counts in one of the six supported units. TTR-M accepts `seconds`, but native preview refuses
that unit because the published TTR-P DurationUnit does not include it.

The published wiring parser checks the entire value, including nested calls. Malformed/duplicate keys,
unknown parameters, wrong/fractional/null/negative counts, overflow, excessive call depth and unsupported
row/detail capture refuse even on empty/unselected routes. A nested argument that names a context or
the candidates refuses, even if that name also denotes a scalar route column; callee column types are
consulted only after P3 pins nested callees. Nested `due` must be a duration or NULL, and nested `on_due`
a call or NULL, at every depth. Until P3 pins callees, this also refuses a nested callee's declared
`due` column wired with a date. Root scalar arguments keep projecting. Nested scalar leaves
retain original source text (code-point spans), avoiding a second string-escaping pass. Unused casts are
checked without executing.
Would-text renders deferred calls and relative durations alongside existing scalars. The full Hartland H3
fixture now previews both the proposal and buyer ask. Undeclared `due`, `on_due` and `group_by` stay in
action evidence and out of writer assignments; declared columns with those names remain assignments.
`group_by` remains scalar evidence on each selected row;
no task grouping/creation, row capture or nested dispatch occurs here. A callee is not resolved/defaulted or
executed. Future dispatch must establish its own dependency/approval closure before interpreting any
`deferred:true` object; these objects are not executable action envelopes. The action-level `on_due` default
is not in the evidence; P3 must apply it before interpreting a missing `on_due`. Root bounded detail refs keep
their existing form. All evidence retains the existing atomic completion, retry and durable read behavior.

Division uses the published ValidityCatalog and RejectGuardSql nonzero guard. The guard's portable SQL subset
runs over compiler/translator-projected divisors in the same private DuckDB database, before any quotient or
predicate. Nested divisions validate bottom-up. NULL divisors succeed with NULL results; zero divisors refuse
the whole preview with generic 501, including in case branches and boolean expressions. No invalid rows are
silently dropped. Nonfinite divisors/quotients also refuse before a predicate, cast or case can hide them.
Division follows compiler numeric typing (float/integer operands need explicit casts) and DuckDB true-division
results. Guards inspect only condition-selected rows for scalar arguments; empty selections create no rows and
do not evaluate division values, while syntax/type validation still runs. No new Query/model/connector call or
pin change is involved. Other PostgreSQL-specific guard families are not admitted by this adapter.

Each wiring's scalar arguments run as one projection over only its condition-selected rows. Arithmetic,
case, null/boolean expressions, explicit casts, literals and bound parameters retain typed results and
original route order. The projection carries a private row ordinal and checks one output per input row.
Bare/dotted symbolic names remain supported for role/action/text arguments; an exact route-column name
wins. Expressions with operators must compile; invalid syntax is never treated as symbolic text. Rows/detail
arguments keep their bounded references. Invalid scalar expressions refuse even on empty/unselected routes;
execution errors in discarded rows do not affect selected rows. Local execution errors and non-finite scalar
results refuse with generic 501 errors. All planning finishes before the atomic
completion, so any later argument failure leaves no partial decision/batch journal. No extra Query call or
model/connector write is made.

Bound-date durations use the compiler's public `SugarLowering` before predicate/scalar compilation.
`:as_of - days(30)`, explicit duration parameters such as `days(:lookback)`, integer/day-count parameters,
weeks/months/hours/minutes and nested bound derivations resolve against pinned values, without reading a
clock or calendar. Typed dates stay dates for day/week/month arithmetic; subday arithmetic yields timestamps.
Timestamp offsets normalize to UTC. The shared manifest binding resolver now handles published minute
bindings and date parameters, including the producer's datetime tag, and refuses fractional/wrong-type counts
instead of truncating. Authored parameter names validate before synthesis, and fresh private bindings never
replace authored values or make unknown references resolve. Invalid/overflowing operations refuse before
completion, including on empty routes. The compiler's wiring/window spelling `30 days` remains invalid inside
scalar expressions; use `days(30)` there.

Native `obligation` actions with no set/transition can now propose open obligation inserts. The action
must declare `kind: text`, `due: date`, and a complete, unambiguous key mapping to one business entity through
its other column types. These are column declarations, not assumptions about the action's name: the stock
`expect` uses `line: return_request_line.line_id`. Qualified targets consume the verifier's exact pinned
resolutions; legacy recipes retain their existing unqualified lookup rules. No missing pin falls back.
The compiler checks key/kind/date assignments even on empty or unselected routes; selected null keys/dates,
blank kinds, malformed mappings, schema mismatches and declared key bounds refuse before completion.

Each proposed `er.entity.obligation` row carries `id`, `recipe`, `run`, `entity`, `key`, `kind`, `due`,
`state: open` and `created_at`. Its `key` is canonical JSON stored as text, retaining exact numeric values;
`created_at` is the pinned Open instant expressed as a UTC timestamp, with full precision. The opaque preview
UUID is deterministic from the versioned namespace, run UUID, target entity, canonical key and kind. It is
independent of route/wiring order and preview clock; it does not allocate a physical model identifier.
Equal proposals for the same target/key/kind share one row, and every action references that row's sequence.
Different due dates for that identity refuse the whole preview. New runs have distinct preview identities.

This is proposed insert evidence, without an existing-open lookup: it does not claim that a physical insert
would beat the one-open uniqueness check. P2 must check existing open obligations and decide insert/no-op
atomically. Insert synthesis makes no additional Query/model/connector call. Re-evaluation, close and durable
batch/protocol reads preserve the same proposal.

Native obligation transitions now resolve a complete business key and text kind against a governed snapshot.
The transition must target the pinned stdlib `obligation.state`; its target state is fixed or supplied by a
text column. Mapping and assignment validation also runs on empty/unselected routes. The stock `fulfil`
action is one example; action names have no special meaning.

Open pins the obligation's logical storage using the compiler's placement and world-overlay rules. This
is the world's declaration (Hartland uses `automation_db`), not a hard-coded physical database name. The
lookup supports an unbound stdlib obligation only. Published grammar/metadata 0.18.16 resolves the actual
pinned model with the bundle's complete loader package map, including injected stdlib. World hosts are
validated against declared-or-computed packages, independently of checkout paths; unknown or partial
packages and an incomplete provider refuse before a claim. Writable/engine/overlay declarations remain
in the resolver input. Missing, ambiguous or invalid placement refuses. Query still requires an explicit
world/storage alias. Calendar-count source reads and published graph execution are described below.

Open refuses a pinned fulfilment action unless `er.entity.obligation` is in the manifest's `objectsRead`,
before placement, run claim or idempotency allocation. Evaluate checks this scope again. Published Core#319
(0.18.16) derives this read from directly wired action metadata, so the fulfilment fixture no longer needs an
artificial obligation JOIN. Open, preparation and preview use Core's shared `AutomationScope.needsObligationLookup`
predicate. Removing the JOIN changes SQL/text evidence; the fixture records a newly approved text fingerprint,
and its old approval refuses before any claim. Nested call read derivation and calendar action-expression
reads (Core#330) remain separate follow-ups.
Placement successes and refusals are prepared once per immutable bundle/world, sharing coverage's model
snapshot and index. Refreshed bundles have their own cache. Both insert and fulfil require the same stdlib
obligation schema.

After validating/projecting all wirings, the runner collects the selected (entity, canonical key text, kind)
identities. It issues one parameterized id/entity/key/kind/state read, filtered by the requested entities,
kinds and exact key text. Matching in memory requires the exact triple, so cross-product rows cannot match
another requested identity. At most **300 distinct identities** are allowed (at most **900 bound values**,
below a portable 999-parameter limit); excess refuses `PREVIEW_UNSUPPORTED` before any lookup, without
splitting or truncating. No selected fulfilment means no auxiliary read.

The lookup uses the pinned model, identity, ceiling, timeout and scope preflight. A security-narrowed lookup
always refuses `RESTRICTED_VIEW` with guard evidence, including when `allow_restricted_view` permits narrowed
decision reads. Truncation and scope guards still apply. The template is `runtime:obligation-lookup/v2` in
protocol evidence. Verified Arrow is journalled as `_au_obligations.rows`, retained with the run, and reused
by retries/replans, including failure; a different identity set on replan is an invariant failure. This is an
internal snapshot, not a new manifest output/download route.

Insert and lookup share canonical key text: sorted JSON object fields and exact numeric normalization
(`BigDecimal.stripTrailingZeros().toPlainString()`), with no null or structured members. Strings remain
distinct from numbers. Stored keys must equal this text exactly; a different field order, whitespace or
numeric spelling is not found and refuses as missing. Legacy bare entity names/scalar keys are not inferred.
One active obligation
(open/overdue/reopened) wins over closed history; multiple active matches, missing matches, invalid values
or malformed snapshots refuse. If no active row exists, only matching history entirely in the requested
state is a silent self transition. Equal effects share one row; opposing effects on one ID refuse. Proposals
carry the observed state as `from` and request history only for a change. Lookup guard results join evaluate's
evidence before atomic decision/batch/action completion. On refusal, successful read evidence remains but
no partial batch or action is committed. No model row is changed or signal sent; P2 must recheck current state,
apply writes/history and enqueue signals atomically. General indirect entity lookups and additional time forms remain pending.

Explicit native `writer` actions now produce insert previews. Each declared action column must name one
attribute of the same pinned ER entity; every declared key and non-nullable field must be mapped and supplied.
Arguments use compiler assignment unification, allowing lossless integer/decimal and date/timestamp widening
while requiring authored casts for incompatible kinds. This check also runs on empty/unselected routes.
Selected values retain exact numeric JSON and composite keys. Omitted optional fields remain omitted;
explicit NULLs remain assignments only for nullable non-key fields. State vocabularies and preserved
text/decimal bounds refuse invalid values without truncation or rounding. Duplicate/unknown/ambiguous target
mappings, aggregate attributes, mixed effects and canonical entity-name collisions refuse with generic 501.
Actual JDBC values are checked before JSON conversion: floating-point results cannot enter integer or decimal
writer fields, even when they look integral. This includes DuckDB true division of compiler-typed integers;
an authored cast specifies the intended conversion. Decimal casts can carry explicit precision/scale, with
the target's preserved bounds checked separately. Date widening produces midnight timestamps (UTC for
`datetime`) in both the insert and action evidence, rather than leaving a date-shaped value.
Each selected row × wiring gets one insert and the action's effect-row references/would-text; repeated wiring
stays repeated. Any later refusal leaves no partial decision/batch journal and triggers no extra Query work.

The 0.18.7 metadata API preserves authored attribute bounds separately from the bare type name. For decimal
attributes, `length` is total digits and `precision` is scale; for text, `length` counts Unicode codepoints.
Preview validates these declarations without rounding or truncation, including scale zero and declarations
containing only one bound. A length-only decimal limits occupied digit positions; a scale-only decimal limits
fractional digits without a total-digit default. Bare types acquire no bounds from compiler defaults. Invalid
declarations refuse when the writer mapping is prepared, including empty selections. Existing inline bounded
type spellings remain supported. All consumers must rebuild because the Attribute JVM signatures changed.
The translator and plan-proto pins move together to 0.12.11, the version required by the published emitter.

Governed writer defaults use the published approval verifier from 0.18.9, unchanged through 0.18.11. At load, the Server keeps
`recipe.approvalDependencies` as JSON, checks its version first, and then strictly reads the v1 fields.
Missing/null/renamed v1 fields, unknown v1 keys, non-object evidence or future versions refuse only
that recipe with `409 recipe-stale` at Open;
the refreshed bundle and unrelated recipes stay available. Recorded imports use complete reconciled loader
files (including injected stdlib). Every file in a governed recipe's scope must declare `package`, because
the producer and Server use different loader roots. A checkout under `/tmp/automation-bundles/<branch>` works.

Coverage resolves once per loaded recipe. Open checks grants before revealing coverage details, then uses
that stored result to verify approval eligibility before claiming. Preview uses the exact resolved actions,
entities and attributes that passed the verifier for every native effect: writer, set, transition and
their key columns. Both paths choose pinned or legacy explicitly; missing pins never fall back to names.
The ER entity list is pinned once in the bundle. Mapping
completeness and default validity remain preview-preparation checks. The descriptor and registry reuse the
same stored resolution. Optional exported text/values/program/deployment fingerprints still must match.

Coverage refusals retain `differs:[text]`, `approved.text:null` and `current.text`, and add
`coverage: missing|malformed|unsupported|unresolved|ambiguous|stale`. On deployment detail,
`coverage: {recipe: kind}` is separate from `unapproved`; either makes `deployment.stale` true. An approval
of the displayed text cannot repair invalid coverage: rebuild/correct metadata or scope, or upgrade the
consumer for unsupported evidence. Only valid coverage can progress to normal fingerprint approvals.

Default-free legacy bundles retain text/1. Without recorded scope, action names shared across packages
refuse as ambiguous: rename the action or adopt evidence; rebuilding the same legacy recipe alone cannot
fix the ambiguity. Existing startup-gated explicit development mode (`automation.development=true`,
`automation.approvals=off`) can exercise defaults without evidence; present evidence always verifies and
opening policy records the bypass. The descriptor/registry always report governed approval and coverage
status, even in development mode; a missing-coverage refusal there does not gate the explicit development
Open bypass. Unprepared coverage still refuses in both modes. No new bypass is added. These dependencies cover native preview only;
physical writes, connector configuration and deferred actions need their own closure. Core#294 can replace
a stdlib entity with a same-qname project entity; an unavailable qualified target refuses safely.
The published fixture and real Open/evaluate/ledger proofs are under `src/test/resources/approval-evidence`.

Native writer actions can explicitly declare `writer_defaults` for optional action columns. For example,
with columns mapped to `proposal.state`, `proposal.run` and `proposal.created`:

```ttrm
writer_defaults: {
    state: { expression: "\"proposed\"" },
    run: { context: run_id },
    created: { context: started_at }
}
```

Each entry names an existing `optional: true` action column and contains exactly one source: a nonempty
TTR-P scalar `expression` string, `context: run_id | started_at`, or `generated: bigint_id_v1`. Expressions use verified staged route
columns and existing pinned bindings such as `:as_of`; they cannot reference another action argument or
context default. `cast(:as_of as text)` renders the UTC instant (`YYYY-MM-DD hh:mm:ss+00`), so a label
built from it uses the UTC quarter. A literal string needs quotes inside the expression. The context sources are the pinned
run UUID as text and the committed Open instant as datetime; neither reads a clock nor allocates an ID.
Context values retain their full precision, with compiler assignment checks before direct serialization.
An explicit wiring argument overrides its default, including explicit NULL; NULL does not trigger fallback.
Defaults do not relax target key/nullability, type, state or bound checks. Declarations and expression types
validate even for empty/unselected routes and overridden defaults; unused expression results do not execute.
Only condition-selected rows evaluate used defaults. Effective values appear in the batch, action arguments
and authored would-text, and share the same atomic completion/refusal as explicit arguments.
Duplicate/unknown/malformed entries, unsupported context names and defaults on non-writer actions refuse.

This contract uses the published metadata's free-form `AutomationAction.properties` lane; grammar/metadata
are now 0.18.11 and translator/plan-proto 0.12.11. The current generic semantics validator warns about the new
property; the Server consumer owns its strict validation. No grammar syntax or typed library API changed.
`generated: bigint_id_v1` requires a mapped ER **bigint key** and derives an exact negative long from
SHA-256 of length-prefixed UTF-8 fields: `automation/writer/bigint_id_v1`, pinned run UUID, zero-based full
manifest wiring ordinal, route name, action column and canonical candidate-key JSON (sorted names, exact
normalized numeric scalars). The first eight digest bytes are a big-endian long with the sign bit set.
This versioned format preserves IDs across retries and candidate row reordering, distinguishes repeated
wirings, and stays separate from positive sequence IDs. It uses no row ordinal, clock or sequence read.
Generated values enter insert keys/rows, action arguments and would-text together, preserving long precision.
Explicit arguments still override; NULL keys refuse. Wrong target types refuse even on empty/unselected
routes or with overrides. Duplicate native writer entity/keys refuse atomically when either insert used
generation. The finite 63-bit hash space does not guarantee global uniqueness: P2 must refuse an existing
physical primary-key conflict transactionally, without overwriting/skipping/regenerating. Preview never
queries or writes the target model to allocate an ID. The evidence format from 0.18.9, unchanged through 0.18.11, covers the authored source.

Hartland's authored default mappings/bundles are staged in #122; deployed adoption remains pending.
Calendar counts and bound-date-minus-column counts are supported as described below; other column-date forms remain pending. P2 must validate actual physical bounds and writable policies
before applying anything. H-3/H-5 are not certified by these defaults. Selected unsupported operations return
`501 preview-unsupported`. P2/P3
retain dry-run task/delivery rows and performed action outcomes. Durable close/sealing and local staging retention are described below. Application mounting is
pending. The local Query fixtures do not certify live Hartland behavior.

### Durable execution protocol (S1.3 foundation)

`GET /automation/runs/{id}/protocol` (or `?render=raw`) returns
`{ schema: "protocol/v1", run: "<uuid>", sections: { automation: [{ seq, at, payload }], islands: [], … } }`.
Every document includes all nine sections in contract order, with per-section sequences and UTC timestamps.
The read uses one PostgreSQL snapshot and has no dependency on a live session, the current bundle or Query.
V6 persists `runs.caller_subject` from the verified JWT separately from the display principal. Only that subject
can read a run's document, including after a restart, failure or seal. A renamed principal keeps access; another
subject reusing its name does not. Unknown, other-owned and legacy rows without a recorded subject all answer
`403 identity`. Existing subjects are never guessed or backfilled from display names. The bearer is not stored.

Open records recipe/program/deployment, bundle/fingerprints, author/caller/run identity, port/as-of, origin and
pinned policy. Successful shared storage parts and published island ports append typed `islands` evidence:
kind/name/port, statement path relative to the pinned recipe, engine/logical storage, declared typed params,
rows, duration and security warnings. Each shared source is recorded once, and each output port once. Output
counts/bytes describe the staged snapshot after key filtering and detail limits. Source byte counts are absent
because the door returns decoded rows. Durations use monotonic elapsed time and include prerequisite/slot waits;
protocol timestamps use the runtime's configured clock. Combine evidence references the executed DuckDB file.
Output metadata and its evidence commit atomically after immutable blob publication. The run lock serializes
concurrent records, identical evidence retries do not append again, and changed evidence, sealed/terminal rows
or elapsed execution budgets refuse publication. A failed source journal prevents its dependent outputs.

`?render=pt` currently returns `501 protocol-renderer-unavailable` after ownership verification. Configured PT
proxying, performed action/outcome payloads, stable ES links and remaining preview lowering remain S1.3 work.
The assembled runtime installs close and its ledger seal; Application installs durable readers while
close and the other execution routes remain unmounted.
ES receipt links still need a stable source reference; the current worker proto supplies an inline receipt.
Application keeps the ledger and Metadata alive until `ApplicationStopped`, after durable HTTP readers drain;
they do not belong to an executor's cancellation scope.

Local staging publishes immutable Arrow blobs before journalling their metadata. A journal failure leaves an
unreferenced blob for the S1.3 retention job; it never deletes content another claimant might reference. The
configured Redis/S3 adapters and their lifecycle are described below. The checked-in real bundle fixture
records its provenance under `src/test/resources/runner/hartland/README.md`.

### Durable dry-run close and local staging retention

`POST /automation/runs/{id}/close` requires a completed preview and the verified initiating subject. Under
one run lock and the original absolute budget it adds final write evidence (zero applied rows), closure and
seal, and commits CLOSED/endedAt. Pending or missing previews answer `409 close-not-ready`. Closed retries
return the same result after restart or budget expiry and retry executor cleanup if it failed. The preview,
model, tasks, deliveries and connectors remain untouched. Sealed execution endpoints answer `409 run-closed`;
status, batch and protocol remain readable without an executor. Cleanup joins local work before DuckDB close.

RunRuntime's maintenance timer now invokes StagingSweeper. Eligibility is the earliest of close + graceHours,
started_at + stagingHours, or immediate abandonment (defaults: 1 h / 24 h). Boundaries are inclusive and use
the same configured clock as the ledger. Global expiry releases staging even for WAITING; it does not abandon
the run or change its tasks. Task handoff must retain its own durable snapshots when implemented at P3.

V7's staging_released_at fence commits before cleanup. New evaluation, source/output publication and
execution reads refuse released staging with `410 staging-released`; closed/sealed and abandoned runs keep
their existing `409 run-closed` / `503 run-abandoned` refusals. Ownership is checked first. Executor cleanup
precedes blob deletion. Only successful deletion sets unreleased run_outputs.released_at; metadata, hashes,
protocol and preview rows remain as audit evidence. Failure or cancellation after fencing remains retryable.

The scan selects at most 100 runs per maintenance pass, prioritizing never-attempted runs and then the oldest
staging_checked_at. Released runs are revisited to collect delayed blob puts that began before the fence.
Per-run store deletion uses the canonical runs/{UUID}/ prefix, including unjournalled Arrow/temp files, and
ignores ledger-provided paths. The local adapter refuses symlink ancestors and does not follow links inside
a run directory. It preserves sibling runs. The configured Redis/S3 adapters implement the same deleteRun contract;
the default method refuses unsupported adapters rather than marking their output index released.

This lifecycle is verified locally and against PostgreSQL, including runtime maintenance and retries after
restart. Configured remote staging is described below. Application run-route/worker installation, owner routing and
live Hartland certification remain pending.

### Configured immutable staging

Application selects `local`, `redis` or `seaweed` through `StagingStores.open` at startup and closes the owned
store after HTTP drain (`ApplicationStopped`). Durable run readers are installed; execution routes/workers/
maintenance remain unmounted pending the remaining P1 integration. RunRuntime continues to accept the application-owned
store and must finish shutdown before its dependencies close.

| setting | use |
| --- | --- |
| `staging.kind` / `AUTOMATION_STAGING_KIND` | `local` (default), `redis`, `seaweed`; other values refuse |
| `staging.path` / `AUTOMATION_STAGING_PATH` | local root; default `/tmp/automation-staging` |
| `staging.url` / `AUTOMATION_STAGING_URL` | Redis `redis[s]://[user:password@]host[:port][/db]`, or HTTP(S) S3 endpoint |
| `staging.bucket` / `AUTOMATION_STAGING_BUCKET` | existing, dedicated unversioned bucket; required for seaweed |
| `staging.prefix` / `AUTOMATION_STAGING_PREFIX` | relative installation namespace; default `automation/staging` |
| `staging.region` / `AUTOMATION_STAGING_REGION` | S3 region; default `us-east-1` |
| `staging.anonymous` / `AUTOMATION_STAGING_ANONYMOUS` | explicit anonymous S3; default false |

Redis credentials belong in a Secret-backed URL. S3 uses the AWS default credential provider chain; credentials
can be supplied through Secret-backed `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`, or the provider chain's
role mechanisms. `extraEnv` already supports Secret references for these deployment settings. URLs are
redacted from configuration diagnostics; malformed/ambiguous URLs and unsafe namespaces refuse before IO.
The S3 endpoint URL has no bucket path, query, fragment or embedded credentials. Prefixes identify separate
installations sharing a bucket/database; run paths remain `runs/{UUID}/{island}.{port}.arrow` in the ledger.

`transfer-core` now exposes an additive `ImmutableBlobStore` raw-byte port. AU's namespaced adapter preserves
IPC bytes over it. Existing Charon MoveExecutor/RedisEndpoint/SeaweedEndpoint operations remain unchanged:
their row-transfer/overwrite semantics cannot implement immutable AU publication directly. Redis uses atomic
SET NX with no TTL or sidecar; S3 uses conditional PUT (`If-None-Match: *`). Identical existing bytes return
false, different bytes refuse, and missing/corrupt data never becomes empty rows. S3 409 conflicts retry at most
three conditional calls, with no unconditional fallback. The AWS client's individual calls have a 20 s total /
10 s attempt timeout. Redis commands use a 10 s timeout.

Before accepting run output, the S3 factory checks an unversioned bucket and conditional-write support.
It retains one intentional byte at `<prefix>/_capabilities/conditional-create-v1`, outside run directories.
The marker makes concurrent/restarted capability checks safe without transient probe-orphan cleanup. A gateway
which ignores the precondition, or a versioned/suspended bucket, refuses startup. Permissions must cover bucket
versioning inspection/listing and object GET/conditional PUT/DELETE under the installation namespace.

Cleanup uses Redis SCAN or paginated S3 ListObjectsV2 and checked batch deletion under the exact UUID prefix.
A partial S3 deletion error, escaped listing or nonadvancing token refuses cleanup so the ledger stays retryable.
Client/connection/credential resources are owned and closed on shutdown or failed construction. Real Redis
8.2.2 and SeaweedFS 4.48 component tests cover racing immutable writes, Arrow/client restart, scan/pagination,
no implicit Redis TTL, sibling isolation and grace/abandoned/late-orphan retention. These tests run in the
ordinary component tier without an opt-in switch. They do not certify the live Hartland storage deployment.
The Redis fixture uses Google's public Docker Hub cache with the same explicit official version tag. Its digest
matches the former ECR image; ECR's shared-runner data quota refused pulls before any Redis test could start.

## Operating it

**Configuration** is `automation.*` in `src/main/resources/application.conf`, every key overridable by an
`AUTOMATION_*` variable (AU contracts §8). Five keys have no default, and a pod missing one fails at start
naming all of them: `db.host`, `door.queryUrl`, `door.metadataUrl`, `auth.jwksUrl`, `auth.issuer`. The chart
(`k8s/`, also the `automation` key of the umbrella `helm/tatrman-server`) renders them from values and fails
the render by key when one is missing.

**The database migrates itself.** At start the service runs Flyway against its own database (one
`automation` database, schema `automation`): `V1` the ledger, `V2` pgmq 1.13.0 installed **SQL-only** —
upstream's `pgmq.sql`, vendored unchanged and hash-checked, PostgreSQL License (see NOTICE) — `V3` the
four queues, `V4` D-98's one-open-task index with NULLs held equal (`NULLS NOT DISTINCT`, PostgreSQL 15+),
`V5` the durable evaluation dispatch/claim/acknowledgement journal, `V6` the initiating verified subject, and
`V7` durable staging release/scan timestamps.
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

### Governed calendar-count preview

Action expressions, writer-default expressions, wiring conditions and expressions inside deferred calls
can use `age(since: placed)` and `working_days(between: placed, and: :as_of)` over typed date/timestamp
columns and bindings. A bound date minus a typed date/timestamp column (`:as_of - placed`, including
a derived bound such as `(:as_of - days(1)) - placed`) uses the same calendar count; numeric subtraction
remains numeric. The known manifest-duration flattening limitation still blocks literal-duration
subexpressions in published wiring text (for example `days(1)` rendered as `1 days`); compute that bound
upstream or bind a duration parameter until Core U1 is fixed. Open discovers the single model calendar by `semantics { role: event_date }`,
requires its exact ER qname in `recipe.objectsRead`, resolves its binding and world placement with the
published compiler and the complete loader package map, and requires an explicit Query door route before
claiming the run. The calendar plan is cached per immutable bundle/world, including placement refusals;
refresh creates a different cache. Checkout paths never grant scope. Open records the calendar entity,
logical storage, columns and `runtime:calendar-snapshot/v1` template in its protocol evidence.

Evaluation performs one governed calendar read using the pinned model, identity, read ceiling, timeout
and allowed ER scope. The date and bound working-day flag are the only selected attributes. The snapshot
must be complete: security narrowing refuses even with `allow_restricted_view`; a reached ceiling or
validator cap also refuses. Verified Arrow is journalled as `_au_calendar.rows` (or an unused numbered private island when that name is authored). Concurrent consumers and
retries share the same successful snapshot or failure; no auxiliary output/download API is introduced.
A refusal cannot publish a decision, batch or action completion.

The published `RecipeTime` typer, `SugarLowering`, normalizer and parameter-aware `SqlTextPlanner` execute
against that snapshot and only
the referenced staged route columns in UTC DuckDB. Server does not copy the calendar joins/count SQL or
invent weekdays. Compiler semantics count calendar days in `(start, end]`, filter the authored working-day
flag, preserve repeated route rows, propagate NULL dates and return zero for empty/reversed ranges. Empty
routes still validate. The published 0.18.14 fixture under `src/test/resources/calendar-preview` goes through
governed Open/evaluate/retry/close/restart with the real PostgreSQL ledger; only Query tables are canned.
It does not certify a live Query database or perform physical writes.

Relative `due` values remain descriptive. Absolute working-day deadlines, adding/subtracting a duration
to/from a route date, reverse/column-column date differences and integer calendar-key action inputs
remain unsupported and refuse, including on empty routes. Action rows do not carry the key provenance
needed to resolve calendar keys; no integer/date coercion is inferred. This stage changes no runtime/compiler dependency pin.
