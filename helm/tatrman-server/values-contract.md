<!-- SPDX-License-Identifier: Apache-2.0 -->
# Tatrman Server — umbrella chart values contract

> **The contract.** These are the supported values of `helm/tatrman-server` — the one
> chart that installs the whole product (SV-P4·S2, RO-12). A key documented here is a
> stable surface: within a product major version it changes **additively only** (new keys,
> new defaults that don't break an existing install). **Keys not documented here are
> non-contract** — a per-service chart may accept more (nested under its key), but only
> what appears below is promised. This file is what the Operate docs track and the
> quickstart's `helm install` step consume verbatim.
>
> Render/verify locally: `just charts` (lint + golden). Regenerate golden after an
> intended change: `just charts-golden`.

---

## 1. Shape

One umbrella, the full service roster as `file://` subcharts, each a thin wrapper over
the shared `tatrman-service` library. Every service is toggled by `<name>.enabled`; per-service
tuning nests under the same key and is passed to that service's own chart
(`services/<x>/k8s/values.yaml` etc.). Vendored subchart deps (`charts/`, `Chart.lock`) are
gitignored build artifacts rebuilt by `scripts/helm-deps.sh` (offline).

- `version` (chart) = the packaging. `appVersion` = **the product version** (RO-24).
- Namespace: the pilot uses `ttr-server`; the chart is namespace-agnostic.

### 1.1 Value key ↔ image/dir name

Value keys are **chart names**, which differ from the image (`ghcr.io/collite/<image>`) and
directory names for several services. Use the key in the left column in your values.

| Value key | Image (`ghcr.io/collite/…`) | Source dir | Role |
|---|---|---|---|
| `veles` | `veles` | `services/veles` | model graph / metadata |
| `query` | `query` | `services/query` | query spine |
| `translate` | `translate` | `services/translate` | query spine |
| `validate` | `validate` | `services/validate` | query spine |
| `dispatch` | `dispatch` | `services/dispatch` | query spine |
| `llm-gateway` | `llm-gateway` | `services/llm-gateway` | LLM gateway |
| `mssql` | `worker-mssql` | `workers/worker-mssql` | worker |
| `postgres` | `worker-postgres` | `workers/worker-postgres` | worker |
| `polars` | `worker-polars` | `workers/worker-polars` | worker |
| `whois` | `identity` | `infra/identity` | identity resolver |
| `health` | `health` | `infra/health` | health aggregator |
| `query-mcp` | `query-mcp` | `tools/query-mcp` | MCP door |
| `veles-mcp` | `meta-mcp` | `tools/meta-mcp` | MCP door |
| `fuzzy` | `lex-matcher` | `services/lex-matcher` | vocabulary matcher (understanding layer) |
| `fuzzy-mcp` | `lex-matcher-mcp` | `tools/lex-matcher-mcp` | MCP door |
| `nlp` | `nlp` | `services/nlp` | NLP front + backends (understanding layer) |
| `nlp-mcp` | `nlp-mcp` | `tools/nlp-mcp` | MCP door |
| `chrono` | `chrono` | `services/chrono` | grounding — time |
| `geo` | `geo` | `services/geo` | grounding — location |
| `money` | `money` | `services/money` | grounding — money |
| `grounding-mcp` | `grounding-mcp` | `services/grounding-mcp` | grounding MCP door |
| `resolver` | `resolver` | `services/resolver` | resolve core + `resolve.bind:v1` door |
| `automation` | `automation` | `services/automation` | the automation service — run API, ledger, protocol (AU; opt-in) |
| `backstage` | `backstage` (upstream) | `infra/backstage` | developer portal (RO-22, opt-in) |

> **Designer viewer (RO-9):** ships in-chart but is **not yet a subchart** — its Veles
> adapter is not built (S0·T4). Tracked in S2·T5. It is outside the acceptance bar.

---

## 2. Global keys

| Key | Type | Default | Consumed by | Notes |
|---|---|---|---|---|
| `global.image.tag` | string | `"0.9.4"` | every service | The single product-tag knob: applied to every image unless that service sets `<name>.image.tag`. Finalized against published digests at T7. |

Per-service override: set `<name>.image.tag` (and `<name>.image.repository`, `<name>.image.pullPolicy`).
Tag precedence in the `tatrman-service` library: `image.tag` → `global.image.tag` → the chart's `appVersion`.
The four NLP backends (§5.1) render their own tag and are pinned individually.

Image pull secrets: set per service (`<name>.imagePullSecrets`), or supply cluster-default pull secrets.

---

## 3. Enable toggles

Every service defaults **on** except `backstage`, `devIdp` and `automation`. `<name>.enabled: false` drops it.

| Group | Keys (all `.enabled`) | Default |
|---|---|---|
| Query spine | `veles`, `query`, `translate`, `validate`, `dispatch`, `llm-gateway`, `mssql`, `postgres`, `polars`, `whois`, `health`, `query-mcp`, `veles-mcp` | `true` |
| Understanding layer | `fuzzy`, `fuzzy-mcp`, `nlp`, `nlp-mcp`, `chrono`, `geo`, `money`, `grounding-mcp`, `resolver` | `true` |
| Automation | `automation` | `false` (pre-release, opt-in; §5.4) |
| Developer portal | `backstage` | `false` (upstream image, opt-in) |
| Dev IdP | `devIdp` | `false` (quickstart only, §4.2) |

The reference fixtures: `fixtures/values-minimal.yaml` (spine only), `fixtures/values-full.yaml`
(everything + opt-ins), `fixtures/values-dark-geo.yaml` (honest degrade), `fixtures/values-automation.yaml`
(the automation service, every key of §5.4 set).

---

## 4. Identity & OIDC (`auth`, `devIdp`) — T3

### 4.1 The `auth` contract

The reference IdP is Keycloak-shaped. These keys **describe** the token issuer a deployment
fronts the MCP doors with; the **duty** to verify a token before pipeline entry (mcp-surface
§3.4, H-2) is the deployment's.

| Key | Type | Default | Notes |
|---|---|---|---|
| `auth.issuerUrl` | string | `""` | Token issuer, e.g. `https://idp/realms/tatrman`. Auto-derives to the in-cluster URL when `devIdp.enabled`. |
| `auth.audience` | string | `""` | Expected token audience. |
| `auth.jwksUri` | string | `""` | JWKS endpoint, e.g. `{issuerUrl}/protocol/openid-connect/certs`. |
| `auth.realm` | string | `"tatrman"` | Realm name (also the seeded dev-IdP realm). |
| `auth.verification` | enum `ingress`\|`in-door` | `ingress` | Where the verify-before-pipeline duty is discharged. `ingress` = an auth-terminating ingress/sidecar verifies the signature and the doors extract claims (**the live pilot's conforming arrangement**). `in-door` = the doors verify the signature themselves. The mechanism is free; the guarantee is contractual. |
| `auth.trustedNetworkShortcuts` | bool | `false` | The `X-User-Id` header / `user_id` argument shortcuts are **outside** the OBO contract (mcp-surface §3.5). Off by default; a deployment enabling them does so on its own authority — ignored whenever a bearer token resolves. |

**Claim conventions (fixed by the OBO contract, not configurable):** user id =
`preferred_username` falling back to `sub`; roles = `realm_access.roles`. Other IdPs map to
the same two facts — the mapping is deployment configuration.

> In `ingress` mode these values configure the ingress the deploying environment adds; the
> chart's services do not verify signatures themselves (they extract claims and fail closed on
> a missing identity — `requireIdentity` defaults on). This is the conformant pilot posture.

### 4.2 Dev Keycloak (`devIdp`) — QUICKSTART ONLY

A throwaway in-cluster Keycloak so the quickstart has a working IdP. **Not for production**:
dev-mode HTTP (no TLS), plaintext admin creds, an auto-imported realm with a password-grant
public client. Renders nothing unless `devIdp.enabled: true`.

| Key | Type | Default | Notes |
|---|---|---|---|
| `devIdp.enabled` | bool | `false` | Master toggle. |
| `devIdp.image.{repository,tag,pullPolicy}` | | `quay.io/keycloak/keycloak` / `26.0` / `IfNotPresent` | |
| `devIdp.admin.{username,password}` | string | `admin`/`admin` | **Dev only** — never a real secret. |
| `devIdp.httpPort` | int | `8080` | |
| `devIdp.resources` | map | cpu 250m / mem 512Mi–1Gi | |

Seeds realm `{auth.realm}`: a public client `tatrman-agent` (direct-access-grants for the
quickstart's `curl` token fetch) and a `demo`/`demo` user with the realm role `user`. When
enabled, the in-cluster issuer is `http://<release>-keycloak.<ns>.svc:8080/realms/<realm>`
(printed in `NOTES.txt`).

---

## 5. Understanding-layer capability config — T4

### 5.1 NLP backends & FI-4 (`nlp.backends.*`)

`nlp` degrades gracefully when a backend is absent, so the **UFAL (CC BY-NC-SA) engines
ship OFF by default** (S0 disposition 2026-07-17) and the permissive-licence engines stay on —
the default install has working NLP that degrades rather than dies.

| Backend | Key | Default | Licence | Notes |
|---|---|---|---|---|
| MorphoDiTa | `nlp.backends.morphodita.enabled` | `false` | UFAL **CC BY-NC-SA** | cs tokenize/lemma/POS. **Opt-in.** |
| NameTag 3 | `nlp.backends.nametag3.enabled` | `false` | UFAL **CC BY-NC-SA** | cs/en NER. **Opt-in.** |
| Stanza | `nlp.backends.stanza.enabled` | `true` | Apache-2.0 | cs DEP_PARSE + en. |
| spaCy | `nlp.backends.spacy.enabled` | `true` | MIT | en tokenize + NER fallback. |

**Enable path + licensing precondition.** Setting `morphodita.enabled` / `nametag3.enabled`
to `true` installs images with **CC BY-NC-SA** UFAL models baked in. Do this only where that
licence's terms (incl. the "NC" non-commercial clause) are cleared for your use, and where the
backend images are reachable from a registry your install can pull. The legal verdict does not
gate the chart — it gates whether a given install may flip these keys (and whether the backend
images may be published to a **public** registry — the open FI-4 tail).

Each backend also takes `image.{repository,tag,pullPolicy}`, `replicaCount`, `resources`,
`port`. The backends render their **own** image tag (not via `global.image.tag`) — pin per
backend (default `0.9.2`, the nlp image family).

### 5.2 Geo / location (`geo.*`)

| Key | Type | Default | Notes |
|---|---|---|---|
| `geo.nominatim.baseUrl` | string | `""` | **No default (Q-19).** Unset ⇒ geocoding is **dark by policy** (reports dark, fails loud, never wrong — RS-19). The public OSM Nominatim is **not** a valid production endpoint. Self-host and set this. |
| `geo.nominatim.userAgent` | string | `tatrman-geo/1.0 (+https://github.com/Collite/ttr-server)` | |
| `geo.db.enabled` | bool | `false` | Durable boundary cache (PostGIS/Postgres). Off ⇒ in-memory. |
| `geo.db.{host,port,name,user}` | | — / 5432 / geo / geo | Password via `geo.secretEnv` (Secret ref). |
| `geo.db.boundaryCacheTtlDays` | int | `90` | |
| `geo.boundaryPrime.enabled` | bool | `false` | Install-time boundary-cache priming Job (Helm post-install/upgrade hook, `PrimingRunner`). Best-effort — a Nominatim outage during priming is counted, not fatal. Needs `db.enabled` + a reachable `nominatim.baseUrl` to be useful. |
| `geo.boundaryPrime.places` | string | `""` | Comma/newline-separated place names to warm (model POI names + distinct member cities). |
| `geo.boundaryPrime.command` | list | `java -cp @/app/jib-classpath-file …PrimingRunner` | Overrides the geo image entrypoint to run the primer main class (the jib classpath layout). Override if the image layout differs. |

Tuning `geo.confidenceThreshold` is env-driven (`GEO_CONFIDENCE_THRESHOLD`) — set via
`geo.extraEnv` until surfaced as a first-class key.

### 5.3 Resolver (`resolver.*`)

| Key | Type | Default | Notes |
|---|---|---|---|
| `resolver.resumeToken.activeKeyId` | string | `""` | HMAC resume-token signing key id. |
| `resolver.resumeToken.allowEphemeralKey` | bool | `false` | **Fail-closed:** with no key and this `false`, the service refuses to boot. `true` = a throwaway per-process key (local/test only — breaks stateless resume across replicas/restarts). |
| `resolver.secretEnv` | list | `[]` | The key material by **Secret ref** — e.g. `{ name: RESOLVER_RESUME_KEY_K1, secretName: resolver-resume-keys, secretKey: k1 }`. |
| `resolver.mcp.{host,requireIdentity,trustNetwork}` | | `127.0.0.1` / `true` / `false` | The `resolve.bind:v1` MCP door: loopback + require-identity by default (RG-P6 review D — the door decodes but does not signature-verify the OBO JWT, so it must not be off-host-reachable unless fronted by an auth-terminating ingress). |

Gating thresholds (`threshold-bind`, `threshold-ambiguity-gap`, `threshold-exact`) are
config-baked in the resolver image (`application.conf`), not values-exposed.

### 5.4 Automation (`automation.*`) — opt-in, pre-release

The service migrates its own database at start (Flyway: the ledger, then pgmq installed SQL-only — no
extension, no custom Postgres image). Four keys have no default and fail the render by name when unset:
`db.host`, `db.passwordSecret.name`, `auth.jwksUrl`, `auth.issuer`.

| Key | Type | Default | Notes |
|---|---|---|---|
| `automation.db.{host,port,name,user}` | | `""` / `5432` / `automation` / `automation` | The ledger's Postgres. The user must own the database (Flyway creates schema `automation` and pgmq's). |
| `automation.db.passwordSecret.{name,key}` | | `""` / `password` | The password by **Secret ref**. |
| `automation.auth.{jwksUrl,issuer}` | string | `""` | Every route but `/automation/health` and the probes verifies the bearer: RS256 against the realm's JWKS, `iss` = `issuer`. There is no unverified mode. |
| `automation.auth.audience` | string | `""` | When set, `aud` must contain it. Unset, a token the realm issued to **any** client is accepted (the service warns at start). |
| `automation.auth.refreshRoles` | list | `[]` | The realm roles that may call `POST /automation/refresh`; empty = the service's default, `ops`. Anyone else is `403 refresh-forbidden`. |
| `automation.auth.tokenUrl` | string | the JWKS URL's sibling | The realm's token endpoint, for the named identities' client credentials. |
| `automation.auth.authorMap` | string | `""` | `author=principal` pairs, comma-separated: a commit author (e-mail, else name) → the deployment's `author` principal. |
| `automation.credentials` | list | `[]` | Credentials by **Secret ref**, each rendered as `TTR_CONN_<name>` — e.g. `{ name: AUTOMATION, secretName: automation-identities, secretKey: automation }`. A named identity's value is `<client id>:<client secret>`. |
| `automation.door.{queryUrl,velesUrl}` | string | `query:7306` / `veles:7261` | The door and the model, in-namespace gRPC. |
| `automation.bundle.git.{url,branch,path}` · `.tokenSecret.{name,key}` | | `""` / `master` / `""` | The repository the recipe bundles are read from; its token by Secret ref. |
| `automation.policies.fragment` | string | `""` | The `automation.ops` policy fragment, rendered as ConfigMap `<fullname>-policies` for validate to include. Empty → none. |
| `automation.configFragment.content` | string | `""` | A HOCON delta over the service's `application.conf` (the library's config fragment). |

### 5.5 BI — the Flight SQL edge (`query.flight.*`, `query.worlds`) — opt-in, pre-release

The query service's Arrow Flight SQL listener (JDBC — DBeaver, IntelliJ; ADBC — pandas; the `tatrman://`
Superset dialect). Off by default; on, it verifies every caller and the render fails by name without
`flight.auth.issuer`. The listener is exposed with the library's `ports.extra`
(`[{ name: flight, port: 7308, appProtocol: kubernetes.io/h2c }]`); the gateway route is the deployment's.
Receipts: the service migrates `bi_receipts` (schema `query`) itself at first use.

| Key | Type | Default | Notes |
|---|---|---|---|
| `query.flight.enabled` | bool | `false` | The edge on 7308. **Single-replica:** a statement's ticket lives in the pod that started it, so the chart refuses it with `query.replicaCount` above 1. |
| `query.flight.{port,bind}` | | `7308` / `0.0.0.0` | Behind the gateway; the service's own default bind (no chart) is `127.0.0.1`. |
| `query.flight.auth.issuer` | string | `""` | **Required when enabled.** Bearers are verified RS256 against the realm's JWKS, `iss` = this. |
| `query.flight.auth.{jwksUri,tokenUrl}` | string | the issuer's Keycloak endpoints | `…/protocol/openid-connect/certs` and `…/token`. |
| `query.flight.auth.audience` | string | `""` | When set, `aud` must contain it; unset, any client's token from the realm is accepted (warned at start). |
| `query.flight.auth.passwordClient` | string | `jdbc` | User name + password logins are this client's direct-access grant. `""` = bearer tokens only. |
| `query.flight.receipts.db.{host,port,name,user}` | | `""` / `5432` / `query` / `query` | `bi_receipts`' Postgres; no host = receipts off. The user must own the database (or schema `query`). |
| `query.flight.receipts.db.passwordSecret.{name,key}` | | `""` / `password` | By **Secret ref**; the name is required with a host. |
| `query.worlds` | string | `""` | `world=connection_id` pairs, comma-separated: the worlds the door serves (`RunRequest.world`). Empty = one world. |

The BI row ceiling is validate's, set through `validate.extraEnv` (no chart key): `VALIDATE_MAX_TOP_N_BY_ROLE`
(`role:rows,…`, e.g. `kantheon-bi-reader:10000`) raises `VALIDATE_MAX_TOP_N` for callers holding the role.

---

## 6. Model source (`veles.metadata.*`)

Veles serves the model of one git repository, cloned at install and re-fetched every 5 minutes.

| Key | Type | Default | Notes |
|---|---|---|---|
| `veles.metadata.gitRemoteUri` | string | `https://github.com/Collite/ttr-demo.git` | The **public Hartland demo model**, so a fresh install has a model to explore with no token. `""` ⇒ no model source: Veles logs an error and never turns ready. |
| `veles.metadata.gitBranch` | string | `master` | Hartland's branch. **Set it together with `gitRemoteUri`.** `""` renders no env var (Veles' own default applies). |
| `veles.metadata.gitSubdirectory` | string | `model` | The model's directory inside the repository — Hartland's. **Set it together with `gitRemoteUri`.** |
| `veles.secretEnv` | list | `METADATA_GIT_TOKEN` from Secret `veles-github-pat` (key `token`, optional) | A read token for a private repository. |

`gitBranch` / `gitSubdirectory` render nothing when `veles.extraEnv` already sets `METADATA_GIT_BRANCH` /
`METADATA_GIT_SUBDIR` (the older way to set them), so an existing install keeps its values and gets no
duplicate env var.

**No fallback model (ttr-server#180).** Veles bundles no model. If the repository can't be loaded (a
bad token, a wrong branch or sub-path, no network), Veles serves no model and its `/ready` answers 503
until the source has loaded once; `GET /status` on Veles shows the source and its last error. A
source that loads but finds no model objects has not loaded.

---

## 7. Security notes (the standing rules)

- **Secrets by reference only.** Every secret is a K8s `Secret` ref (`secretEnv[].{secretName,secretKey}`) —
  no plaintext secret value is a chart value (the no-secret-API rule). The one exception is
  `devIdp.admin.*`, which is dev-only and loudly marked not-for-production.
- **Verify before pipeline entry** (§4.1) is mandatory — the pilot terminates auth at ingress.
  `requireIdentity` defaults on: a call with no resolvable OBO identity fails closed
  (`missing_user_identity`). Trusted-network shortcuts are off (§4.1).
- **Resolver fail-closed** on an empty resume-key set (§5.3) unless `allowEphemeralKey`.
- **Geo dark-by-policy** with no Nominatim endpoint (§5.2): honest degrade, never a wrong answer.
- **No silent model substitution** (§6): a model source that fails to load leaves Veles not ready
  and serving nothing, never another model.

---

## 8. Per-service passthrough

Anything under a service's key that is not listed above is passed to that service's own chart —
see `services/<x>/k8s/values.yaml` (or `workers/`, `tools/`, `infra/`) for its full surface
(telemetry, probes, resources, replicaCount, `extraEnv`, `secretEnv`, service-specific wiring).
Those are per-service defaults, not part of this umbrella contract; rely on them at the
per-service chart's stability, not the product's.
