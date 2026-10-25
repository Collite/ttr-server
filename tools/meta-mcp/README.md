# meta-mcp

MCP wrapper for the model-graph service `metadata` — formerly `veles` (2026-06-13 → 2026-10), originally `ai-platform/infra/metadata`.

The module, image and Helm chart are `meta-mcp`; the Kotlin package is
`org.tatrman.metadata.mcp`. It exposes the model read and area-resolution tools over
HTTP on port **7262** (`META_MCP_SERVER_PORT`). Configure the gRPC backend with
`METADATA_PROTOCOL`, `METADATA_GRPC_HOST` (default `metadata`) and
`METADATA_GRPC_PORT` (default `7261`). Telemetry uses `OTEL_ENABLED_META_MCP`.

Run `just build meta-mcp` and `just test meta-mcp`. The backend and consumers must
roll together because the gRPC service is `org.tatrman.meta.v1.MetadataService`.
