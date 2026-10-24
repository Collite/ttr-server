# SPDX-License-Identifier: Apache-2.0
import json
from pathlib import Path

import pytest
import responses

from ttr_connect_openmetadata.cli import main
from ttr_connect_openmetadata.state import State, write_state

FIXTURES = Path(__file__).parent / "fixtures"


def test_plan_cli_matches_golden(capsys: pytest.CaptureFixture[str]) -> None:
    assert main(["plan", "--export-dir", str(FIXTURES / "export"), "--cn-path", "owl"]) == 0
    output = capsys.readouterr()
    assert [json.loads(line) for line in output.out.splitlines()] == json.loads(
        (FIXTURES / "plan/world.plan.json").read_text()
    )
    assert output.err == ""


def test_plan_does_not_advance_state(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    state_path = tmp_path / "state.json"
    write_state(state_path, State(snapshot="before", ids=["cn.kind.Old"], om_version="2.0.6"))
    original = state_path.read_bytes()
    args = ["plan", "--export-dir", str(FIXTURES / "export"), "--state-file", str(state_path)]
    assert main(args) == 0
    ops = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    assert ops[-1] == {"op": "Deprecate", "kind": "glossaryTerm", "qname": "cn.kind.Old"}
    assert state_path.read_bytes() == original
    assert main([*args, "--no-deprecate-missing"]) == 0
    assert '"op":"Deprecate"' not in capsys.readouterr().out
    assert state_path.read_bytes() == original


def test_cli_304_and_export_error(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    path = tmp_path / "state.json"
    write_state(path, State(snapshot="fixture-1", ids=[], om_version="2.0.6"))
    url = "http://metadata.example/model/export"
    with responses.RequestsMock() as mock:
        mock.get(url, status=304)
        assert main(["plan", "--export-url", url, "--state-file", str(path)]) == 0
        assert capsys.readouterr().out == ""
    with responses.RequestsMock() as mock:
        mock.get(url, status=304)
        assert main(["plan", "--export-url", url]) == 2
        assert "export error" in capsys.readouterr().err
    assert main(["plan", "--export-dir", str(tmp_path / "missing")]) == 2


def test_mapping_exit(capsys: pytest.CaptureFixture[str]) -> None:
    assert main(["plan", "--export-dir", str(FIXTURES / "export"), "--world", "wrong"]) == 3
    assert "mapping error" in capsys.readouterr().err


@pytest.mark.parametrize("command", ["check", "sync"])
def test_writer_commands_require_arguments(command: str) -> None:
    with pytest.raises(SystemExit) as error:
        main([command])
    assert error.value.code == 2


def test_cli_requires_exactly_one_source() -> None:
    with pytest.raises(SystemExit) as error:
        main(["plan"])
    assert error.value.code == 2
    with pytest.raises(SystemExit):
        main(["plan", "--export-dir", ".", "--export-url", "http://example.org/model/export"])


def test_default_plan_does_not_schedule_rdf_import(capsys: pytest.CaptureFixture[str]) -> None:
    assert main(["plan", "--export-dir", str(FIXTURES / "export")]) == 0
    operations = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    assert operations
    assert not any(op["op"] == "ImportRdf" for op in operations)
