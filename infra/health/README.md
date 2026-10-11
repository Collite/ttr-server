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
(`?threshold=`), `/health/all/detailed`.

## Package root

`org.tatrman.health.*` (swept off `com.platform.health.*`). No proto, no DB — stateless.

## Port

HTTP **7000** (`HEALTH_CHECK_SERVICE_PORT`, kept from the predecessor platform). No gRPC, no MCP wrapper.

## Check targets (Stage 5.2 T3 re-point)

`application.conf` was re-pointed off the legacy predecessor-platform / erp-sql estate onto the **kantheon
constellation** (agents, platform services, workers, MCP wrappers, technical-wave) addressed by
bare in-namespace service names, plus the shared **cluster estate** (data / monitoring / auth /
gateway / middleware) namespace-qualified. Every field carries a `${?HEALTH_*}` env override so
the deploying repo (olymp) retunes DNS/ports/paths via a ConfigMap without rebuilding the image.

## Run

```
just deploy-kt infra/health        # Jib image + Helm deploy (Jib build CI-gated on Rancher)
./gradlew :infra:health:test       # mocked unit/component suite
```
