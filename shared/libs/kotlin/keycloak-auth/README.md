# keycloak-auth

> **forked-from:** the legacy predecessor platform (`shared/libs/kotlin/erp-sql-common/src/.../auth/`), extracted 2026-06-24 (fork Phase 5 Stage 5.0).
> Maintained independently since the fork; do not assume parity with the original.

A generic Keycloak `client_credentials` token provider, **extracted** off the predecessor platform's
`erp-sql-common.auth`. These four files have **zero** imports from the rest of
`erp-sql-common`, so the legacy ERP-SQL line need not fork to carry them — they move
into this standalone, domain-free lib instead.

## Package root

`org.tatrman.keycloak.auth` (renamed off the legacy erp-sql-common auth package — the fork
drops the "erp-sql" coupling; the code is a generic Keycloak token provider, not ERP-specific).

## Contents

| Type | Role |
|---|---|
| `TokenProvider` | `suspend fun getToken(): String` — the interface |
| `KeycloakTokenProvider` | fetches a service-account token via the `client_credentials` grant (Ktor + Apache engine); `fromConfig` / `create` factories |
| `CachingTokenProvider` | Caffeine-backed TTL cache decorator (expiry buffer applied) |
| `TokenResponse` | the Keycloak token-endpoint JSON shape |

## Consumers (post-fork)

- `infra/whois` — the user/role directory's Keycloak sync (Stage 5.1).

The `NoErpSqlCommonImportSpec` test guards the extraction: it fails if any source file
re-introduces an `erp.sql.common` / `infra.erp` reference.
