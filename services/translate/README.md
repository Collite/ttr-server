# translate

> **forked-from:** the legacy predecessor platform (`services/translator/`), forked 2026-06-14.
> Maintained independently since the fork; do not assume parity with the original.

The **translator** for kantheon — a thin gRPC service wrapping the
`ttr-translator` Calcite/RelNode engine. Converts between the supported
`Language`s (SQL, Transformation DSL, DataFrame DSL, RelNode) and SQL dialects,
the two-halves way the pipeline uses it:

- **`ParseToRelNode`** — front of pipeline: source language → canonical RelNode in the target schema.
- **`UnparseFromRelNode`** — back of pipeline: validated RelNode → dialect SQL (default MSSQL).
- **`Translate`** — one-shot any-to-any (frontends, inspection).
- **`Explain`** — debug variant returning per-stage artefacts.
- **`DetectSourceSchema`** — infer the effective source schema.

The gRPC service proto is forked as `org.tatrman.translate.v1` / `TranslateService`
(RPC names kept; `com.tatrman.translator.v1` is gone). The `Language` /
`SqlDialect` enums live in the library-only `translator.proto` (same package,
Stage 1.3) and are imported by the service proto. Model data comes from
**Veles** (formerly the metadata service): translate polls `GetSnapshot` and
calls `GetQuery`, via `org.tatrman.meta.v1.VelesServiceGrpcKt`.

Kotlin source root: `org.tatrman.translate`. Ports: **7275** (HTTP) /
**7276** (gRPC). No MCP wrapper — translate is an internal pipeline service called
by query-runner, not an agent-facing tool.

## Local / test

```bash
just test-kt translate          # Calcite golden round-trip suite
./gradlew :services:translate:ktlintCheck
just deploy-kt translate        # Jib image + k8s/overlays/local
```

`translate.use-fixture-model = true` (env `TRANSLATE_USE_FIXTURE_MODEL`) boots
against the in-process boot fixture when no Veles is reachable (local/test).
