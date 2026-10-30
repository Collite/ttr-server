# SPDX-License-Identifier: Apache-2.0
from pathlib import Path

import pytest

from docproc.config import load_config


def environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("PIPELINE_DB_URL", "postgresql://secret@localhost/pipeline")
    monkeypatch.setenv("DOCWH_STORE_URL", "http://store")
    monkeypatch.setenv("DOCWH_PIPELINE_URL", "http://pipeline")


def test_defaults_and_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    environment(monkeypatch)
    cfg = load_config()
    assert (cfg.pool, cfg.families, cfg.http_port, cfg.vt_slack_s, cfg.batch) == ("cpu", (), 7310, 30, 1)
    assert "secret" not in repr(cfg)
    monkeypatch.setenv("DOCPROC_POOL", "nlp")
    monkeypatch.setenv("DOCPROC_FAMILIES", "one,two")
    monkeypatch.setenv("DOCPROC_HTTP_PORT", "7311")
    monkeypatch.setenv("TTR_CONN_EXTRACT_RECORDS", "world-secret")
    cfg = load_config()
    assert cfg.pool == "nlp" and cfg.families == ("one", "two") and cfg.http_port == 7311
    assert cfg.world_db_url == "world-secret" and "world-secret" not in repr(cfg)


def test_missing_key_is_named(tmp_path: Path) -> None:
    path = tmp_path / "application.conf"
    path.write_text("docproc { pool = cpu }")
    with pytest.raises(ValueError, match="docproc.pipeline.db.url"):
        load_config(path)


def test_bad_poll_config(monkeypatch: pytest.MonkeyPatch) -> None:
    environment(monkeypatch)
    monkeypatch.setenv("DOCPROC_POLL_BATCH", "0")
    with pytest.raises(ValueError, match="invalid docproc"):
        load_config()
