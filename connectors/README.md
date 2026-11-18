# Connectors

Integration contracts and adapters for external systems.

- [`platform-question/`](platform-question/README.md): the open read-only question
  contract, configuration schema, Camunda 7 templates in English and Czech, and a
  generic BPMN process. Released as one archive at `platform-question/v0.11.0`.
  The bridge service follows separately; none is included here yet.
- [`openmetadata/`](openmetadata/): the OpenMetadata connector source.
- [`n8n-nodes-tatrman/`](n8n-nodes-tatrman/README.md): the Tatrman Automation nodes for n8n
  (Tatrman Run, Tatrman Trigger and their credential), moved from `kantheon` with their history
  (OL W6·S1). A standalone pnpm project; `n8n-nodes-tatrman/v*` publishes the npm package
  `@collite/n8n-nodes-tatrman` (GitHub Packages) and the derived image `ghcr.io/collite/n8n-tatrman`
  through `n8n-nodes.yml` — see [`PUBLISHING.md`](../PUBLISHING.md).
