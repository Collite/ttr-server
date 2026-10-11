# identity

> **forked-from:** the legacy predecessor platform (`infra/whois/`), forked 2026-06-24 (fork Phase 5 Stage 5.1).
> Maintained independently since the fork; do not assume parity with the original.

The **user/role directory** and **OPA bundle server** — a technical-wave (no-persona) service
in the `infra/` tree. It syncs users + roles from upstream sources (Keycloak admin API, ERP DB)
into its **own Postgres** and serves:

- `GET /whois?internal_id= | ?user_id=&user_id_type= | ?email= | ?id=` → `UserRecord` JSON
  (the shape `whois-common` defines; the one validate's optional `WhoisRoleSource` consumes — Stage 5.3).
- `GET /bundle/{type}/roles.tar.gz` → an OPA policy bundle (merged `data.json` + `.rego` policies + `.manifest`).
- `GET /health`, `GET /ready` → probes.

Identity discipline (fork §6): identity is **never an identity authority** — it is an optional
*role-enrichment* source. Kantheon resolves identity from the bearer at the query-mcp edge;
validate calls identity (when `validate`'s `roleSource = whois`) only to expand the ERP role hierarchy the
Keycloak token does not carry.

## Package root

`org.tatrman.identity.*` (swept off `infra.whois.*`). Domain records live in the `whois-common`
lib (`org.tatrman.identity.domain`); the Keycloak token provider in the `keycloak-auth` lib
(`org.tatrman.keycloak.auth`) — both forked in Stage 5.0.

## Port

HTTP **7110** (`WHOIS_SERVER_PORT`, kept from the predecessor platform). No gRPC, no MCP wrapper.

## Repository modes

- `whois.repository.type = json` (default): in-memory from a `whois.json` classpath/file fixture
  — used for local boot and the component tests; no DB needed.
- `whois.repository.type = db`: Postgres-backed (`who-is-database`), Flyway V1–V5 on boot, with
  the Keycloak/ERP sync loop active. The live deployment runs in `db` mode.

### The ERP user source

An ERP's user schema is its own, so the image ships no ERP SQL. The `db`-mode ERP sync
(`WHOIS_ERP_SYNC_ENABLED`) reads four queries from `erp-database.queries`, each returning the
columns named here:

| key (env) | columns |
|---|---|
| `users` (`ERP_USERS_QUERY`) | `user_id`, `username`, `fullname` (`"First Last"`), `email` — the users to sync, active ones only |
| `roles` (`ERP_ROLES_QUERY`) | `id`, `code`, `description` |
| `user-roles` (`ERP_USER_ROLES_QUERY`) | `user_id`, `role_id` |
| `role-hierarchy` (`ERP_ROLE_HIERARCHY_QUERY`) | `parent_role`, `child_role` (a role that is its own parent is a root) |

While any of the four is unset the ERP source is skipped: a WARN at startup names the missing keys,
and no ERP connection is opened. Users are matched across sources by email, so a user whose email is
blank becomes `<username>@<erp-database.fallback-email-domain>` (`ERP_FALLBACK_EMAIL_DOMAIN`); with
no domain set, such a user is skipped and counted in a WARN.

## Run

```
just deploy-kt infra/identity   # Jib image + Helm deploy (CI/cluster; Jib build is CI-gated on Rancher)
./gradlew :infra:identity:test  # mocked unit/component suite
```
