# OpenTelemetry Configuration — kantheon

> **forked-from:** the legacy predecessor platform (`shared/libs/python/otel-config/`), forked 2026-06-13.
> Maintained independently since the fork; do not assume parity with the original.

OpenTelemetry SDK initialisation for the kantheon Python lane (nlp in fork Stage 2.3, worker-polars in 3.4, Metis when it adopts). Mirrors `:shared:libs:kotlin:otel-config` on the JVM side — same OTLP exporters, same logback-OTel bridge equivalent, same `service.name`/`service.version`/`kantheon.module` resource attributes. See `EXAMPLES.md` §8 for the JVM init pattern; this README is the Python sibling.

## Usage

```python
from otel_config import setup_tracing, setup_logging

setup_tracing(service_name="nlp", service_version="0.1.0")
setup_logging(service_name="nlp", service_version="0.1.0")
```

## Sync

`just py-sync-all` (or `uv sync` in this directory) installs the deps into the workspace.
