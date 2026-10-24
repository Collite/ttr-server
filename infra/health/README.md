# health

> **forked-from:** the legacy predecessor platform (`infra/health/`), forked 2026-06-24 (fork Phase 5 Stage 5.2).
> Maintained independently since the fork; do not assume parity with the original.

The **cluster health aggregator** — a technical-wave (no-persona) service in the `infra/` tree.
It runs three checker types against configured targets and serves a roll-up the landing page's
"Cluster Dashboard" reads:

- **native** — HTTP GET `<url><healthEndpoint>`, healthy on 2xx.
- **tcp** — plain TCP connect to `<host>:<port>` (for services that don't speak HTTP, e.g. Postgres).
- **prometheus** — PromQL `up{job="<job>"}`, healthy when `== 1`.

Endpoints: `GET /health/{technology}`, `/health/{technology}/detailed`, `/health/all`
(`?threshold=`), `/health/all/detailed` (health + each technology's **content**, below), `/status/all`
(the content alone), `/healthz` (self-liveness, the pod probe). `GET /` and `GET /status` are the
open-tier status page and its JSON twin (FO-28) — a different thing from `/status/all`.

## Content

A technology may carry a `status` block: where the service reports its content (the model commit it
serves, what it loaded and when) and how to read it. `/health/all/detailed` returns it per technology
as `content` — a small vocabulary the landing renders without knowing the service: `ok`, `at`, `commit`,
`summary` (label/value pairs with a `kind`: `count`, `time`, `date`, `commit`, `list`, `text`), `note`,
`sources`, `top`, `children` — plus a `content` summary saying whether every service that reports a
commit reports the same one. A service configured to name the commit that names none (its boot fixture,
no model loaded) is not ok and is listed as `missing`: it is not on the same commit. The summary's
`lastRefresh` is the newest load among the services that name a commit. Only what a service exposes is
shown; nothing is estimated.

```hocon
translate {
  type = native
  url = "http://translate.ttr-server:7275"
  healthEndpoint = "/ready"
  status {
    url = "http://translate.ttr-server:7275/status"
    at = "last_successful_refresh_timestamp"      # dotted paths into the body; `workers#` is a size
    commit = "model_version"
    commitPattern = "\\|([0-9a-f]{7,40}):"       # the commit inside `stock-v1|<sha>:<hash>`
    ok = "model_version"
    failValues = ["boot-fixture-v0"]
    failNote = "boot fixture served: no model snapshot loaded yet"
    summary = [ { k = entities, path = "er_entity_count", kind = count } ]
  }
}
```

`adapter` picks code for what a path cannot say (`content/Adapters.kt`): `generic-json` (the default),
`veles` (one row per model source), `nlp` (languages and engines from `/version`), `prometheus-targets`,
`k8s-externalsecrets` and `k8s-certificates`; a named adapter keeps the block's configured `summary`,
`note` and `ok` too. A `k8s:/apis/…` URL is read from the Kubernetes API as the pod's ServiceAccount — the
chart's `serviceAccount.name` creates it, `rbac.enabled` gives it read access to the status of
ExternalSecrets and Certificates, nothing else. `probes` add sub-rows the health service checks itself
(`http://…` 2xx, or `tcp://host:port` connects — the name lookup inside the timeout). Reads run in
parallel; each technology's is kept 30 s (5 s after it failed, and only that one is asked again), and a
failed read is that row's `error`, never a failed answer. The health half of `/health/all/detailed` is one
fan-out shared by every caller for 15 s.

`src/test/resources/status/` holds the reference estate's real answers; `StatusFixturesTest` reads
each through the bundled configuration.

## Package root

`org.tatrman.health.*` (swept off `com.platform.health.*`). No proto, no DB — stateless.

## Port

HTTP **7000** (`HEALTH_CHECK_SERVICE_PORT`, kept from the predecessor platform). No gRPC, no MCP wrapper.

## Roster

The bundled `application.conf` carries the **reference roster** — the hartland estate's: every Kantheon
agent and front door, the ttr-server spine and workers, the understanding services, the DocWH services,
and the platform tiers (CNPG PostgreSQL, MS SQL, Redis, SeaweedFS, Keycloak, OPA, the observability
stack, Envoy Gateway, Argo CD, External Secrets, cert-manager, Sealed Secrets), addressed
`<service>.<namespace>`. The landing's `services.json` names the same keys.

### Per-cluster roster

Each estate runs a different set of services, so a deployment supplies its own roster without an image:
a `roster` block, which **replaces** the bundled `technologies` (a merge would keep every target the estate
does not run, as a red tile). Through the chart it is the config fragment:

```yaml
configFragment:
  content: |
    health-check-service.roster {
      technologies {
        veles { type = native, url = "http://veles.ttr-server:7260", healthEndpoint = "/ready" }
        # …
      }
    }
```

The library mounts it, hands it to the JVM (`-Dconfig.file`, with the bundled conf included) and rolls
the pod when it changes. A `roster` without a `technologies` block stops the service rather than
serving the bundled estate's tiles as if they were this one's.

## Run

```
just deploy-kt infra/health        # Jib image + Helm deploy (Jib build CI-gated on Rancher)
./gradlew :infra:health:test       # mocked unit/component suite
```
