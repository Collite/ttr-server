# Kantheon Developer Portal (Backstage)

> **forked-from:** the legacy predecessor platform (`infra/backstage/`), forked 2026-06-24 (fork Phase 5 Stage 5.5).
> Maintained independently since the fork; do not assume parity with the original. The fork is a **rebrand + catalog re-point** — the Backstage app source is unchanged; `organization.name`, `app.title`, baseUrls and the **software catalog** move to Kantheon.

The Kantheon developer portal — a technical-wave (no-persona) Backstage app in `infra/`. Own Node +
Yarn toolchain (a documented build exception, like llm-gateway's Spring Boot), its own backend on
**port 7007**, TechDocs + scaffolder.

## Rebrand surface (Stage 5.5)

- `app-config.yaml`: `organization.name` → **Kantheon**; `app.title` → **Kantheon Developer Portal**.
- `examples/kantheon-catalog.yaml` (new): the constellation as a Backstage catalog — a `kantheon`
  System + Group and a `Component` per pantheon citizen (agents Iris-BFF/Themis/Golem/Pythia/Hebe;
  platform services Metadata/query/lex-matcher/nlp/translate/dispatch/validate/llm-gateway/Charon/Metis;
  workers worker-mssql/worker-polars/worker-postgres; `capabilities-mcp`; technical wave identity/health/landing). Wired as
  a `catalog.locations` file entry; replaces the predecessor's example catalog.
- `docs/catalog-info.yaml`, `templates/agent-starter/template.yaml`, `app-config.local.yaml`
  (`doc.aip.localhost` → `doc.kantheon.localhost`), the OIDC username-transform comment in
  `packages/backend/src/index.ts` — all rebranded off the prior client-specific and predecessor-platform naming.

## Build / run

```sh
yarn install
yarn tsc && yarn build      # Node-toolchain exception — CI-gated (yarn 4 berry, large dep tree)
yarn start
```

The image is built and tagged `kantheon/backstage` (port 7007) at deploy; deploy manifests fork
from `deployment/apps/backstage`. The `yarn tsc && yarn build` + Backstage component tests run in
CI (the Node toolchain is not exercised on the local Rancher path); this stage's deterministic
local gate is YAML/catalog well-formedness + a residual prior-naming sweep (client and predecessor-platform names).
