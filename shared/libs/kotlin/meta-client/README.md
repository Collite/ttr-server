# meta-client

Shared gRPC client for the model-graph service `metadata` — formerly `veles` (2026-06-13 → 2026-10), originally `ai-platform/infra/metadata`.

The artifact stays `org.tatrman:meta-client`. Import `MetadataGrpcClient` and
`GrpcMetadataGrpcClient` from `org.tatrman.metadata.client`. It depends on the shared
proto stubs and gRPC transport, independently of the server application module.

The package move requires rebuilding JVM callers. The gRPC wire service is
`org.tatrman.meta.v1.MetadataService`; the server and all consumers in ttr-server,
kantheon and tatrman-platform (studio-bff) must roll together per cluster. Proto message types and package names are unchanged.

Run `just build meta-client` and `just test meta-client`.
