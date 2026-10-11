# Grounding rollout — GitOps drafts + release checklist (A14.4)

**Config values below belong in the pilot's GitOps repo, not here.** This directory stages the
per-instance config the grounding feature needs so review can see it in one place;
apply it by editing the real values files in that repo.

## 1. Ship the images (release-service.yml auto-detects build type)

Grounding spans five deployables. Tag + push in dependency order (services →
grounding-mcp → golem), one at a time, using `just tag <dir>` (auto-bumps patch,
pushes the `<dir>/v<semver>` tag, triggers the ACR build + Argo sync):

```
just tag services/chrono
just tag services/geo
just tag services/money
just tag tools/grounding-mcp
just tag agents/golem
```

Wait for each image to build + the pod to roll before the next (the newer service
must be live before grounding-mcp points at it; grounding-mcp before golem).

## 2. Per-instance config (GitOps values)

### grounding-mcp — `apps/grounding-mcp/overlays/<pilot>/values.yaml`
Points at the three grounding services (in-cluster gRPC). Env names follow the
`*_SERVER_GRPC_PORT` convention (see the env-var-name-mismatch note); confirm each
service's `application.conf` reads the same key.

```yaml
env:
  CHRONO_SERVER_GRPC_HOST: chrono
  CHRONO_SERVER_GRPC_PORT: "9090"
  GEO_SERVER_GRPC_HOST: geo
  GEO_SERVER_GRPC_PORT: "9090"
  MONEY_SERVER_GRPC_HOST: money
  MONEY_SERVER_GRPC_PORT: "9090"
```

### golem — `apps/golem/overlays/<pilot>/values.yaml` (or config.production.json)
The GroundEntities node's soft dependency + per-instance GroundingContext
(contracts §8). `reference_datetime` is NEVER config — it is the turn timestamp.

```yaml
env:
  MCP_GROUNDING_URL: http://grounding-mcp:7153/mcp   # in-cluster URL on the pilot estate
  GOLEM_GROUNDING_ENABLED: "true"            # global kill switch for the node
  GOLEM_GROUNDING_TIMEZONE: Europe/Prague
  GOLEM_GROUNDING_LOCALE: cs-CZ
  GOLEM_GROUNDING_DEFAULT_CURRENCY: CZK
  GOLEM_GROUNDING_FX_POLICY: TRANSACTION_DATE
  GOLEM_GROUNDING_TOLERANCE_PCT: "0"
  GOLEM_GROUNDING_MISS_PENALTY: "0.5"        # free-SQL structural-miss demotion (1.0 disables)
  # GOLEM_GROUNDING_HERE_PLACE_REF: ""       # set only if the instance has a "here" anchor
```

> If OTel export is disabled for golem (the `OTEL_ENABLED` baked-config gotcha),
> the GroundEntities span + `golem_grounding_total` won't reach Tempo/Prometheus
> and A14.3's Golem panels stay empty — fix the per-service enable flag in
> the GitOps repo as part of this rollout.

## 3. Staged enablement

`GOLEM_GROUNDING_ENABLED` is the switch. Roll out to the pilot's accounting golem
instance first with it **on**; if the eval gate (A14.5) regresses, flip it to
`false` (golem degrades to "no grounding", the cascade still runs) and iterate —
no redeploy of the services needed to disable.

## 4. Post-deploy gate → A14.5

Once all five are live on the pilot estate, run `just eval-grounding` and paste the report
into the A14.5 checklist: bulk pass-rate ≥ 80%, **LLM-fallback rate < 10%**, hero
questions green.
