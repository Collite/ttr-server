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
> are implemented, along with durable status and native dry-run preview batches. Application installation,
> owner routing and remaining preview lowering remain. Durable dry-run close/sealing and local staging retention
> are assembled and tested; Application mounting remains pending.
> Budgeted evaluate, observation-only polling and durable abandonment/cleanup primitives are implemented. Run handlers,
> including evaluate and route/detail/output readers, are not mounted in Application.

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

Deployment owner routing, Application installation and remaining P1 lifecycle integration are pending. No run routes or
maintenance loop are installed in Application yet. Remaining performed payloads, configured PT/ES links, preview lowering and
task deadline handoff must be integrated before exposing this path there.

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

This foundation supports scalar column/literal/parameter arguments and symbolic role/action arguments.
Action predicates use the published TTR-P parser/type checker and `ttrp-emit` plan lowering at the existing
0.17.3 grammar / 0.12.9 translator pins. Each predicate runs once over its verified route snapshot in a private
DuckDB database, selecting row ordinals without another Query call. Boolean composition, SQL null semantics,
case, membership, text ordering, addition/subtraction/multiplication and typed date casts are supported where the compiler
can lower them. Only referenced columns/bindings enter the scratch database. Decimal literals/bindings and
large integers remain exact typed columns, avoiding the plan literal's Double representation. Incompatible
scalar kinds require an explicit cast. Invalid predicates refuse even on empty routes; syntax, typing,
lowering and local execution failures return `501 preview-unsupported` before decision/batch completion.
Repeated wiring stays repeated and the selected rows retain original route order. Division remains an explicit
501 until full validity/reject lowering is integrated; DuckDB's infinity for division by zero is never accepted
as an action predicate result.

Native writer/obligation synthesis, key lookups, calendar/duration expressions and general action argument
expressions still require lowering; selected unsupported operations return `501 preview-unsupported`. P2/P3
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
The assembled runtime installs close and its ledger seal; Application routes remain unmounted.
ES receipt links still need a stable source reference; the current worker proto supplies an inline receipt.
Application shutdown must drain durable HTTP readers before closing the ledger; they do not belong to an
executor's cancellation scope.

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
store after HTTP drain (`ApplicationStopped`). This installs storage only: run routes/workers/maintenance are
still unmounted pending the remaining P1 integration. RunRuntime continues to accept the application-owned
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
naming all of them: `db.host`, `door.queryUrl`, `door.velesUrl`, `auth.jwksUrl`, `auth.issuer`. The chart
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
