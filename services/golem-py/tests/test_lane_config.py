# SPDX-License-Identifier: Apache-2.0
"""review-329 F3/F4 — what a lane that is on needs fails at STARTUP, not on its first turn."""

from __future__ import annotations

from pathlib import Path

import pytest

from golem_py import lane_prompt
from golem_py.errors import ConfigInvalid
from golem_py.ladder import LadderConfig, load_default
from golem_py.lane import check_lane_config


def _ladder(**capable: object) -> LadderConfig:
    return LadderConfig.model_validate({"rung_timeouts_ms": capable})


def test_the_shipped_ladder_and_prompt_pass() -> None:
    check_lane_config(load_default())


def test_a_ladder_with_no_clock_for_the_capable_rung_is_refused_at_startup() -> None:
    with pytest.raises(ConfigInvalid, match=r"rungs\.capable\.timeout_ms"):
        check_lane_config(_ladder())


def test_a_missing_prompt_file_is_refused_at_startup(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(lane_prompt, "DEFAULT_PROMPT_PATH", tmp_path / "absent.md")
    with pytest.raises(ConfigInvalid, match="prompt file"):
        check_lane_config(_ladder(capable=10000))


def test_a_prompt_file_without_its_repair_part_is_refused_at_startup(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    broken = tmp_path / "prompt.md"
    broken.write_text("<!-- instructions -->\nWrite SQL.\n<!-- input -->\n{{question}}\n")
    monkeypatch.setattr(lane_prompt, "DEFAULT_PROMPT_PATH", broken)
    with pytest.raises(ConfigInvalid, match="repair"):
        check_lane_config(_ladder(capable=10000))
