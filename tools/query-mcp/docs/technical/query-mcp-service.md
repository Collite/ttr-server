# query-mcp service

The agent-facing MCP tools service for the v1 query pipeline. Sits between MCP clients (Python LangChain agents, mcp-cli, etc.) and the v1 backend, exposing two tools — `query` and `compile` — over StreamableHTTP transport.

## Tool surfaces

### `query`

Submit a query to the v1 platform; returns formatted result data plus a structured envelope.

**Required input:**
- `source` — query source text in the chosen language
- `source_language` — `sql` | `transdsl` | `dfdsl` | `rel_node`

**Optional input:**
- `parameters` — bound parameter map; types inferred from JSON value
- `session_id` — sticky-routing token (only used when the chosen worker advertises `supports_stateful_sessions = true`)
- `format` — `json` (default) | `csv` | `tsv` | `markdown`
- `row_limit` — clamped to `[1, 5000]`; default `500`
- `user_id` — trusted-network override; ignored when an `Authorization` token resolves
- `hide_columns_matching` — **G3** — list of regex patterns; columns whose names match any pattern are hidden
- `row_numbering` — **G3** — `none` (default) | `one_based` (prepends a `#` index column)
- `source_schema` — `er` | `db` | `obj` — the catalog the source's identifiers resolve against. Default `db` when `source_language` is `sql`; otherwise unset.

**Output:**
- `content[0]` — `TextContent` with the formatted result (mediaType matches `format`)
- `structuredContent` — see Envelope shape below

The analytical agent's typical call site uses `hide_columns_matching=["^ID"]` to hide technical key columns.

### `compile`

Compile a query into target-dialect SQL without executing.

**Required input:**
- `source`, `source_language` — same as `query`
- `target_dialect` — `mssql` | `postgresql` | `mysql_mariadb`

**Optional input:**
- `parameters`, `user_id` — same as `query`
- `apply_security` — default `true`. Setting `false` requires admin role (rejected with `permission_denied` otherwise).
- `source_schema` — `er` | `db` | `obj` — the catalog the source's identifiers resolve against, passed through to `ParseToRelNode`. When absent, no source schema is sent and the translator detects it from the source; note this differs from `query`, which defaults `sql` to `db`. To compile exactly what `query` ran, pass the same value (`db` for a `sql` source that `query` ran without one). Any other value is rejected with `unknown_source_schema`.

**Output:**
- `content[0]` — `TextContent` with the compiled SQL (mediaType `text/plain`)
- `structuredContent.compiledSql` — same SQL string for machine consumption
- `structuredContent.parameterPlan` — `[{name, type, bound, label?}, ...]`

## Envelope shape

```jsonc
{
  "ok": true,
  "tool": "query",
  "rowCount": 5,
  "columnCount": 4,
  "truncated": false,
  "format": "json",
  "mediaType": "application/json; charset=utf-8",
  "columns": [
    { "name": "id", "type": "Int64", "nullable": true },
    ...
  ],
  "messages": [
    { "severity": "warning", "code": "partial_results_truncated", "text": "..." }
  ],
  "pipelineWarnings": [
    {
      "code": "security_predicate_applied",
      "severity": "info",
      "text": "Filter applied for table dbo.Customers",
      "sourceService": "validator",
      "metadata": { "sourceStage": "security" }
    }
  ]
}
```

`pipelineWarnings` is **always present** — empty array when no warnings — so MCP clients can write a uniform parser.

## Transport

- StreamableHTTP at `${query-mcp.mcp.path}` (default `/mcp`).
- HTTP probes: `/health`, `/ready`, `/status`, `/metrics`, `/shutdown`.
- `/ready` returns 200 only when all three upstream gRPC channels (query-runner, translator, validator) are in `READY` or `IDLE` connectivity state.

## Identity

Three-source identity resolution in priority order:

1. **`Authorization: Bearer <jwt>`** — preferred for production. The `preferred_username` claim wins, falling back to `sub`. Roles come from `realm_access.roles` (Keycloak convention).
2. **`X-User-Id` header** — service-to-service shortcut.
3. **Tool-arg `user_id`** — trusted-network shortcut.

**v1 limitation.** This service decodes inbound JWTs *without verifying their signatures*. Production deployments are expected to terminate inbound auth at an ingress / sidecar that validates the token before it reaches `query-mcp`; the resolver here only extracts claims for downstream context-passing.

`security.require-identity` (HOCON / `QUERY_MCP_REQUIRE_IDENTITY` env) controls whether requests without any identity are rejected with `missing_user_identity`.

## Upstream dependencies

| Upstream      | Purpose                                          | Default port |
|---------------|--------------------------------------------------|-------------:|
| query-runner  | `Run` (server-streaming) + `Compile`             |         7210 |
| translator    | `ParseToRelNode` + `UnparseFromRelNode`          |         7207 |
| validator     | `Validate`                                       |         7208 |

Channels are created at boot, reused across requests, and configured with 32 MiB max inbound message size, 30 s keepalive, gRPC built-in retry on `UNAVAILABLE`. They close on JVM shutdown.

## Error codes

See [error-codes.md](./error-codes.md).

## Parallel deployment with `erp-data-mcp`

`tools/erp-data-mcp/` (the v0 tools service) remains untouched and reachable on its existing port. v1 cut-over happens in Phase 2.5 — an agent-side configuration change, not a code change.
