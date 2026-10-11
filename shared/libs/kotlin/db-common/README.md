# db-common

> **forked-from:** the legacy predecessor platform (`shared/libs/kotlin/db-common/`), forked 2026-06-13.
> Maintained independently since the fork; do not assume parity with the original.

HikariCP + Exposed bootstrap for MSSQL/Postgres, with HOCON-driven `DatabaseConfig` and a `DatabaseConnection` lifecycle owner. Used by `workers/worker-mssql` (MSSQL), `services/validate` and `services/query` (Postgres).

## Package root

`shared.libs.db.common` (preserved from the predecessor platform per the fork convention — see [`tasks-p1-s1.3-shared-libs.md`](../../../docs/implementation/v1/fork/tasks-p1-s1.3-shared-libs.md) pre-flight note).
