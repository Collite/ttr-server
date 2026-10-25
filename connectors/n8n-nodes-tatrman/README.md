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

The image is Linux/amd64, based on the approved `ghcr.io/collite/n8n` mirror. The Dockerfile is the
single source of the n8n version; release metadata and test recipes read it. The mirror preserves the
upstream multi-platform digest. The dedicated manual mirror job in `release-image.yml` uses Kantheon's
workflow token to create the package and establish repository Actions access; PR and release jobs log
in to GHCR before pulling it. The connector image remains `ghcr.io/collite/n8n-tatrman:<runtime>-<package>`.

The build context is the unpacked package tarball. Package metadata and compiled files live in
`/opt/tatrman-nodes/node_modules/@collite/n8n-nodes-tatrman`, outside the persistent `.n8n` volume.
An unprivileged entrypoint creates only a symlink under n8n's scanned
`.n8n/nodes/node_modules/@collite` directory, then delegates to the upstream entrypoint. It refuses
to overwrite an existing physical npm installation. n8n's package loader therefore uses the same
`@collite/n8n-nodes-tatrman.tatrmanRun` and `@collite/n8n-nodes-tatrman.tatrmanTrigger` IDs as npm.
No npm credential is baked into a layer. `N8N_CUSTOM_EXTENSIONS` must not point at this package.
The olymp image switch and live Server/hartland certification remain pending.

## Credentials

Create **Tatrman Automation API** with:

- **Base URL**: the automation service origin/prefix, without `/automation` or a trailing slash.
- **Token**: a deployment/service bearer for automation, or the answerer's personal bearer for task answers.
- **Iris BFF URL**: reserved for P1's Golem Question.

The credential test calls the authenticated descriptor `GET /automation/`. n8n applies bearer authentication to every service request.
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
and context. Each HTTP attempt allows 90 seconds, exceeding the Server's default 60-second synchronous budget.
Only Evaluate polls after a 202, with a separate 30-second polling budget and a 15-second Retry-After
floor. Poll errors retain the run ID and Location; a timed-out evaluation may still be running, and
retrying Evaluate with that run ID joins the existing evaluation.

Always set an Idempotency Key on Open. An Open 202 is returned immediately as a replay handle;
continue to Evaluate. If the original session is still registering, Evaluate can briefly return
RUN_ABANDONED: retry Evaluate with the same run ID. A lost Open response without an idempotency key
may have created a run; repeating Open can create another. Server Locations beginning `/automation/`
are rebased beneath a Base URL path prefix; other origins and paths remain refused.

Omitted Keys means unrestricted candidates. An explicit `[]` means perform nothing: the connector
emits `{ performed: [], skipped: [] }` without calling the Server. Empty Rows on Answer is preserved.
Read Routes before Close; the connector omits `limit`, accepting the Server's configured page size.

## Tatrman Trigger

Choose Deployment and Events: Signal, Answer, Window Closed, or Run Closed. Route Rows is removed
from P0 because the current Server releases route sessions on close; restoration is tracked in
[kantheon#257](https://github.com/Collite/kantheon/issues/257). Use Run → Routes before Close instead.
A run-closed notification can open a fresh follow-up run for action work; never mutate the sealed source.

Webhook activation registers `{ deployment, url, secret, kinds }` via `POST /automation/webhooks`.
GET `/automation/webhooks` must return `{ webhooks: [{id,...}] }`; DELETE `/automation/webhooks/{id}`
unregisters it on deactivation (already-gone 404 is accepted). GET/DELETE and exact HMAC/event shapes
remain proposed Server P3 contracts. The receiver checks hex HMAC-SHA256 (`X-Tatrman-Signature`, optional
`sha256=` prefix) over raw JSON bytes; bad signatures return 401 and malformed envelopes return 400.
Filtered events produce no execution. Signature freshness/timestamp binding remains a Server handoff
follow-up; current signatures alone do not prevent replay of a captured event.

Poll supports Run Closed only. Since defaults to the first poll. The proposed list endpoint is
`GET /automation/runs?deployment&since` → `{ runs: [{ runId (or id), status, createdAt }], truncated? }`.
Timestamps are compared numerically; the cursor is canonical ISO. A five-minute creation-time overlap
catches bounded late commits, with seen IDs pruned to that window. Pending runs are tracked separately;
403/404 and terminal runs are dropped and logged. Transport/5xx failures do not commit partial state;
truncated lists fail explicitly. Poll state survives re-publication and resets only when deployment or
configured Since changes. Server P3 still needs to confirm durable terminal reads, inclusive time filtering,
creation-versus-commit ordering and the maximum late-commit delay. These client tests do not certify that API.

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
just test-n8n-install-parity
```

Vitest uses fake service responses constrained by the checked Server source: route limits/defaults,
closed-session refusal, request budgets and idempotent replay handles. Recorded H-1 P3 fixtures and
hartland certification remain pending. The old adapter-only smoke script was removed.

`test-n8n-image` checks the actual package manifest, compiled entry points, runtime/SDK pairing,
unprivileged user and scoped package loader with a fresh `.n8n` mount. Its persisted workflow proof
exercises authenticated credential testing (including a bad token), scoped workflow IDs, a 450-row
route in server-default 200/200/50 pages, HMAC delivery, filtered-event suppression, expressions,
perform/outcome/close, closed-route refusal, persisted success and webhook deactivation. A separate
real poll-mode workflow is edited, saved and re-published while active: deduplication and pending-run
state must survive. Only the Automation service is fake; outbound networking is disabled and only
harness scripts are mounted. `test-n8n-install-parity` installs the tarball with npm in a fresh runtime
and runs the same scoped workflow JSON. This proves installation/runtime mechanics, not Server P3.

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
Packages. Both writes use the job's `GITHUB_TOKEN` with `packages: write`; base pulls authenticate before builds. No public registry release
is required. The generic `release-image.yml` excludes these tags to avoid a competing publisher.
Local build/test recipes do not cut tags or publish.

After publication, verify the digest and a pulled image, then confirm `collite-registry` has Read on
this new package (via `registry-read` where necessary) before the separate olymp P4 PR changes
Hartland. Existing private-package access does not establish access to this new package. A failed
release can leave one artifact published; inspect both registries before rerunning or cutting another
version. Server P3 endpoint/lifecycle agreement, recorded H-1 fixtures and live AU certification remain
open independently of this image release.
