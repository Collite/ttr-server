# A14.5 — Grounding acceptance run (step-by-step)

Runs the grounding eval against **the pilot estate** and records the gate results. This is the
runtime DoD for the whole grounding feature. Work top-to-bottom; each phase has a
**verify** step — don't proceed past a red check.

> **Reality check before you start (2026-07-07).** The eval needs five deployables
> live on the pilot estate: `services/chrono`, `services/geo`, `services/money`,
> `tools/grounding-mcp`, and a grounding-enabled `agents/golem`. In the pilot's GitOps repo
> **none of the first four have an Argo app yet, and golem has no grounding config /
> no pilot overlay.** So Phase 1 (stand them up in the GitOps repo) is real work and
> gates everything else. If you've already done Phase 1, jump to Phase 2.

Two repos are involved:
- **the source repo** — source + `just tag` to build/push images + `just eval-grounding` to run.
- **the pilot's GitOps repo** — Argo apps + the pilot's image tags + per-instance config.

---

## Phase 0 — Prerequisites

```bash
kubectl config current-context          # must be the pilot cluster
kubectl get pods -A | grep -Ei 'golem|query-mcp'   # confirm the base stack is up
gh auth status                          # for `just tag` (pushes tags → release-service.yml)
```

Grounding is a **soft dependency**: if this run shows problems, `GOLEM_GROUNDING_ENABLED=false`
disables the node without redeploying anything. Keep that in your back pocket.

---

## Phase 1 — Stand up the deployables in the GitOps repo (one-time)

Use the existing apps as templates (they already encode the pilot's conventions):
- services → copy `apps/services/fuzzy-matcher` (Kotlin service).
- grounding-mcp → copy `apps/tools/query-mcp` (Kotlin MCP tool; note its `overlays/<pilot>/`).

For **each** of `chrono`, `geo`, `money`, `grounding-mcp`, create
`apps/{services|tools}/<name>/{base/values.yaml, overlays/<pilot>/{kustomization.yaml,values.yaml}}`
and register it in the pilot's app-of-apps. Set:
- `image.repository: tatrman.azurecr.io/<name>`, `image.tag:` (filled in Phase 3).
- the container port each service actually listens on (chrono gRPC is 7220 per A8.2;
  grounding-mcp HTTP is **7153**). Copy the port block from the template and adjust.
- for grounding-mcp, the three gRPC targets — mind the **`*_SERVER_GRPC_PORT`** name
  convention (a legacy `*_GRPC_PORT` silently falls back to a wrong default; this bit
  validator once). Cross-check each service's `application.conf`.

Then add golem's grounding config. The config draft is in
[`rollout/README.md`](rollout/README.md) §2 — copy the `MCP_GROUNDING_URL` +
`GOLEM_GROUNDING_*` env into golem's pilot values (`apps/agents/golem*/overlays/…`).
Set `GOLEM_GROUNDING_ENABLED: "true"`.

**OTel gotcha (affects the acceptance checklist directly):** newer services often ship
with export disabled (missing per-service `OTEL_ENABLED_*` / golem's baked
`OTEL_ENABLED:false`). If it's off, the Grafana panels and Tempo traces in Phase 7 stay
empty even though grounding works. Set the enable flag for chrono/geo/money/grounding-mcp
and golem while you're in the values files.

**Verify:** `cd <gitops-repo> && kustomize build apps/tools/grounding-mcp/overlays/<pilot> | head`
renders without error for each new app.

---

## Phase 2 — Build & push images (the source repo)

From a clean, pushed branch (or main). Tag **in dependency order**, one at a time,
waiting for each ACR build to finish before the next (`just tag` bumps the patch +
pushes `<dir>/v<x>` which triggers `release-service.yml`):

```bash
just tag services/chrono
just tag services/geo
just tag services/money
just tag tools/grounding-mcp
just tag agents/golem        # only after the grounding config from Phase 1 is merged
```

**Verify:** each tag's Action is green:
```bash
gh run list --workflow release-service.yml -L 5
```
Note the five version numbers `just tag` printed — you need them in Phase 3.

---

## Phase 3 — Point the pilot estate at the new images + sync

In the GitOps repo, set each app's `overlays/<pilot>/values.yaml` → `image.tag:` to the
version from Phase 2 (same order), commit, push. Argo picks it up (or `argocd app sync
<app>` per app). Roll **services → grounding-mcp → golem** so each dependency is live
before its consumer.

**Verify** each pod is running the tag you just set:
```bash
for a in chrono geo money grounding-mcp golem; do
  echo "== $a =="; kubectl get pods -l app=$a -o jsonpath='{.items[*].spec.containers[*].image}{"\n"}'
done
```
(Adjust the `-l app=<name>` selector to whatever label your templates emit — confirm
with `kubectl get pods -A | grep <name>` and `kubectl get pod <pod> -o jsonpath='{.metadata.labels}'`.
The `just eval-grounding` recipe assumes `app=grounding-mcp` and `app=golem`; if your
labels differ, either relabel or port-forward by pod name in Phase 5.)

Then check readiness:
```bash
kubectl get pods -l app=grounding-mcp   # Ready 1/1
kubectl get pods -l app=golem           # Ready 1/1; grounding-mcp is a soft dep, so
                                        # golem is Ready even if grounding-mcp is down
```

---

## Phase 4 — Connectivity smoke test

Confirm golem actually reached grounding-mcp at boot (not silently degraded):
```bash
kubectl logs -l app=golem --tail=200 | grep -i grounding
#  want: "grounding-mcp session pool ready"
#  NOT:  "Grounding MCP unavailable, GroundEntities will degrade to 'no grounding'"
```
If it degraded, fix `MCP_GROUNDING_URL` / the grounding-mcp service DNS before running —
otherwise every case comes back ungrounded and the eval is meaningless.

---

## Phase 5 — Run the eval

First the **cluster-free** self-check (must be green regardless of the pilot estate):
```bash
just eval-grounding-test        # corpus validity + pure report core; 18 tests
```

Then the real run. The recipe port-forwards grounding-mcp (`7153`) and golem
(`7999→7903`) and runs both tiers:
```bash
just eval-grounding
```
Default `--models` scores every fixture. **The pilot estate serves one model (an accounting model)**, so
most `money-*`/`geo-*` synthetic fixtures won't exist there — restrict scoring so those
are `skipped:model-unavailable` rather than false failures. Start narrow and widen as you
add fixtures:
```bash
just eval-grounding "accounting-period,calendar"
```
Reports land in `eval/grounding/reports/{metrics.json,report.md}`.

If the port-forward labels don't match (Phase 3), run the underlying script directly
against your own forwards:
```bash
kubectl port-forward <grounding-mcp-pod> 7153:7153 &
kubectl port-forward <golem-pod> 7999:7903 &
cd eval/grounding && uv run python run_eval.py \
  --grounding-url http://localhost:7153/mcp --golem-url http://localhost:7999 \
  --models accounting-period,calendar --output-md reports/report.md
```

---

## Phase 6 — Read the gates

`report.md` (also printed) gives you:
- **bulk pass-rate ≥ 80%** (over *scored* cases; skipped don't count).
- **LLM-fallback rate < 10%** — the primary A14 target (`source_rate.llm_fallback_rate`).
- **E2E pass-rate** — the hero questions live here.
- outcome distribution + latency p50/p95/p99.

The runner exits non-zero if either gate fails. If **LLM-fallback is high**, the rules
path is regressing → look at which spans fell to LLM (grep the metrics.json for
`"source": "LLM"`), likely a recognizer/model gap. If a **hero E2E case fails**, check
whether the grounded condition survived: the `grounding_condition_missing:` warning +
the `grounding_notes` on the envelope tell you if grounding produced the recipe but the
cascade dropped it (A13 structural check) vs. grounding never fired (Phase 4 / routing).

---

## Phase 7 — Hero-question traces

For each of the three hero questions, capture a trace proving the condition came from a
recipe, not LLM composition. Use the per-turn log tool:
```bash
just trace-recent                 # find the latest turn's trace_id across all services
just trace-logs <trace_id>        # pull the whole golem→grounding-mcp→chrono/geo/money chain
```
Confirm the chain shows `golem.ground_entities` → `golem.ground.<tool>` → the service's
`ground` span with `source=RULES`. Grab the Grafana "Grounding" dashboard screenshot
(outcome/source/latency populated) — if empty, revisit the OTel flag from Phase 1.

---

## Phase 8 — Record + decide

Paste into `tasks-stage-grounding-14-eval-rollout.md` under A14.5:
- the `report.md` summary block (pass rates, LLM-fallback %, latency),
- one Tempo trace link per hero question,
- the A2 kind-population metric reading (Resolver still emitting universal kinds).

Then flip A14.5 → `[x]`, tick the checklist in [`README.md`](README.md), and note the
retro on the <10% target (A14.7). If the run is red and you can't fix quickly, set
`GOLEM_GROUNDING_ENABLED=false` on the pilot estate (golem degrades cleanly) and iterate — no
service redeploy needed to disable.
