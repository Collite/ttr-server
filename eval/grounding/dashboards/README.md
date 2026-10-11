# Grounding dashboards + alerts — GitOps drafts (A14.3)

**These are drafts staged here for review — they belong in the separate GitOps
repo, not in this one** (observability manifests live there per the repo's
"no deployment manifests here" rule). Move on rollout:

- `grounding-overview.json` → a Grafana dashboard (import via provisioning or the UI).
  Target home in the GitOps repo: `apps/observability/overlays/<estate>/dashboards/`
  (wherever the existing service dashboards are provisioned).
- `grounding-alerts.yaml` → a Prometheus/Alerting rule group. Target home:
  the Prometheus rules ConfigMap / `PrometheusRule` set already deployed from the GitOps repo.

## Metrics consumed (already emitted; no service change needed)

| Metric | Type | Labels | Source |
|--------|------|--------|--------|
| `chrono_ground_total` / `geo_ground_total` / `money_ground_total` | counter | `outcome`, `source` | A8.7 / A9 / A10.6 service metrics |
| `chrono_ground_latency_ms` / `geo_ground_latency_ms` / `money_ground_latency_ms` | histogram | `outcome`, `source` | ⟶ `_bucket`/`_sum`/`_count` in Prometheus |
| `golem_grounding_total` | counter | `tool`, `status`, `source` | Golem GroundEntities node (`golem.grounding.total`) |

Traces: the Golem `golem.ground_entities` span and its `golem.ground.<tool>` children
are in Tempo — link the dashboard's "GroundEntities span" panel to a Tempo query
`{ name = "golem.ground_entities" }` (exemplar/trace link, datasource-dependent).

> OTel→Prometheus naming: dots become underscores and the counter keeps its `_total`
> suffix, so `golem.grounding.total` is queried as `golem_grounding_total`. Confirm
> against the deployed Prometheus at rollout — some collectors add a namespace prefix.

## Alert

`grounding-alerts.yaml` fires **GroundingLlmFallbackHigh** when the LLM-fallback share
of OK groundings exceeds 10% over 1 day (the A14.5 gate), per service and overall.
