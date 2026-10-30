# SPDX-License-Identifier: Apache-2.0
"""Frozen HOCON configuration. Connection strings are excluded from repr/logging."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

from pyhocon import ConfigFactory


@dataclass(frozen=True)
class Config:
    pool: str
    pipeline_db_url: str = field(repr=False)
    store_url: str
    pipeline_url: str
    families: tuple[str, ...] = ()
    pack_path: str = ""
    nlp_grpc: str = ""
    lexmatcher_grpc: str = ""
    gateway_url: str = ""
    world_db_url: str = field(default="", repr=False)
    http_port: int = 7310
    vt_slack_s: int = 30
    idle_ms: int = 500
    batch: int = 1
    max_deliveries: int = 10
    telemetry_enabled: bool = False
    otlp_endpoint: str = "http://localhost:4317"


def load_config(path: str | Path | None = None) -> Config:
    target = Path(path or os.environ.get("DOCPROC_CONFIG", "conf/application.conf"))
    if not target.is_absolute() and not target.exists():
        target = Path(__file__).resolve().parents[2] / target
    tree = ConfigFactory.parse_file(str(target)).get_config("docproc")

    def required(key: str) -> str:
        value = str(tree.get(key, ""))
        if not value:
            raise ValueError(f"missing required config docproc.{key}")
        return value

    families = tree.get("families", [])
    if isinstance(families, str):
        families = [s.strip() for s in families.split(",") if s.strip()]
    cfg = Config(
        pool=required("pool"),
        pipeline_db_url=required("pipeline.db.url"),
        store_url=required("docwh.store.url"),
        pipeline_url=required("docwh.pipeline.url"),
        families=tuple(families),
        pack_path=tree.get("pack.path", ""),
        nlp_grpc=tree.get("nlp.grpc", ""),
        lexmatcher_grpc=tree.get("lexmatcher.grpc", ""),
        gateway_url=tree.get("gateway.url", ""),
        world_db_url=tree.get("world.db.url", ""),
        http_port=int(tree.get("http.port", 7310)),
        vt_slack_s=int(tree.get("vt-slack-s", 30)),
        idle_ms=int(tree.get("poll.idle-ms", 500)),
        batch=int(tree.get("poll.batch", 1)),
        max_deliveries=int(tree.get("max-deliveries", 10)),
        telemetry_enabled=tree.get_bool("telemetry.enabled", False),
        otlp_endpoint=tree.get("telemetry.otlp.endpoint", "http://localhost:4317"),
    )
    if (
        cfg.max_deliveries < 1
        or cfg.batch < 1
        or cfg.idle_ms < 1
        or cfg.vt_slack_s < 0
        or not 1 <= cfg.http_port <= 65535
    ):
        raise ValueError("invalid docproc poll, vt-slack-s or http.port")
    return cfg
