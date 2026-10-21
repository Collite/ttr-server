# SPDX-License-Identifier: Apache-2.0
"""Compiles the official FlightSql.proto for the recorded edge, and serves one edge per test session."""

from __future__ import annotations

import os
import pathlib

import pytest

HERE = pathlib.Path(__file__).parent
PROTO = HERE / "fixtures" / "vendor"  # third-party: Apache Arrow's FlightSql.proto, verbatim
GENERATED = HERE / "fixtures" / "generated"


def _compile_flight_sql() -> None:
    target = GENERATED / "FlightSql_pb2.py"
    source = PROTO / "FlightSql.proto"
    if target.exists() and target.stat().st_mtime >= source.stat().st_mtime:
        return
    import grpc_tools
    from grpc_tools import protoc

    GENERATED.mkdir(exist_ok=True)
    (GENERATED / "__init__.py").touch()
    include = pathlib.Path(grpc_tools.__file__).parent / "_proto"
    status = protoc.main(["protoc", f"-I{PROTO}", f"-I{include}", f"--python_out={GENERATED}", str(source)])
    if status != 0:
        raise RuntimeError(f"protoc failed ({status}) on {source}")


_compile_flight_sql()


@pytest.fixture(scope="session")
def edge():
    from tests.fixtures.edge import RecordedEdge

    server = RecordedEdge()
    yield server
    server.shutdown()


@pytest.fixture
def calls(edge):
    """The edge's call log, emptied for each test."""
    edge.calls.clear()
    return edge.calls


@pytest.fixture
def live_url():
    url = os.environ.get("TTR_FLIGHT_URL")
    if not url:
        pytest.skip("live: set TTR_FLIGHT_URL (and TTR_FLIGHT_TOKEN or TTR_FLIGHT_USER/TTR_FLIGHT_PASSWORD)")
    return url
