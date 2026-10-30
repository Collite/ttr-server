# SPDX-License-Identifier: Apache-2.0
"""Shared fixtures: the repo paths, the cross-repo fixture directory, a pgmq Postgres."""

from __future__ import annotations

import os
from collections.abc import Iterator
from pathlib import Path

import psycopg
import pytest

WORKER = Path(__file__).resolve().parents[1]
REPO = WORKER.parents[1]
CONTRACTS = WORKER / "tests" / "fixtures" / "contracts"
# The D-130 install this repo already vendors (sha-checked by the automation service's PgmqVendorSpec).
PGMQ_SQL = REPO / "services" / "automation" / "src" / "main" / "resources" / "db" / "migration" / "V2__pgmq_1_13_0.sql"


def kantheon_dir() -> Path | None:
    """The kantheon checkout beside this one (KANTHEON_DIR, else ../kantheon), for the cross-repo identity checks."""
    env = os.environ.get("KANTHEON_DIR")
    candidate = Path(env) if env else REPO.parent / "kantheon"
    return candidate if (candidate / "shared" / "proto" / "fixtures" / "dx").is_dir() else None


@pytest.fixture(scope="session")
def pg_url() -> Iterator[str]:
    from testcontainers.community.postgres import PostgresContainer

    with PostgresContainer("postgres:18-alpine", driver=None) as pg:
        url = pg.get_connection_url()
        with psycopg.connect(url, autocommit=True) as c:
            c.execute(PGMQ_SQL.read_text(encoding="utf-8"))  # no parameters → the simple protocol, many statements
        yield url


@pytest.fixture(scope="session")
def world_db() -> Iterator[str]:
    from testcontainers.community.postgres import PostgresContainer

    from tests.fakes import PACK

    with PostgresContainer("postgres:18-alpine", driver=None) as pg:
        url = pg.get_connection_url()
        with psycopg.connect(url, autocommit=True) as conn:
            conn.execute((PACK / "records/ddl/review.sql").read_text())
            # Frozen PX DDL predates the fixed engine columns in S3.3 T6.
            # An explicit test-only migration demonstrates the required deployment delta.
            conn.execute((WORKER / "tests/fixtures/sql/record_columns.sql").read_text())
        yield url
