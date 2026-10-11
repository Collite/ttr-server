# data-formatter

> **forked-from:** the legacy predecessor platform (`shared/libs/kotlin/data-formatter/`), forked 2026-06-13.
> Maintained independently since the fork; do not assume parity with the original.

Arrow IPC + Apache POI (XLSX) + Apache Parquet output formatters over a canonical `RowBatch`. Snapshot-driven, format-agnostic — emit CSV/TSV/JSON/Markdown/XLSX/Parquet from the same in-memory representation, used by `tools/query-mcp` and the workers layer for export.

## Package root

`shared.formatter` (preserved from the predecessor platform per the fork convention — see [`tasks-p1-s1.3-shared-libs.md`](../../../docs/implementation/v1/fork/tasks-p1-s1.3-shared-libs.md) pre-flight note).
