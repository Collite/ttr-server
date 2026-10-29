# SPDX-License-Identifier: Apache-2.0
import httpx
import psycopg
import pytest

from docproc.runtime.failures import diagnostic, transient
from docproc.sdk.store import StoreError


@pytest.mark.parametrize(
    "status,expected", [(401, True), (429, True), (503, True), (404, False), (403, False), (422, False)]
)
def test_store_failure_classification(status: int, expected: bool) -> None:
    exc = StoreError(status, "test/code", "SECRET")
    assert transient(exc) is expected
    assert diagnostic(exc) == f"StoreError status={status} code=test/code"


@pytest.mark.parametrize(
    "exc", [httpx.ConnectError("SECRET"), httpx.ReadTimeout("SECRET"), psycopg.OperationalError("SECRET")]
)
def test_transport_failure_classification(exc: Exception) -> None:
    assert transient(exc) and "SECRET" not in diagnostic(exc)
    assert not transient(ValueError("registry/version-mismatch"))
