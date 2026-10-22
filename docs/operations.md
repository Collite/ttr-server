<!-- SPDX-License-Identifier: Apache-2.0 -->
# Operating Tatrman Server (open tier)

The open Server is operated the way cloud-native infrastructure is operated: through
**configuration and a CLI**, not through a built-in admin application. This is a
deliberate stance (FO-28), not a missing feature — there is no privileged web console
to secure, and no write path an operator can reach that the governed architecture
doesn't already mediate.

> **Operate tier.** On a managed Platform estate the same runtime gains the commercial
> **Studio Operations** perspective — an estate / round / reservation / runs-quota
> console. That is an *operate-tier* add-on; the open tier's operator surface is the
> read-only health page below.

## The health & status surface

The `health` service (image `health:dev`, port `7000`) is the operator's front door.
Every route is **read-only** — the open surface exposes no mutating verb.

| Route | Purpose |
| --- | --- |
| `GET /` | Minimal human status page: Server version, model fingerprint, per-service health. |
| `GET /status` | The same as JSON — `{ serverVersion, modelFingerprint, summary, services[] }`. |
| `GET /health/all` | Liveness rollup across every configured target (`200` healthy / `503` otherwise). |
| `GET /health/{service}` | One target's liveness (e.g. `/health/veles`). |
| `GET /healthz` | The aggregator's own self-probe (use this as the pod liveness probe, not `/health/all`). |

- **Server version** is the single release-train version (`tatrman-server.version`) — the
  whole open Server is built and released together, so there is one version, not a
  per-service matrix.
- **Model fingerprint** is Veles's `model_version` (the loaded model's identity), read
  from `veles /status`. If Veles is unreachable it renders `unavailable` — the page
  never fails because a downstream is down.

Each individual service also serves its own `GET /health`, `GET /ready`, and
`GET /status` (Veles additionally exposes `GET /metrics` in Prometheus format).

## Configuration reference

Every operational knob is an environment variable / Helm value — nothing is set through
a running UI.

**Health surface**

| Variable | Default | Meaning |
| --- | --- | --- |
| `HEALTH_CHECK_SERVICE_PORT` | `7000` | The health service HTTP port. |
| `VELES_STATUS_URL` | `http://veles:7260/status` | Where the status page reads the model fingerprint. |
| `HEALTH_<SERVICE>_URL` | per `application.conf` | Override a target's base URL (e.g. `HEALTH_VELES_URL`). |
| `HEALTH_<SERVICE>_ENDPOINT` | `/health` | Override a target's health path. |
| `HEALTH_CACHE_TTL_SECONDS` | `30` | Result cache TTL for the rollup. |

The full target inventory (every service + infra dependency, each with a `HEALTH_*`
override) lives in `infra/health/src/main/resources/application.conf`; a deploying repo
(olymp) retunes DNS/ports/paths via a ConfigMap without rebuilding the image.

**Per-service wiring** — each service reads its peers from its own `application.conf`
with `${?ENV}` overrides (e.g. `TRANSLATE_SERVER`, `VALIDATE_SERVER`, `VELES_GRPC_HOST`).
See each service's `src/main/resources/application.conf`.

## Running it

The whole Server runs as a Helm release (there is no all-in-one compose file — the
estate is Kubernetes-native):

```bash
# a light query spine + identity/health, for a /health smoke:
helm upgrade --install ttr helm/tatrman-server -f helm/tatrman-server/deploy/values-bp-dsk-smoke.yaml
# then:  curl -fsS http://<health-host>:7000/health/all
```

Golden manifests live under `helm/tatrman-server/deploy/` (`values-bp-dsk-smoke.yaml`,
`values-kind-ci.yaml`) and `helm/tatrman-server/golden/`.

## The "no admin app" stance (FO-28)

The open tier ships **no operator web application** — and this is not a gap:

- **Provisioning & config** is declarative: Helm values + environment variables, under
  version control, applied by your CD (olymp/ArgoCD in the reference estate).
- **Day-2 tasks** (model refresh, cache clear) are **governed cluster-internal verbs**,
  not an operator console. Routes such as `POST /refresh` (query/translate) and
  `POST /admin/reload-stop-words` (Veles, shared-secret gated) are reached *inside the
  cluster* by the platform's own reconciliation, not exposed as an operator admin
  surface. They are intentionally absent from the health page above.
- **There is no write path to your data** on the open operator surface. Data writes,
  where a tier has them at all, go through the governed door — never a side channel.

If you want a graphical operations console (estate overview, planning rounds, reservation
admin, runs & quota), that is the commercial **Studio Operations** perspective on a
Platform estate.

## BI / reporting

Serving data to BI tools is covered separately in **[Superset & BI](superset.md)** — the
open tier is self-serve (semantic layer + bring-your-BI + reference Superset pairing).
Clients connect through the query service's **Flight SQL edge**; the client side (JDBC URL,
DBeaver, ADBC, worlds, errors, receipts) is **[JDBC, ADBC and other Flight SQL clients](jdbc.md)**.

### The Flight SQL edge (`query`, port 7308)

Off by default. On, every call is authenticated against the realm; there is no anonymous mode,
and an edge switched on without an issuer does not start.

| Variable (Helm `query.flight.*`) | Default | Meaning |
| --- | --- | --- |
| `QUERY_FLIGHT_ENABLED` (`enabled`) | `false` | Start the edge. |
| `QUERY_FLIGHT_PORT` (`port`) | `7308` | Its gRPC port; expose it with the chart's `ports.extra` and route it with a `GRPCRoute` (TLS at the gateway, h2c to the pod; a route timeout ≥ 300 s for long reads). The door's own 7306 stays unrouted. |
| `QUERY_FLIGHT_BIND` (`bind`) | `127.0.0.1` (chart: `0.0.0.0`) | Listen address. Loopback unless a gateway fronts it. |
| `QUERY_FLIGHT_AUTH_ISSUER` (`auth.issuer`) | — (required) | The realm's issuer URL; the JWKS and token endpoints derive from it. |
| `QUERY_FLIGHT_AUTH_JWKS_URI` / `QUERY_FLIGHT_KC_TOKEN_URL` | derived | In-cluster URLs when the issuer's host is not reachable from the pod. |
| `QUERY_FLIGHT_AUTH_AUDIENCE` (`auth.audience`) | empty | Required `aud`; empty accepts any (logged as a warning). |
| `QUERY_FLIGHT_KC_CLIENT` (`auth.passwordClient`) | `jdbc` | The Keycloak client for password login (direct-access grants on); empty = bearer tokens only. |

`/status` on the query HTTP port has a `flight` block (running, port, bind, statements, receipts).
An enabled edge that cannot listen (the port taken, a bind address the pod does not have) stops
the service from starting, and `/ready` is not ready while an enabled edge is not running.

**The Flight edge is single-replica:** a statement's ticket lives in the pod that started it, so
a second replica behind the route would answer another pod's read with `NOT_FOUND`. The chart
refuses `flight.enabled` with `replicaCount` above 1.

### Worlds — `QUERY_WORLDS` (Helm `query.worlds`)

`world=connection_id` pairs, comma-separated: `hartland_us=pg-hartland-us,hartland_cz=pg-hartland-cz`.
A request naming a world runs on that connection; a world not in the table is refused
(`unknown_world`). Empty = one world, on the deployment's default connection. A malformed table
fails the start. `/status` lists the worlds.

A world selects the data; the caller's roles and row rules decide what of it they read. Worlds grant
nothing: any signed-in caller may pick any world in the table, and the same row rules apply in each
(a row rule keyed on a value both worlds hold applies in both).

### Row caps — validate

| Variable | Default | Meaning |
| --- | --- | --- |
| `VALIDATE_DEFAULT_TOP_N` | `30` | Rows an answer gets when the caller states no limit. |
| `VALIDATE_MAX_TOP_N` | = default | The most a caller may ask for. |
| `VALIDATE_MAX_TOP_N_BY_ROLE` | empty | `role:rows,…` — a higher ceiling for callers holding the role (`kantheon-bi-reader:10000`). A role only raises the ceiling; the default stays everyone's. The role name may contain `:` (the rows follow the last one). A malformed entry fails the start. |

Set them through validate's `extraEnv`. A capped answer carries `top_n_applied` (a warning, and a
typed notice with `requested` / `applied`) on its last batch. validate's `/status` shows all three.

**Through the Flight edge** a statement asks for as many rows as its caller's ceiling allows: it gets
`VALIDATE_MAX_TOP_N`, or its role's higher value, and its own `LIMIT` lowers that. The default
applies to door callers that state no row window (MCP, agents), not to edge statements. A caller with
no BI role gets `VALIDATE_MAX_TOP_N`, which is the default unless you raised it. An answer the ceiling
cut carries `top_n_applied` with `requested: 0` and `applied` = the ceiling. BI clients that read an
answer whole (`ttr-sqlalchemy`, so Superset) hold up to the ceiling in memory: size the role's value
for that.

### Receipts — `bi_receipts`

One row per statement through the edge, in schema `query` of the database named by
`QUERY_RECEIPTS_DB_HOST` / `_PORT` / `_NAME` / `_USER` / `_PASSWORD` (Helm `query.flight.receipts.db`,
the password by Secret ref). No host = no receipts. The Secret is optional: if it is missing (or the password is
empty) the service still starts and logs `Flight edge: receipts off (no QUERY_RECEIPTS_DB_PASSWORD …)` at WARN.
Statements then run without receipts until the Secret exists and the pod restarts. The password is read once at
start-up, so rotating it also needs a rollout restart (`kubectl rollout restart deploy/query`). Until then, each
receipt the old password cannot write is logged at WARN and dropped. The service creates the table on first use
(Flyway, history in the same schema; a table from before `notices` gains the column, its older rows
with none); its user must own the database or schema `query`. Writing is
best effort: a full queue or a database outage drops receipts and logs it, and never fails a statement.

- **Size.** A row is the statement, the SQL the door ran, the row rules applied and the statement's
  notices (`notices`, jsonb: the warnings its closing metadata carried) — typically 1–4 KB. 10 000 statements a day is roughly 10–40 MB a day.
- **Retention.** Nothing is deleted automatically. Prune by time (the `ts` index serves it), e.g. a
  nightly `DELETE FROM query.bi_receipts WHERE ts < now() - interval '90 days'` from a CronJob or
  `pg_cron`, at the retention your audit policy sets.
- **Reading.** `GET /receipts?correlation_id=…` or `?user_id=…&since=…` on the query HTTP port, for
  a bearer with `query-platform-admin`.

### gRPC reflection — `GRPC_REFLECTION_ENABLED`

Every service's gRPC server has reflection **off** by default. Set `GRPC_REFLECTION_ENABLED=true`
(the service's `extraEnv`) to let `grpcurl` list and call services without the `.proto` files;
leave it off where the port is reachable from outside the cluster. Without it, pass the protos:
`grpcurl -import-path shared/proto/src/main/proto -proto org/tatrman/query/v1/query.proto …`.
