# SPDX-License-Identifier: Apache-2.0
import json
import os
import time
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest
import requests

Json = dict[str, Any]


class LiveOm:
    def __init__(self, url: str, token: str) -> None:
        self.url = url.rstrip("/")
        self.token = token
        self.session = requests.Session()
        self.session.headers["Authorization"] = f"Bearer {token}"

    def call(self, method: str, path: str, **kwargs: Any) -> Json:
        response = self.session.request(method, f"{self.url}/v1/{path}", timeout=60, **kwargs)
        assert response.ok, f"{method} {path}: {response.status_code} {response.text}"
        result: Json = response.json()
        return result

    def entities(self, path: str, fields: str = "") -> list[Json]:
        result: list[Json] = []
        after = None
        while True:
            params = {"limit": 100, "fields": fields}
            if after:
                params["after"] = after
            page = self.call("GET", path, params=params)
            result.extend(page["data"])
            after = page.get("paging", {}).get("after")
            if not after:
                return result

    def settle_initial_approvals(self) -> None:
        # Observe actual completion, plus a quiet interval for batched event delivery.
        previous: set[str] = set()
        quiet_since = time.monotonic()
        deadline = quiet_since + 90
        while time.monotonic() < deadline:
            page = self.call(
                "GET",
                "governance/workflowInstances",
                params={
                    "startTs": 0,
                    "endTs": int(time.time() * 1000),
                    "limit": 1000000,
                    "workflowDefinitionName": "GlossaryTermApprovalWorkflow",
                },
            )
            instances = page["data"]
            current = {i["id"] for i in instances}
            assert len(instances) == page["paging"]["total"]
            if current != previous or any(i["status"] != "FINISHED" for i in instances):
                quiet_since = time.monotonic()
                previous = current
            if current and time.monotonic() - quiet_since >= 10:
                self.record("approvals", {"finished": len(current), "quietSeconds": 10})
                return
            time.sleep(1)
        pytest.fail("initial approval workflows did not finish and become quiet")

    def record(self, name: str, value: Json) -> None:
        directory = Path(os.environ.get("OM_RESULTS_DIR", "/tmp/xm-om-results"))
        directory.mkdir(parents=True, exist_ok=True)
        with (directory / f"{name}.jsonl").open("a") as output:
            output.write(json.dumps(value, ensure_ascii=False, sort_keys=True) + "\n")


@pytest.fixture(scope="session")
def om() -> Iterator[LiveOm]:
    url, token = os.environ.get("OM_URL"), os.environ.get("OM_TOKEN")
    if not url or not token:
        pytest.skip("set OM_URL and OM_TOKEN for the disposable local OM conformance lane")
    client = LiveOm(url, token)
    assert client.call("GET", "system/version")["version"].startswith("2.0.6")
    yield client
    client.session.close()
