# SPDX-License-Identifier: Apache-2.0
from collections.abc import Callable
from typing import Any

from fastapi import FastAPI, Response
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Gauge, Histogram, generate_latest

runs = Counter("docproc_runs_total", "Completed runs", ["family", "status"])
duration = Histogram("docproc_run_seconds", "Run duration", ["family"])
verdicts = Counter("docproc_verdicts_total", "Failed postconditions", ["family", "rule"])
queue_depth = Gauge("pipeline_queue_depth", "Messages including hidden", ["queue"])
queue_age = Gauge("pipeline_oldest_msg_age_sec", "Oldest message age", ["queue"])


def make_probes_app(
    is_ready: Callable[[], bool],
    status: Callable[[], dict[str, Any]],
    refresh_metrics: Callable[[], None] = lambda: None,
) -> FastAPI:
    app = FastAPI(title="docproc", version="0.11.0")

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/ready")
    def ready() -> Response:
        return Response(status_code=200 if is_ready() else 503)

    @app.get("/status")
    def get_status() -> dict[str, Any]:
        return status()

    @app.get("/metrics")
    def metrics() -> Response:
        refresh_metrics()
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    return app
