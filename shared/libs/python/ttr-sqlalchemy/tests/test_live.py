# SPDX-License-Identifier: Apache-2.0
"""BI (S2.1 T5) — against a real edge. Skipped unless TTR_FLIGHT_URL is set, e.g. through a port-forward:

    kubectl -n <namespace> port-forward deploy/query 7308:7308
    TTR_FLIGHT_URL=localhost:7308 TTR_FLIGHT_USER=petr TTR_FLIGHT_PASSWORD=… uv run pytest -m live -v

`TTR_FLIGHT_TLS=true` for the gateway route (`flight.<cluster host>:443`); `TTR_FLIGHT_TOKEN` instead of a user and
password; `TTR_FLIGHT_EXPECT_WAREHOUSES` = how many `er.entity.warehouse` rows the user's row rules leave in
`hartland_cz` (1 for petr on hartland), checked when set.
"""

from __future__ import annotations

import os
import urllib.parse

import pytest
import sqlalchemy as sa

pytestmark = pytest.mark.live


def _engine(live_url: str, world: str) -> sa.engine.Engine:
    tls = os.environ.get("TTR_FLIGHT_TLS", "false")
    user = os.environ.get("TTR_FLIGHT_USER")
    credentials = ""
    connect_args = {}
    if os.environ.get("TTR_FLIGHT_TOKEN"):
        connect_args["token"] = os.environ["TTR_FLIGHT_TOKEN"]
    elif user:
        password = urllib.parse.quote(os.environ.get("TTR_FLIGHT_PASSWORD", ""), safe="")
        credentials = f"{urllib.parse.quote(user, safe='')}:{password}@"
    return sa.create_engine(f"tatrman://{credentials}{live_url}/{world}?tls={tls}", connect_args=connect_args)


def test_live_reflection_lists_the_three_schemas(live_url):
    engine = _engine(live_url, "hartland_cz")
    try:
        inspector = sa.inspect(engine)
        assert inspector.get_schema_names() == ["er.entity", "obj.query", "db.dbo"]
        tables = inspector.get_table_names("er.entity")
        assert "warehouse" in tables
        columns = [c["name"] for c in inspector.get_columns("warehouse", schema="er.entity")]
        assert columns, "warehouse has no columns"
        assert inspector.get_table_names("db.dbo"), "no bound tables in db.dbo"
    finally:
        engine.dispose()


def test_live_a_select_reads_the_users_rows(live_url):
    engine = _engine(live_url, "hartland_cz")
    try:
        with engine.connect() as conn:
            result = conn.exec_driver_sql("SELECT * FROM er.entity.warehouse")
            rows = result.fetchall()
            cursor = result.cursor
            assert cursor.correlation_id
            assert cursor.connection_id
        expected = os.environ.get("TTR_FLIGHT_EXPECT_WAREHOUSES")
        if expected is not None:
            assert len(rows) == int(expected)
        else:
            assert rows
    finally:
        engine.dispose()


def test_live_the_other_world_reads_its_own_connection(live_url):
    cz, us = _engine(live_url, "hartland_cz"), _engine(live_url, "hartland_us")
    try:
        with cz.connect() as a, us.connect() as b:
            ra = a.exec_driver_sql("SELECT * FROM er.entity.warehouse")
            ra.fetchall()
            rb = b.exec_driver_sql("SELECT * FROM er.entity.warehouse")
            assert rb.fetchall(), "no US rows"
            assert ra.cursor.connection_id != rb.cursor.connection_id
    finally:
        cz.dispose()
        us.dispose()
