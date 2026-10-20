# automation

The automation service (AU): it runs recipes through the door, keeps the ledger and the protocol of every
run, and is the exchange — the run API in, the connector SPI out. The design, contracts, plan and status live
in the project folder:
[`project/kantheon/features/automation-agents/`](../../../project/kantheon/features/automation-agents/)
(the service: `implementation/ttr-server/` — `contracts.md` is the API, the ledger and the config).

> **Built so far:** P0 · S0.1 (the ledger, the idempotent claim, the error envelope, health) and S0.3
> (identity, the chart, the image). The bundle load and `/refresh` (S0.2) follow the toolchain's recipe
> grammar; until then `GET /automation/` answers with nothing served.

## Operating it

**Configuration** is `automation.*` in `src/main/resources/application.conf`, every key overridable by an
`AUTOMATION_*` variable (AU contracts §8). Five keys have no default, and a pod missing one fails at start
naming all of them: `db.host`, `door.queryUrl`, `door.velesUrl`, `auth.jwksUrl`, `auth.issuer`. The chart
(`k8s/`, also the `automation` key of the umbrella `helm/tatrman-server`) renders them from values and fails
the render by key when one is missing.

**The database migrates itself.** At start the service runs Flyway against its own database (one
`automation` database, schema `automation`): `V1` the ledger, `V2` pgmq 1.13.0 installed **SQL-only** —
upstream's `pgmq.sql`, vendored unchanged and hash-checked, PostgreSQL License (see NOTICE) — `V3` the
four queues, and `V4` D-98's one-open-task index with NULLs held equal (`NULLS NOT DISTINCT`, PostgreSQL 15+).
No extension and no custom Postgres image are needed; the database user must own the database.
The HTTP port opens only after the migration, so the chart's startup probe covers it.

**Identity, in five lines** (AU contracts §7):
1. Every route but `/health`, `/ready` and `/automation/health` needs a bearer that verifies — RS256 against
   the realm's JWKS, the configured issuer, unexpired, the audience when one is set. Anything else is `401
   unauthenticated`; there is no header or argument fallback. ⚑ Without `auth.audience`, a token the realm
   issued to any client verifies (the service warns at start). `POST /automation/refresh` also needs one of
   `auth.refreshRoles` (default `ops`) — else `403 refresh-forbidden`.
2. The caller may always run a deployment **as caller** — the door sees the caller's own token.
3. **As author** (the deployer, from the refresh commit via `AUTOMATION_AUTHOR_MAP`) or **as a named identity**
   (`def identity` in the world) needs a `def grant`; the deployer may always run their own deployment as author.
4. A named identity runs on its client credentials, `TTR_CONN_<NAME>` = `<client id>:<client secret>`; unset
   → `403 identity · unmapped`. Running as someone else's *person* author needs IdP token exchange →
   `403 identity · token-exchange`.
5. Debug runs are as the caller, or with a grant as the deployment's `run_as`; the protocol records both.

**Ports:** HTTP 7131. **Image:** `ghcr.io/collite/automation` (a tag `automation/v<x.y.z>` publishes it).
