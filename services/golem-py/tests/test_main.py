# SPDX-License-Identifier: Apache-2.0
"""OL W1c·S3·T3 — the CLI takes the lane's settings, checks them before the turn, and
`--json` prints the envelope.

No network: the live clients are constructed (which opens no channel) but never called, and
the one end-to-end turn here runs over the recorded fakes of the lane spec.
"""

from __future__ import annotations

import dataclasses
import json
from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest

import main
from golem_py.door_client import LiveSqlDoor
from golem_py.errors import ConfigInvalid
from golem_py.graph import run_turn
from golem_py.ladder import load_default
from golem_py.lane import lane_on
from golem_py.llm_client import LiveLlmGateway
from golem_py.model_slice import LiveModelSlice
from golem_py.outputs import Answer, RefusalWithGaps
from golem_py.query_client import QueryResult, sql_sha256
from golem_py.settings import GolemSettings
from tests.helpers import h1_lattice
from tests.test_llm_lane import (
    PERIOD,
    PERIOD_SQL,
    SUBJECT,
    FakeDoor,
    FakeGateway,
    FakeSlice,
    _state,
    lane_deps,
    period_lattice,
    reply,
)

LANE_ENV = {
    "GOLEM_QUERY_TARGET": "query:7306",
    "GOLEM_QUERY_WORLD": "hartland_cz",
    "GOLEM_LLM_TARGET": "llm-gateway:9090",
    "GOLEM_LLM_MODEL": "gw-capable-1",
    "GOLEM_META_TARGET": "metadata:7261",
    "GOLEM_META_PACKAGES": "hartland",
}


def _settings(monkeypatch: pytest.MonkeyPatch, **env: str) -> GolemSettings:
    for name in list(LANE_ENV) + ["GOLEM_LLM_LANE_ENABLED", "GOLEM_PROFILE", "GOLEM_LADDER_CONFIG"]:
        monkeypatch.delenv(name, raising=False)
    for name, value in env.items():
        monkeypatch.setenv(name, value)
    return GolemSettings.from_env()


def test_with_no_lane_settings_the_cli_builds_no_lane(monkeypatch: pytest.MonkeyPatch) -> None:
    deps = main.build_deps(_settings(monkeypatch), load_default(), "resolver:7276")

    assert deps.door is None and deps.llm is None and deps.model_slice is None
    assert not lane_on(deps)
    main.check_startup(deps, "CHAT_QUICK")  # a lane that is off has nothing to check


def test_the_lane_settings_reach_the_three_live_legs(monkeypatch: pytest.MonkeyPatch) -> None:
    deps = main.build_deps(_settings(monkeypatch, **LANE_ENV), load_default(), "resolver:7276")

    assert isinstance(deps.door, LiveSqlDoor) and deps.door.target == "query:7306"
    assert isinstance(deps.llm, LiveLlmGateway) and deps.llm.target == "llm-gateway:9090"
    assert deps.llm.model == "gw-capable-1" and deps.llm.model_class == "capable"
    assert isinstance(deps.model_slice, LiveModelSlice)
    assert deps.settings.query_world == "hartland_cz"
    assert lane_on(deps)
    main.check_startup(deps, "CHAT_QUICK")


def test_the_switch_turns_a_configured_lane_off(monkeypatch: pytest.MonkeyPatch) -> None:
    settings = _settings(monkeypatch, **LANE_ENV, GOLEM_LLM_LANE_ENABLED="false")
    deps = main.build_deps(settings, load_default(), "resolver:7276")

    assert not lane_on(deps)


@pytest.mark.parametrize(
    ("drop", "message"),
    [
        ("GOLEM_QUERY_WORLD", "GOLEM_QUERY_WORLD"),
        ("GOLEM_META_TARGET", "model slice"),
        ("GOLEM_META_PACKAGES", "model slice"),
    ],
)
def test_a_lane_that_is_on_without_what_it_needs_is_refused_before_the_turn(
    monkeypatch: pytest.MonkeyPatch, drop: str, message: str
) -> None:
    env = {k: v for k, v in LANE_ENV.items() if k != drop}
    deps = main.build_deps(_settings(monkeypatch, **env), load_default(), "resolver:7276")

    with pytest.raises(ConfigInvalid, match=message):
        main.check_startup(deps, "CHAT_QUICK")


def test_the_startup_check_runs_check_lane_config(monkeypatch: pytest.MonkeyPatch) -> None:
    """review-329 F3/F4: `check_lane_config` existed with no call site; the CLI is it."""
    # The shipped ladder (its profiles intact) with no clock for the `capable` rung.
    no_clock = load_default().model_copy(update={"rungs": {}, "rung_timeouts_ms": {}})
    deps = main.build_deps(_settings(monkeypatch, **LANE_ENV), no_clock, "resolver:7276")

    with pytest.raises(ConfigInvalid, match=r"rungs\.capable\.timeout_ms"):
        main.check_startup(deps, "CHAT_QUICK")


def test_a_bad_configuration_exits_2_before_any_turn(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    env = {k: v for k, v in LANE_ENV.items() if k != "GOLEM_QUERY_WORLD"}
    _settings(monkeypatch, **env)

    async def no_turn(*_: object, **__: object) -> None:
        raise AssertionError("a turn ran on an invalid configuration")

    monkeypatch.setattr(main, "run_turn", no_turn)

    assert main.main(["--question", "Tržby za minulý měsíc"]) == main.EXIT_CONFIG
    assert "GOLEM_QUERY_WORLD" in capsys.readouterr().err


async def test_json_prints_the_lane_envelope_with_rows_that_are_not_json_natives() -> None:
    rows = QueryResult(
        rows=[{"month": date(2026, 10, 1), "revenue": Decimal("1234.50")}],
        columns=["month", "revenue"],
    )
    gateway = FakeGateway(reply(PERIOD_SQL, [[22, 34]], note="October 2026"))
    lattice = period_lattice()
    d = lane_deps(lattice, llm=gateway, door=FakeDoor(rows), model_slice=FakeSlice())
    out = await run_turn(_state(lattice), dataclasses.replace(d))
    assert isinstance(out, Answer)

    doc = json.loads(json.dumps(main.envelope_json(out), default=main._default))

    assert doc["type"] == "Answer" and doc["llm_invocations"] == 1
    assert doc["sql"] == PERIOD_SQL
    assert doc["provenance"]["lane"] == "llm"
    assert doc["provenance"]["llm_model"] == "gw-capable-1"
    assert doc["provenance"]["sql_sha256"] == sql_sha256(PERIOD_SQL)
    assert doc["row_count"] == 1 and doc["columns"] == ["month", "revenue"]
    assert doc["rows"] == [{"month": "2026-10-01", "revenue": "1234.50"}]
    assert [s["text"] for s in doc["llm_handled"]] == [PERIOD.text]
    assert [g["span"]["text"] for g in doc["gaps_carried"]] == [PERIOD.text]
    assert "lattice" not in doc


async def test_json_prints_a_refusal_without_its_lattice() -> None:
    gateway = FakeGateway(reply("SELECT 1 FROM er.entity.customer"))
    d = lane_deps(h1_lattice(), llm=gateway, door=FakeDoor(), model_slice=FakeSlice())
    out = await run_turn(_state(h1_lattice()), d)
    assert isinstance(out, RefusalWithGaps)

    doc = json.loads(json.dumps(main.envelope_json(out), default=main._default))

    assert doc["type"] == "RefusalWithGaps"
    assert doc["reason"] == "QUERY_NOT_WRITTEN"
    assert "outside_slice" in doc["explanation"]
    assert "lattice" not in doc


def test_the_subject_flag_is_the_callers_subject() -> None:
    """The door runs under the subject the CLI was given (OL-C12) — the Job passes the
    persona's; there is no default identity."""
    args = main._parse_args(["--question", "q", "--subject", SUBJECT, "--json"])
    assert args.subject == SUBJECT and args.json


# review-343 C1 — a ladder file that cannot be read is a configuration error: exit 2.
@pytest.mark.parametrize("problem", ["missing", "not yaml", "not a ladder"])
def test_a_bad_ladder_file_exits_2_naming_it(
    problem: str,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    _settings(monkeypatch)
    path = tmp_path / "ladder.yaml"
    if problem == "not yaml":
        path.write_text("rungs: [unclosed\n")
    elif problem == "not a ladder":
        path.write_text("schema: something/else\n")

    async def no_turn(*_: object, **__: object) -> None:
        raise AssertionError("a turn ran on an invalid configuration")

    monkeypatch.setattr(main, "run_turn", no_turn)

    assert main.main(["--question", "q", "--ladder", str(path)]) == main.EXIT_CONFIG
    assert "the ladder file" in capsys.readouterr().err


def test_a_missing_default_ladder_file_exits_2(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _settings(monkeypatch)
    monkeypatch.setenv("GOLEM_LADDER_CONFIG", str(tmp_path / "nonexistent.yaml"))

    assert main.main(["--question", "q"]) == main.EXIT_CONFIG
    assert "nonexistent.yaml" in capsys.readouterr().err


# review-343 C2 — an unknown profile is refused before any port is called, lane on or off.
@pytest.mark.parametrize("lane", [False, True])
def test_an_unknown_profile_exits_2_before_any_port_is_called(
    lane: bool, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _settings(monkeypatch, **(LANE_ENV if lane else {}))

    class FailingCore:
        """Stands in for the resolver: any call fails the test."""

        def __init__(self, *_: object, **__: object) -> None:
            pass

        async def resolve(self, **_: object) -> None:
            raise AssertionError("the core was called for a profile that does not exist")

    monkeypatch.setattr(main, "LiveCore", FailingCore)

    code = main.main(["--question", "q", "--profile", "CHAT_FAST"])

    err = capsys.readouterr().err
    assert code == main.EXIT_CONFIG
    assert "CHAT_FAST" in err and "CHAT_QUICK" in err


def test_the_profile_comes_from_the_environment_when_no_flag_is_given(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _settings(monkeypatch, GOLEM_PROFILE="NO_SUCH_PROFILE")

    async def no_turn(*_: object, **__: object) -> None:
        raise AssertionError("a turn ran on an unknown profile")

    monkeypatch.setattr(main, "run_turn", no_turn)

    assert main.main(["--question", "q"]) == main.EXIT_CONFIG
    assert "NO_SUCH_PROFILE" in capsys.readouterr().err
