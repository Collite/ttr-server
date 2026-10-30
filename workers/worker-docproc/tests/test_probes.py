# SPDX-License-Identifier: Apache-2.0
from fastapi.testclient import TestClient

from docproc.probes import make_probes_app, runs


def test_readiness_status_metrics() -> None:
    state = {"ready": False}
    client = TestClient(make_probes_app(lambda: state["ready"], lambda: {"pool": "cpu", "families": []}))
    assert client.get("/health").status_code == 200
    assert client.get("/ready").status_code == 503
    state["ready"] = True
    assert client.get("/ready").status_code == 200
    assert client.get("/status").json() == {"pool": "cpu", "families": []}
    runs.labels("probe-test", "SUCCEEDED").inc()
    assert 'docproc_runs_total{family="probe-test",status="SUCCEEDED"}' in client.get("/metrics").text
