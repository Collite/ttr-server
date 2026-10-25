# Tatrman Automation for n8n

Governed automation recipes from n8n, using the identity stored in the Tatrman Automation credential.

P0 provides **Tatrman Run** and **Tatrman Trigger**. Platform Logical Query and Golem Question are planned in P1.
Tested with **n8n 2.44.6**, using its **n8n-workflow 2.44.2** SDK. Node version 1; package version 0.1.0.
The public package name is reserved for a later licensing decision; this Platform package targets
`@collite/n8n-nodes-tatrman` on GitHub Packages. It has not been published or certified on hartland yet.

## Install

After a release is published, install the exact version into a self-hosted n8n community-package directory:

```sh
npm config set @collite:registry https://npm.pkg.github.com
# Configure a read:packages credential for npm.pkg.github.com using your secret manager.
# Do not bake that credential into an image layer.
cd ~/.n8n/nodes
npm install @collite/n8n-nodes-tatrman@0.1.0
```

Restart n8n. Hartland uses the derived image instead, keeping the package outside its persistent `.n8n` volume.
Build and verify it from the packed package (repository root, Docker required):

```sh
pnpm install --frozen-lockfile
just build-n8n-image n8n-tatrman:2.44.6-0.1.0
just test-n8n-image n8n-tatrman:2.44.6-0.1.0
```

The image is Linux/amd64, based on the official `n8nio/n8n:2.44.6` Docker Hub image.
The existing Docker Hub source is retained: the equivalent `docker.n8n.io` endpoint returned HTTP 429
during the 2026-10-25 image proof. Both approved official source names are accepted by the release guard. Its build context is the unpacked
package tarball; it copies `package.json`, `README.md` and `dist/` into
`/opt/tatrman-nodes/node_modules/@collite/n8n-nodes-tatrman`, sets
`N8N_CUSTOM_EXTENSIONS=/opt/tatrman-nodes`, and resolves the runtime's own SDK via `NODE_PATH`.
It runs as the upstream `node` user and needs no npm credential in any layer. n8n's custom-directory
loader registers the nodes as `CUSTOM.tatrmanRun` and `CUSTOM.tatrmanTrigger`; the credential is
`tatrmanAutomationApi`. Package metadata retains the scoped package name. The olymp image switch and
live Server/hartland certification remain pending.

## Credentials

Create **Tatrman Automation API** with:

- **Base URL**: the automation service origin/prefix, without `/automation` or a trailing slash.
- **Token**: a deployment/service bearer for automation, or the answerer's personal bearer for task answers.
- **Iris BFF URL**: reserved for P1's Golem Question.

The credential test calls `GET /automation/health`. n8n applies bearer authentication to every service request.
Door grants, approvals, dry-run restrictions and row-level security apply under this identity.

## Tatrman Run

All fields accept n8n expressions except Operation. Choose a recipe/deployment from the dropdown, or use an ID expression.
Select a recipe for the route/action dropdown; names come from the manifest returned by the automation door.

| Operation | Fields | Output |
| --- | --- | --- |
| Open | Recipe, Deployment, Port (JSON); optional As Of, Bundle, Dry Run, Idempotency Key | Run ID, replay flag and status |
| Part | Run ID, Part (`candidates`, context name or `decision`) | Part preview and reference |
| Islands | Run ID, Island | Island preview and reference |
| Evaluate | Run ID | Verdict/guard result, or completed asynchronous run |
| Routes | Run ID, Recipe, Route | One item per row; fetches subsequent pages |
| Perform | Run ID, Recipe, Action; optional Keys (JSON array), Group (JSON), Callback URL | Performed/skipped records |
| Outcome | Run ID, Recipe, Action, Outcomes (JSON array) | Door acknowledgement |
| Digests | Run ID | Digest groups |
| Close | Run ID | Final status, batch and protocol reference |
| Answer | Task ID, Answer; optional Note, Rows (JSON array) | Commit/supersession result |
| Task | Task ID | Task snapshot |

For example, Open's Port may be `{"key":4711}` or `{"none":true}`. A stable Idempotency Key makes a repeated
open replay the same run. Outcome entries contain `key`, `status` (`delivered`, `failed`, `skipped`), `at`, and
optional `detail`. Keys/Rows/Outcomes must be arrays, and Port must be an object.

Items retain their n8n input pairing. Enable n8n's Continue On Fail to receive `{ error, code, detail }` per failed
item. Otherwise a NodeApiError includes the Server's code; fingerprint `differs` survives in its description
and context. Async Open/Evaluate follow a same-origin `/automation/` Location, honoring Retry-After (seconds or
HTTP date) with a 15-second minimum interval within a 30-second total budget. Both `open` and `running` progress
remain pending. A budget error does not cancel the server run; inspect its progress at the returned Location.
The client never repeats the mutation or restarts a refusal.

## Tatrman Trigger

Choose Deployment and Events: Signal, Answer, Window Closed, Run Closed, or Route Rows.

**Webhook** registers the node's URL and a generated secret on activation. The service must support:

```text
POST   /automation/webhooks   { deployment, url, secret, kinds } -> { id }
GET    /automation/webhooks   -> { webhooks: [{ id, ... }] }
DELETE /automation/webhooks/{id}
```

GET/DELETE remain a dependency of Server P3. The receiver requires `X-Tatrman-Signature` as a hex HMAC-SHA256
(optional `sha256=` prefix) over the exact raw body bytes. Invalid signatures return 401 before any workflow data
is emitted. Deactivation unregisters the webhook. The public URL must be reachable from the automation service.

Events emit `{ kind, deployment, runId?, taskId?, payload }`. Route Rows subscribes to Run Closed, fetches the
selected route's rows, and emits each row with `_tatrman: { runId, deployment, route }`. If Route Rows and Run Closed
are both selected, row expansion takes precedence for Run Closed events.

Run Closed describes a completed source run. For new action work, open and evaluate a **separate** run, perform
or report its outcomes, then close that new run. Close seals the source protocol; new actions on a sealed run
are not an established Server guarantee. The planned H-1 certification sequence awaits P3 lifecycle confirmation.

**Poll** supports Run Closed and Route Rows. Signal, Answer and Window Closed require webhook mode because the
runs list is not an event log. Use n8n's Poll Times to set the interval and optional Since for the first tick.
Polling calls `GET /automation/runs?deployment&since`; it expects `{ runs: [{ runId (or id), status, createdAt }],
truncated? }` in ascending or arbitrary order. The Server must interpret `since` inclusively by creation time.
Open runs remain tracked through `GET /automation/runs/{id}` until closed. Equal timestamps are deduplicated;
truncated lists and excessive state fail explicitly. Polling stores at most 10,000 run IDs; use webhooks for
long-running/high-volume deployments. Server list filtering/timestamps are pending P3 contract confirmation.
As with n8n polling triggers generally, workflow retries can redeliver items; keep downstream actions idempotent.

## Development and checks

```sh
pnpm --filter @collite/n8n-nodes-tatrman typecheck
pnpm --filter @collite/n8n-nodes-tatrman test
pnpm --filter @collite/n8n-nodes-tatrman lint
pnpm --filter @collite/n8n-nodes-tatrman build
pnpm --filter @collite/n8n-nodes-tatrman scan
# From the repository root, with Docker available:
just lint-n8n
just test-n8n-runtime
just build-n8n-image
just test-n8n-image
```

Vitest uses fake service responses and checked-in Server descriptor/manifest fixtures. Recorded H-1 P3 run fixtures
and hartland certification are pending. `scripts/smoke.cjs` is the small loader/context-adapter check.
`scripts/workflow-smoke.cjs` runs two complete workflows in a fresh n8n database inside the pinned container,
with outbound networking disabled. It imports a credential and workflows, publishes them, checks persisted signing
state, sends actual HTTP webhook requests, rejects invalid HMACs with 401, follows two route pages, opens/evaluates
fresh action runs, performs/reports outcomes/closes them, checks successful persisted executions, and deactivates
both workflows through the authenticated local n8n API, asserting remote webhook deletion. One branch uses
Run(routes); the other expands route_rows in the Trigger. Expressions traverse the actual paired item chain.
The fake refuses action writes to the closed source run. All state is temporary and removed after the check.
`test-n8n-image` additionally checks the actual built package manifest, compiled entry points, runtime/SDK
pairing, unprivileged user and loader contents, then runs the same persisted-workflow proof against that
image. Both checks use a fresh `.n8n` tmpfs and mount only the harness scripts, never the node distribution.
This proves n8n runtime mechanics; it does not certify Server P3 or its event lifecycle. See the shared
[P3 handoff](https://github.com/Collite/project/blob/master/kantheon/features/automation-agents/implementation/n8n-nodes/server-p3-handoff.md)
in the planning workspace for unconfirmed API shapes and the sealed-run lifecycle question.

`scan` runs the official `@n8n/scan-community-package` rules on source and shipped JS, including `src/` (the public
scanner's default source glob omits it). Two **package-metadata-only** exceptions are explicit in `scripts/scan.mjs`:
`community-package-json-license-not-default` (D-43 forbids assuming MIT for Platform code) and
`valid-peer-dependencies` (AU S0.1 requires the exact runtime SDK pin). Security rules remain enabled; this is not
a claim of public n8n community verification. The package uses `UNLICENSED` pending the Platform licence decision.

## Internal release

PRs build, check, scan and pack the connector, then build/test the derived image. PRs never publish.
After review and merge, from a clean current master with the tag still free, the agent cuts the exact tag:

```sh
just publish n8n-nodes-tatrman set 0.1.0
```

`n8n-nodes-tatrman/v0.1.0` runs the dedicated `n8n-nodes.yml` workflow. Before any registry write it
checks that the tag equals the package version, derives the image tag from the Dockerfile runtime pin
plus that version, and runs the package/image proofs. The publishing job rebuilds from the checked
package artifact and repeats the actual-image proof before pushing that exact local image as
`ghcr.io/collite/n8n-tatrman:2.44.6-0.1.0`; it then publishes the private scoped npm package to GitHub
Packages. Both writes use the job's `GITHUB_TOKEN` with `packages: write`. No public registry release
is required. The generic `release-image.yml` excludes these tags to avoid a competing publisher.
Local build/test recipes do not cut tags or publish.

After publication, verify the digest and a pulled image, then confirm `collite-registry` has Read on
this new package (via `registry-read` where necessary) before the separate olymp P4 PR changes
Hartland. Existing private-package access does not establish access to this new package. A failed
release can leave one artifact published; inspect both registries before rerunning or cutting another
version. Server P3 endpoint/lifecycle agreement, recorded H-1 fixtures and live AU certification remain
open independently of this image release.
