# SPDX-License-Identifier: Apache-2.0
"""Bootstrap and drain. SIGTERM stops fetches; the current synchronous run finishes."""

from __future__ import annotations

import logging
import os
import signal
import threading
from typing import Any

import uvicorn

from docproc.bus.pgmq import PgmqBus
from docproc.config import load_config
from docproc.probes import make_probes_app
from docproc.runtime.registry import KindRegistry, TypeRegistryClient
from docproc.runtime.runner import ProcessorRunner
from docproc.sdk.pipeline import PipelineClient
from docproc.sdk.store import StoreClient


def main() -> None:
    config = load_config()
    logging.basicConfig(level=logging.INFO)
    if config.telemetry_enabled:
        from otel_config import setup_opentelemetry

        setup_opentelemetry("docproc", config.otlp_endpoint)
    token_file = os.environ.get("DOCPROC_SERVICE_TOKEN_FILE")
    warehouse = StoreClient(config.store_url, token_file=token_file)
    manager = PipelineClient(config.pipeline_url, token_file=token_file)
    bus = PgmqBus(config.pipeline_db_url)
    kinds = KindRegistry()
    # Production kind registration is added by S3.2; noop remains test-only.
    registry = TypeRegistryClient(manager)
    runner = ProcessorRunner(config, bus, warehouse, manager, kinds, registry)
    app = make_probes_app(runner.ready, runner.status, runner.refresh_metrics)
    server = uvicorn.Server(uvicorn.Config(app, host="0.0.0.0", port=config.http_port, log_level="info"))

    def stop(signum: int, frame: Any) -> None:
        runner.stopping.set()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    probes = threading.Thread(target=server.run, name="docproc-probes", daemon=True)
    probes.start()
    try:
        runner.bootstrap()
        runner.run()
    finally:
        runner.stopping.set()
        server.should_exit = True
        probes.join(timeout=5)
        bus.close()
        warehouse.close()
        manager.close()


if __name__ == "__main__":
    main()
