# SPDX-License-Identifier: Apache-2.0
"""Phase 2.4 §C+§D — config loads + capabilities surface is correct."""

from __future__ import annotations

from pathlib import Path

import pytest

from workers_polars.config import load_config
from workers_polars.grpc_service import make_capabilities, make_status


@pytest.fixture
def cfg():
    """Load the bundled `conf/application.conf` from the worker root."""
    repo_root = Path(__file__).resolve().parents[1]
    return load_config(repo_root / "conf" / "application.conf")


def test_config_loads_with_expected_defaults(cfg):
    # Ports match conf/application.conf and the deployed worker env
    # (POLARS_SERVER_GRPC_PORT=7301 / POLARS_SERVER_PORT=7300).
    assert cfg.grpc.port == 7301
    assert cfg.http.port == 7300
    assert cfg.workspace.idle_ttl_seconds == 3600
    assert cfg.workspace.max_dfs_per_session == 50
    assert cfg.capability.engine_name == "polars"
    assert cfg.capability.supports_stateful_sessions is True


def test_capabilities_advertises_polars_and_stateful(cfg):
    caps = make_capabilities(cfg)
    assert caps.engine_name == "polars"
    assert caps.supports_stateful_sessions is True
    assert caps.max_concurrent_sessions == cfg.capability.max_concurrent_sessions
    # Polars Worker doesn't speak SQL dialects or have ERP-side connections.
    assert list(caps.supported_dialects) == []
    assert list(caps.supported_connections) == []
    assert caps.limits.max_row_limit == cfg.limits.max_rows_per_result


def test_status_defaults_ready(cfg):
    status = make_status()
    assert status.ready is True
    assert status.active_sessions == 0
