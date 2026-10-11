# otel-config

> **forked-from:** the legacy predecessor platform (`shared/libs/kotlin/otel-config/`), forked 2026-06-13.
> Maintained independently since the fork; do not assume parity with the original.

Initialises the OpenTelemetry SDK from HOCON config: OTLP exporters (traces, metrics, logs), the SLF4J/Logback bridge so log records flow into the same `SdkLoggerProvider`, and the resource attributes (service.name, service.version, kantheon module tag).

Used by every Kotlin service via `createOpenTelemetrySdk(...)`. See `EXAMPLES.md` §8 for the canonical init pattern.

## Package root

`shared.otel` (preserved from the predecessor platform per the fork convention — see [`tasks-p1-s1.3-shared-libs.md`](../../../docs/implementation/v1/fork/tasks-p1-s1.3-shared-libs.md) pre-flight note).
