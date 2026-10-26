# SPDX-License-Identifier: Apache-2.0
import json
from pathlib import Path

import pytest
import responses

from ttr_connect_openmetadata.cli import main
from ttr_connect_openmetadata.model import Export
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


@pytest.mark.parametrize("existing", [False, True])
@pytest.mark.parametrize("missing", ["table", "column"])
def test_skipped_assets_preserve_state_then_retry(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    export: Export,
    existing: bool,
    missing: str,
) -> None:
    from copy import deepcopy

    from om_fake import FakeOm
    from test_sync import sync_args

    fake = FakeOm(export)
    name = "northline-pg.northline.retail.sales"
    saved = deepcopy(fake.stores["tables"][name])
    if missing == "table":
        del fake.stores["tables"][name]
    else:
        fake.stores["tables"][name]["columns"] = []
    path = tmp_path / "state.json"
    if existing:
        write_state(path, State(snapshot="older", ids=["cn.kind.Old"], om_version="2.0.6"))
    before = path.read_bytes() if existing else None
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        for _ in range(2):
            assert main(sync_args(tmp_path)) == 0
            output = capsys.readouterr()
            assert json.loads(output.out)["warnings"] >= 1
            assert "WARN state not advanced:" in output.err
            assert (path.read_bytes() if path.exists() else None) == before
        fake.stores["tables"][name] = saved
        assert main(sync_args(tmp_path)) == 0
        output = json.loads(capsys.readouterr().out)
        assert output["warnings"] == 0 and output["linked"] > 0
        assert json.loads(path.read_text())["options"]
        assert json.loads(path.read_text())["snapshot"] == "fixture-1"


@pytest.mark.parametrize(
    "change",
    [
        None,
        "studio-url",
        "studio-workspace",
        "same-workspace",
        "same-origin",
        "cn-name-mode",
        "cn-path",
        "world",
        "no-deprecate-missing",
        "om-version",
        "connector-version",
        "legacy",
    ],
)
def test_conditional_export_respects_options(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    export: Export,
    monkeypatch: pytest.MonkeyPatch,
    change: str | None,
) -> None:
    from om_fake import FakeOm
    from test_export_reader import URL, register_export
    from test_sync import sync_args

    fake = FakeOm(export)
    args = sync_args(tmp_path)
    index = args.index("--export-dir")
    args[index : index + 2] = ["--export-url", URL]
    path = tmp_path / "state.json"
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        register_export(mock)
        assert main(args) == 0
        assert json.loads(capsys.readouterr().out)["not_modified"] is False
        before = path.read_bytes()
        if change == "legacy":
            state = json.loads(path.read_text())
            del state["options"]
            path.write_text(json.dumps(state))
        elif change == "om-version":
            from ttr_connect_openmetadata.om_client import OmClient

            original = OmClient.read_version

            def upgraded(client: OmClient) -> str:
                original(client)
                client.version = "2.0.7"
                return client.version

            monkeypatch.setattr(OmClient, "read_version", upgraded)
        elif change == "connector-version":
            monkeypatch.setattr(
                "ttr_connect_openmetadata.cli.version", lambda _: "next-connector-build"
            )
        elif change == "no-deprecate-missing":
            args += ["--no-deprecate-missing"]
        elif change:
            value = {
                "studio-url": "https://new-studio.example",
                "studio-workspace": "catalog-demo",
                "same-workspace": export.graph.world,
                "same-origin": "https://studio.example/",
                "cn-name-mode": "qname",
                "cn-path": "owl",
                "world": export.graph.world,
            }[change]
            flag = "--" + {"same-workspace": "studio-workspace", "same-origin": "studio-url"}.get(
                change, change
            )
            if flag in args:
                args[args.index(flag) + 1] = value
            else:
                args += [flag, value]
        fake.calls.clear()
        mock.reset()
        fake.install(mock)
        register_export(mock)
        equivalent = change in {None, "same-workspace", "same-origin", "world"}
        if equivalent:
            # Replace the export route; the OM version probe must still run.
            mock.replace(responses.GET, URL, status=304)
        assert main(args) == 0
        output = json.loads(capsys.readouterr().out)
        export_calls = [c for c in mock.calls if c.request.url and c.request.url.startswith(URL)]
        assert bool(export_calls)
        assert all(("If-None-Match" in c.request.headers) == equivalent for c in export_calls)
        assert output["not_modified"] is equivalent
        if equivalent:
            assert fake.calls == [("GET", "system/version", None)]
            assert path.read_bytes() == before
            assert all(
                output[k] == 0 for k in ("created", "updated", "linked", "deprecated", "warnings")
            )
        else:
            assert json.loads(path.read_text())["options"]
            if change == "studio-url":
                assert all(
                    t["extension"]["ttrUrl"].startswith("https://new-studio.example/")
                    for t in fake.stores["glossaryTerms"].values()
                    if not t["extension"]["ttrQname"].startswith("cn.")
                )
                for term in fake.stores["glossaryTerms"].values():
                    if term["extension"]["ttrQname"].startswith("cn."):
                        assert "ttrUrl" not in term["extension"]
                        assert "ttrStudioLink" not in term["extension"]


def test_retry_recomputes_deprecations_without_repatching(
    tmp_path: Path, capsys: pytest.CaptureFixture[str], export: Export
) -> None:
    from copy import deepcopy

    from om_fake import FakeOm, uid
    from test_sync import sync_args

    fake = FakeOm(export)
    args = sync_args(tmp_path)
    path = tmp_path / "state.json"
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main(args) == 0
        capsys.readouterr()
        stale = deepcopy(next(iter(fake.stores["glossaryTerms"].values())))
        stale.update(
            id=uid("old-term"),
            name="Old",
            fullyQualifiedName="northline Entities.Old",
            extension={"ttrQname": "er.entity.Old"},
        )
        stale.pop("parent", None)
        stale["entityStatus"] = "Draft"
        fake.stores["glossaryTerms"][stale["fullyQualifiedName"]] = stale
        state = json.loads(path.read_text())
        state["ids"].append("er.entity.Old")
        path.write_text(json.dumps(state))
        before = path.read_bytes()
        missing = fake.stores["tables"].pop("northline-pg.northline.retail.sales")
        for expected in [1, 0]:
            assert main(args) == 0
            assert json.loads(capsys.readouterr().out)["deprecated"] == expected
            assert path.read_bytes() == before
        fake.stores["tables"][missing["fullyQualifiedName"]] = missing
        assert main(args) == 0
        assert json.loads(capsys.readouterr().out)["deprecated"] == 0
        assert path.read_bytes() != before


@pytest.mark.parametrize("command", ["plan", "sync"])
def test_workspace_requires_studio_origin(command: str) -> None:
    with pytest.raises(SystemExit) as error:
        main([command, "--export-dir", str(FIXTURES / "export"), "--studio-workspace", "x"])
    assert error.value.code == 2


@pytest.mark.parametrize("workspace", ["", ".."])
def test_invalid_workspace_cannot_short_circuit_on_saved_export(
    tmp_path: Path, capsys: pytest.CaptureFixture[str], export: Export, workspace: str
) -> None:
    from om_fake import FakeOm
    from test_sync import sync_args

    fake = FakeOm(export)
    args = sync_args(tmp_path)
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main(args) == 0
        capsys.readouterr()
        fake.calls.clear()
        assert main([*args, "--studio-workspace", workspace]) == 3
        assert "invalid Studio workspace" in capsys.readouterr().err
        assert fake.writes == []
