# Status fixtures

The bodies the health service's content adapters read, as the hartland estate's services answered them
(2026-10-23, `kubectl get --raw …/proxy/status`). `StatusFixturesTest` reads each through the
`status` block the bundled `application.conf` configures for it, so a change to a service's status
shape, or to the configuration, shows up here before it shows up as an empty row on the landing.

- `<service>.json` — `GET /status` (`nlp-version.json`: nlp's `GET /version`; `identity-ready.json`:
  identity's `GET /ready`).
- `prometheus-targets.json` — `GET /api/v1/targets?state=active`, cut to each target's job and health.
- `k8s-externalsecrets.json`, `k8s-certificates.json` — the Kubernetes list responses, cut to the
  names and the status the adapters read (no spec, no data); the ExternalSecrets also cut to five, with
  neutral names.

Refresh one by fetching it again from a running estate; keep the cuts.
