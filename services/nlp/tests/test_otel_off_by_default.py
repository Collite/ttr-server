# SPDX-License-Identifier: Apache-2.0
"""OpenTelemetry is off unless a collector is named.

The front used to default the collector to `localhost:4317` and build its log,
trace and metric exporters in every pod. Where no collector runs, each retried
forever: an ERROR/WARNING pair every few seconds in the pod log (and in this
suite). The chart renders `OTEL_EXPORTER_OTLP_HOST` only when telemetry is
enabled with a host, so that variable is the switch.

Off, the tracer and meter are the OpenTelemetry API's no-ops; `/v1/analyze`
still opens its span and counts its requests against them
(`test_loop_not_blocked.py` drives that path with no collector set).
"""

from __future__ import annotations

import pytest

from nlp_service.api import routes

_OTEL_ENV = (
    "OTEL_EXPORTER_OTLP_HOST",
    "OTEL_EXPORTER_OTLP_GRPC_PORT",
    "OTEL_EXPORTER_OTLP_HTTP_PORT",
    "NLP_SERVICE_OTEL_PROTOCOL",
)


@pytest.fixture
def otel(monkeypatch):
    """Record what `create_app` asks of OpenTelemetry, without touching the process."""
    for name in _OTEL_ENV:
        monkeypatch.delenv(name, raising=False)
    seen = {"setup": [], "fastapi": [], "httpx": 0}

    def setup(**kwargs):
        from opentelemetry import metrics, trace

        seen["setup"].append(kwargs)
        return {"tracer": trace.get_tracer("test"), "meter": metrics.get_meter("test")}

    class _Httpx:
        def instrument(self):
            seen["httpx"] += 1

    monkeypatch.setattr(routes, "setup_opentelemetry", setup)
    monkeypatch.setattr(routes, "instrument_fastapi", seen["fastapi"].append)
    httpx_instrumentation = pytest.importorskip("opentelemetry.instrumentation.httpx")
    monkeypatch.setattr(httpx_instrumentation, "HTTPXClientInstrumentor", _Httpx)
    return seen


def test_no_collector_builds_no_exporters(otel):
    routes.create_app()

    assert otel == {"setup": [], "fastapi": [], "httpx": 0}


def test_a_blank_host_is_no_collector(otel, monkeypatch):
    monkeypatch.setenv("OTEL_EXPORTER_OTLP_HOST", "  ")

    routes.create_app()

    assert otel["setup"] == []


def test_a_named_collector_gets_the_exporters_and_the_instrumentation(otel, monkeypatch):
    monkeypatch.setenv("OTEL_EXPORTER_OTLP_HOST", "mon-alloy.monitoring.svc.cluster.local")

    app = routes.create_app()

    assert [(s["otel_endpoint"], s["protocol"]) for s in otel["setup"]] == [
        ("mon-alloy.monitoring.svc.cluster.local:4317", "grpc")
    ]
    assert otel["fastapi"] == [app]
    assert otel["httpx"] == 1


def test_the_http_protocol_takes_the_http_port(otel, monkeypatch):
    monkeypatch.setenv("OTEL_EXPORTER_OTLP_HOST", "collector")
    monkeypatch.setenv("NLP_SERVICE_OTEL_PROTOCOL", "HTTP")
    monkeypatch.setenv("OTEL_EXPORTER_OTLP_HTTP_PORT", "4318")

    routes.create_app()

    assert [(s["otel_endpoint"], s["protocol"]) for s in otel["setup"]] == [
        ("collector:4318", "http")
    ]
