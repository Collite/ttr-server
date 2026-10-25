# SPDX-License-Identifier: Apache-2.0
import json
from pathlib import Path

import pytest
import responses
from om_fake import URL, FakeOm

from ttr_connect_openmetadata.cli import main
from ttr_connect_openmetadata.model import Export
from ttr_connect_openmetadata.state import read_state

FIXTURE = Path(__file__).parent / "fixtures/export"


def sync_args(tmp_path: Path) -> list[str]:
    token = tmp_path / "token"
    token.write_text("fixture-token\n")
    return [
        "sync",
        "--export-dir",
        str(FIXTURE),
        "--om-url",
        URL,
        "--om-token-file",
        str(token),
        "--studio-url",
        "https://studio.example",
        "--state-file",
        str(tmp_path / "state.json"),
    ]


def test_sync_twice_and_deprecate(
    export: Export, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    fake = FakeOm(export)
    args = sync_args(tmp_path)
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main(args) == 0
        summary = json.loads(capsys.readouterr().out)
        assert set(summary) == {
            "snapshot",
            "created",
            "updated",
            "deprecated",
            "linked",
            "warnings",
        }
        assert not any("importRdf" in path for _, path, _ in fake.calls)
        assert len(fake.stores["glossaries"]) == 2
        assert len(fake.stores["glossaryTerms"]) == 21
        assert len(fake.stores["metrics"]) == 3
        assert sum(len(t["customProperties"]) for t in fake.stores["metadata/types"].values()) == 11
        table = fake.stores["tables"]["northline-pg.northline.retail.sales"]
        assert table["extension"]["ttrQname"] == "db.retail.sales"
        assert table["extension"]["ttrUrl"].startswith("https://studio.example/viewer?")
        assert any(t["tagFQN"] == "Tatrman.Grounded" for t in table["tags"])
        assert any(t["tagFQN"] == "Tatrman.Grounded" for c in table["columns"] for t in c["tags"])
        assert table["extension"]["catalogOwner"] == "retained"
        assert read_state(tmp_path / "state.json") is not None
        fake.calls.clear()
        assert main(args) == 0
        assert fake.writes == []
        capsys.readouterr()
        # Remove an isolated CN quality while keeping a coherent input snapshot.
        removed = next(n for n in export.graph.nodes if n.kind == "quality")
        edited = tmp_path / "export"
        edited.mkdir()
        for name in ("graph.json", "defs.json", "cn.ttl", ".snapshot"):
            (edited / name).write_bytes((FIXTURE / name).read_bytes())
        graph = json.loads((edited / "graph.json").read_text())
        graph["nodes"] = [n for n in graph["nodes"] if n["id"] != removed.id]
        graph["edges"] = [e for e in graph["edges"] if removed.id not in (e["from"], e["to"])]
        (edited / "graph.json").write_text(json.dumps(graph))
        args[args.index("--export-dir") + 1] = str(edited)
        fake.calls.clear()
        assert main(args) == 0
        assert json.loads(capsys.readouterr().out)["deprecated"] == 1
        patches = [body for method, _, body in fake.writes if method == "PATCH"]
        assert sum(op.get("value") == "Deprecated" for patch in patches for op in patch) == 1
        assert not any(method == "DELETE" for method, _, _ in fake.calls)


def test_check_registry_is_idempotent(
    export: Export, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    fake = FakeOm(export)
    args = sync_args(tmp_path)
    check = ["check", *args[args.index("--om-url") : args.index("--studio-url")]]
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main(check) == 0
        assert json.loads(capsys.readouterr().out)["created"] == 14
        fake.calls.clear()
        assert main(check) == 0
        assert fake.writes == []
        assert fake.calls[0][:2] == ("GET", "system/version")
        assert fake.stores["glossaries"] == {}
        assert not (tmp_path / "state.json").exists()


def test_dry_run_needs_no_credentials_or_writes(
    export: Export, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    fake = FakeOm(export)
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert (
            main(
                [
                    "sync",
                    "--export-dir",
                    str(FIXTURE),
                    "--dry-run",
                    "--state-file",
                    str(tmp_path / "state"),
                ]
            )
            == 0
        )
        assert len(capsys.readouterr().out.splitlines()) == 63
        assert fake.calls == []
        assert not (tmp_path / "state").exists()


@pytest.mark.parametrize(
    "status,attempts",
    [
        (400, 1),
        (401, 1),
        (403, 1),
        (404, 1),
        (409, 1),
        (429, 1),
        (500, 4),
        (502, 4),
        (503, 4),
        (504, 4),
    ],
)
def test_failed_write_retries_only_5xx_and_preserves_state(
    export: Export,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
    status: int,
    attempts: int,
) -> None:
    from ttr_connect_openmetadata.state import State, write_state

    fake = FakeOm(export)
    fake.failures[("PUT", "metrics")] = [status] * attempts
    sleeps: list[float] = []
    monkeypatch.setattr("ttr_connect_openmetadata.om_client.time.sleep", sleeps.append)
    args = sync_args(tmp_path)
    path = tmp_path / "state.json"
    write_state(path, State(snapshot="old", ids=[], om_version="2.0.6"))
    before = path.read_bytes()
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main(args) == 1
        output = capsys.readouterr()
        assert output.out == ""
        assert f"OM {status}" in output.err
    assert path.read_bytes() == before
    assert sum(method == "PUT" and name == "metrics" for method, name, _ in fake.calls) == attempts
    assert sleeps == ([0.25, 0.5, 1.0] if attempts == 4 else [])


def test_transient_rest_and_sdk_failures_recover(
    export: Export,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fake = FakeOm(export)
    fake.failures[("GET", "system/version")] = [503, 502]
    fake.failures[("PUT", "metrics")] = [500, 504]
    sleeps: list[float] = []
    monkeypatch.setattr("ttr_connect_openmetadata.om_client.time.sleep", sleeps.append)
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main(sync_args(tmp_path)) == 0
        assert len(fake.stores["metrics"]) == 3
        assert sleeps == [0.25, 0.5, 0.25, 0.5]
        assert json.loads(capsys.readouterr().out)["snapshot"] == "fixture-1"


def test_patch_failure_surfaces_and_preserves_state(
    export: Export,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from om_fake import uid

    fake = FakeOm(export)
    args = sync_args(tmp_path)
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main(args) == 0
        capsys.readouterr()
        path = tmp_path / "state.json"
        before = path.read_bytes()
        table_name = "northline-pg.northline.retail.sales"
        fake.stores["tables"][table_name]["extension"]["ttrQname"] = "drift"
        fake.failures[("PATCH", f"tables/{uid(table_name)}")] = [503] * 4
        monkeypatch.setattr("ttr_connect_openmetadata.om_client.time.sleep", lambda _: None)
        fake.calls.clear()
        assert main(args) == 1
        assert "OM 503" in capsys.readouterr().err
        assert path.read_bytes() == before
        assert sum(m == "PATCH" for m, _, _ in fake.calls) == 4


def test_missing_table_warns_without_creation(
    export: Export, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    fake = FakeOm(export)
    del fake.stores["tables"]["northline-pg.northline.retail.sales"]
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main([*sync_args(tmp_path), "--cn-path", "glossary"]) == 0
        output = capsys.readouterr()
        assert "WARN OM has not scanned table" in output.err
        assert json.loads(output.out)["warnings"] >= 1
        assert not any(m == "PUT" and p == "tables" for m, p, _ in fake.calls)
        assert not any("importRdf" in p for _, p, _ in fake.calls)


def test_iri_is_immutable_and_foreign_fields_survive(
    export: Export, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    fake = FakeOm(export)
    args = [*sync_args(tmp_path), "--cn-path", "glossary"]
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main(args) == 0
        capsys.readouterr()
        term = next(t for t in fake.stores["glossaryTerms"].values() if t.get("iri"))
        term["iri"] = "https://catalog.example/immutable"
        term["extension"]["externalOwner"] = "kept"
        term["description"] = "outdated"
        assert main(args) == 0
        updated = next(t for t in fake.stores["glossaryTerms"].values() if t["id"] == term["id"])
        assert updated["iri"] == "https://catalog.example/immutable"
        assert updated["extension"]["externalOwner"] == "kept"
        assert updated["description"] != "outdated"


def test_deprecate_all_materialized_types_with_pagination(export: Export, tmp_path: Path) -> None:
    from ttr_connect_openmetadata.mapping import plan_from_export
    from ttr_connect_openmetadata.model import Deprecate
    from ttr_connect_openmetadata.om_client import OmClient

    fake = FakeOm(export)
    fake.page_size = 2
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        client = OmClient(URL, "fixture-token")
        try:
            client.sync(plan_from_export(export), "glossary")
            metric = next(iter(fake.stores["metrics"].values()))
            table = fake.stores["tables"]["northline-pg.northline.retail.sales"]
            column = next(
                c
                for c in table["columns"]
                if any(t["tagFQN"] == "Tatrman.Grounded" for t in c["tags"])
            )
            for kind, qname in [
                ("metric", metric["extension"]["ttrQname"]),
                ("column", column["extension"]["ttrQname"]),
                ("table", table["extension"]["ttrQname"]),
            ]:
                client.deprecate(Deprecate.model_validate({"kind": kind, "qname": qname}))
            assert client.summary.deprecated == 3
            assert any(
                t["tagFQN"] == "Tatrman.Deprecated"
                for t in fake.stores["metrics"][metric["name"]]["tags"]
            )
            latest = fake.stores["tables"][table["fullyQualifiedName"]]
            assert all(t["tagFQN"] != "Tatrman.Grounded" for t in latest["tags"])
            assert any(t["source"] == "Glossary" for t in latest["tags"])
            latest_column = next(c for c in latest["columns"] if c["name"] == column["name"])
            assert all(t["tagFQN"] != "Tatrman.Grounded" for t in latest_column["tags"])
            count = len(fake.writes)
            for kind, qname in [
                ("metric", metric["extension"]["ttrQname"]),
                ("column", column["extension"]["ttrQname"]),
                ("table", table["extension"]["ttrQname"]),
                ("table", "db.missing.nonexistent"),
            ]:
                client.deprecate(Deprecate.model_validate({"kind": kind, "qname": qname}))
            assert len(fake.writes) == count
            assert not any(m == "DELETE" for m, _, _ in fake.calls)
        finally:
            client.close()


def test_registry_type_conflict_fails(
    export: Export, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    fake = FakeOm(export)
    fake.stores["metadata/types"]["table"]["customProperties"] = [
        {
            "name": "ttrQname",
            "description": "Tatrman identity",
            "propertyType": {"id": "00000000-0000-0000-0000-000000000001", "type": "type"},
        }
    ]
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main(sync_args(tmp_path)) == 1
        assert "non-string type" in capsys.readouterr().err
        assert fake.writes == []


@pytest.mark.parametrize("status", [401, 403, 429])
def test_rdf_auth_and_rate_limit_do_not_fall_back(
    export: Export, tmp_path: Path, capsys: pytest.CaptureFixture[str], status: int
) -> None:
    fake = FakeOm(export)
    fake.import_status = status
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main([*sync_args(tmp_path), "--cn-path", "owl"]) == 1
        assert f"OM {status}" in capsys.readouterr().err
        assert fake.stores["glossaryTerms"] == {}
        assert not (tmp_path / "state.json").exists()


def test_rdf_success_adopts_terms_by_iri(
    export: Export, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    from om_fake import uid

    from ttr_connect_openmetadata.mapping import plan_from_export
    from ttr_connect_openmetadata.model import UpsertTerm

    fake = FakeOm(export)
    imported = next(
        op
        for op in plan_from_export(export).operations
        if isinstance(op, UpsertTerm) and op.cn and op.parent is None
    )
    # OWL import chooses an OM name from the ontology. It need not equal the TTR leaf.
    name = "ImportedOntologyName"
    fqn = f"{imported.glossary}.{name}"
    fake.import_status = 200
    fake.import_terms = [
        {
            "id": uid(fqn),
            "name": name,
            "fullyQualifiedName": fqn,
            "description": "Imported",
            "iri": imported.iri,
            "glossary": {
                "id": uid(imported.glossary),
                "type": "glossary",
                "fullyQualifiedName": imported.glossary,
            },
        }
    ]
    fake.import_result["termsCreated"] = 1
    args = [*sync_args(tmp_path), "--cn-path", "owl"]
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main(args) == 0
        summary = json.loads(capsys.readouterr().out)
        assert summary["created"] == 40  # 14 registry, 2 glossaries, 21 terms, 3 metrics
        assert len(fake.stores["glossaryTerms"]) == 21
        actual = fake.stores["glossaryTerms"][fqn]
        assert actual["iri"] == imported.iri
        assert actual["extension"]["ttrQname"] == imported.qname
        assert actual["displayName"] == imported.display
        assert actual["synonyms"] == imported.synonyms
        assert any("importRdf" in p for _, p, _ in fake.calls)
        fake.calls.clear()
        assert main(args) == 0
        assert fake.writes == []


@pytest.mark.parametrize(
    "result",
    [{"dryRun": True}, {"dryRun": False, "messages": ["Failed urn:example: term refused"]}],
)
def test_rdf_200_failure_does_not_advance_state(
    export: Export, tmp_path: Path, capsys: pytest.CaptureFixture[str], result: dict[str, object]
) -> None:
    fake = FakeOm(export)
    fake.import_status = 200
    fake.import_result = result
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main([*sync_args(tmp_path), "--cn-path", "owl"]) == 1
        assert "RDF import" in capsys.readouterr().err
        assert not (tmp_path / "state.json").exists()


@pytest.mark.parametrize("failure", ["glossary", "metric"])
def test_200_link_failure_is_not_success(
    export: Export, tmp_path: Path, capsys: pytest.CaptureFixture[str], failure: str
) -> None:
    fake = FakeOm(export)
    fake.link_failed = failure == "glossary"
    fake.drop_metric_assets = failure == "metric"
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main([*sync_args(tmp_path), "--cn-path", "glossary"]) == 1
        assert "OM error" in capsys.readouterr().err
        assert not (tmp_path / "state.json").exists()


def test_sync_304_is_no_op_even_with_unreadable_token(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    from ttr_connect_openmetadata.state import State, write_state

    path = tmp_path / "state.json"
    write_state(path, State(snapshot="fixture-1", ids=[], om_version="2.0.6"))
    before = path.read_bytes()
    with responses.RequestsMock() as mock:
        mock.get("http://export.example/model/export", status=304)
        assert (
            main(
                [
                    "sync",
                    "--export-url",
                    "http://export.example/model/export",
                    "--om-url",
                    URL,
                    "--om-token-file",
                    str(tmp_path / "does-not-exist"),
                    "--state-file",
                    str(path),
                ]
            )
            == 0
        )
        assert len(mock.calls) == 1
        assert json.loads(capsys.readouterr().out) == {
            "snapshot": "fixture-1",
            "created": 0,
            "updated": 0,
            "deprecated": 0,
            "linked": 0,
            "warnings": 0,
        }
    assert path.read_bytes() == before


def test_no_deprecate_missing_is_honored(
    export: Export, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    from ttr_connect_openmetadata.state import State, write_state

    fake = FakeOm(export)
    args = [*sync_args(tmp_path), "--cn-path", "glossary"]
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main(args) == 0
        capsys.readouterr()
        term = next(iter(fake.stores["glossaryTerms"].values()))
        original = term["extension"]["ttrQname"]
        term["extension"]["ttrQname"] = "cn.kind.Old"
        # FQN remains live but ownership collision must fail instead of stealing it.
        write_state(
            tmp_path / "state.json",
            State(snapshot="before", ids=["cn.kind.Old"], om_version="2.0.6"),
        )
        assert main([*args, "--no-deprecate-missing"]) == 1
        assert "already owned by" in capsys.readouterr().err
        term["extension"]["ttrQname"] = original
        # An extra stale owned term is ignored when deprecation is disabled.
        from copy import deepcopy

        from om_fake import uid

        stale = deepcopy(term)
        stale.update(
            id=uid("stale"),
            name="Old",
            fullyQualifiedName=f"{term['glossary']['fullyQualifiedName']}.Old",
            iri="urn:example:old",
        )
        stale["extension"]["ttrQname"] = "cn.kind.Old"
        fake.stores["glossaryTerms"][stale["fullyQualifiedName"]] = stale
        fake.calls.clear()
        assert main([*args, "--no-deprecate-missing"]) == 0
        assert json.loads(capsys.readouterr().out)["deprecated"] == 0
        assert fake.writes == []


def test_glossary_fallback_fields_hierarchy_relations_and_metrics(
    export: Export, tmp_path: Path
) -> None:
    from ttr_connect_openmetadata.mapping import plan_from_export
    from ttr_connect_openmetadata.model import UpsertMetric, UpsertTerm

    fake = FakeOm(export)
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main([*sync_args(tmp_path), "--cn-path", "glossary"]) == 0
        for op in plan_from_export(export, studio_url="https://studio.example").operations:
            if isinstance(op, UpsertTerm):
                term = fake.stores["glossaryTerms"][op.fqn]
                assert term["displayName"] == op.display
                assert term["description"] == op.description
                assert term["synonyms"] == op.synonyms
                assert {k: term["extension"][k] for k in op.props} == op.props
                assert (term.get("parent") or {}).get("fullyQualifiedName") == op.parent
                assert term.get("iri") == op.iri
                assert {r["term"]["fullyQualifiedName"] for r in term["relatedTerms"]} == {
                    r.term for r in op.related
                }
            elif isinstance(op, UpsertMetric):
                metric = fake.stores["metrics"][op.name]
                assert metric["metricType"] == op.metricType
                assert metric["metricExpression"] == op.metricExpression.model_dump()
                assert {k: metric["extension"][k] for k in op.props} == op.props
                assert json.loads(metric["extension"]["ttrColumnAssets"])
                assert metric["assets"]  # Native assets, no fictitious measure glossary term.
        assert not any("importRdf" in p for _, p, _ in fake.calls)


def test_missing_assets_produce_one_warning_per_table(
    export: Export, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    fake = FakeOm(export)
    fake.stores["tables"].clear()
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main([*sync_args(tmp_path), "--cn-path", "glossary"]) == 0
        output = capsys.readouterr()
        assert json.loads(output.out)["warnings"] == 4
        assert output.err.count("WARN OM has not scanned table") == 4
        assert "WARN OM has not scanned column" not in output.err
        assert not any(m in ("PUT", "PATCH") and p.startswith("tables") for m, p, _ in fake.calls)


def test_terms_from_another_world_are_untouched(
    export: Export, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    from copy import deepcopy

    from om_fake import uid

    from ttr_connect_openmetadata.state import State, write_state

    fake = FakeOm(export)
    args = [*sync_args(tmp_path), "--cn-path", "glossary"]
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main(args) == 0
        capsys.readouterr()
        term = next(iter(fake.stores["glossaryTerms"].values()))
        foreign = deepcopy(term)
        foreign.update(
            id=uid("foreign"), name="Foreign", fullyQualifiedName="another Concepts.Foreign"
        )
        foreign["glossary"] = {
            "id": uid("another"),
            "type": "glossary",
            "fullyQualifiedName": "another Concepts",
        }
        fake.stores["glossaryTerms"][foreign["fullyQualifiedName"]] = foreign
        # Same TTR qname in another world must neither collide with live terms nor be deprecated.
        fake.calls.clear()
        assert main(args) == 0
        assert fake.writes == []
        capsys.readouterr()
        foreign["extension"]["ttrQname"] = "cn.kind.ForeignRemoved"
        write_state(
            tmp_path / "state.json",
            State(snapshot="before", ids=["cn.kind.ForeignRemoved"], om_version="2.0.6"),
        )
        assert main(args) == 0
        assert json.loads(capsys.readouterr().out)["deprecated"] == 0
        assert fake.writes == []


def test_state_write_failure_is_reported_after_sync(
    export: Export,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def fail_write(*_: object) -> None:
        raise OSError("state disk full")

    fake = FakeOm(export)
    monkeypatch.setattr("ttr_connect_openmetadata.cli.write_state", fail_write)
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main([*sync_args(tmp_path), "--cn-path", "glossary"]) == 1
        output = capsys.readouterr()
        assert "state disk full" in output.err
        assert output.out == ""
        assert not (tmp_path / "state.json").exists()


def test_health_timeout_fails_once(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    import requests

    args = sync_args(tmp_path)
    with responses.RequestsMock() as mock:
        mock.get(URL + "/v1/system/version", body=requests.Timeout("health timeout"))
        assert main(args) == 1
        assert len(mock.calls) == 1
        assert "health timeout" in capsys.readouterr().err
        assert not (tmp_path / "state.json").exists()


def test_unpersisted_source_iri_fails_without_advancing_state(
    export: Export, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    fake = FakeOm(export)
    fake.drop_term_iri = True
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main(sync_args(tmp_path)) == 1
    assert "OM did not retain source IRI" in capsys.readouterr().err
    assert not (tmp_path / "state.json").exists()


def test_stale_conditional_deprecation_preserves_state(
    export: Export, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    import shutil

    fake = FakeOm(export)
    args = sync_args(tmp_path)
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        fake.install(mock)
        assert main(args) == 0
        capsys.readouterr()
        state = tmp_path / "state.json"
        before = state.read_bytes()
        edited = tmp_path / "export"
        shutil.copytree(FIXTURE, edited)
        removed = "cn.quality.sold_at"
        graph = json.loads((edited / "graph.json").read_text())
        graph["nodes"] = [n for n in graph["nodes"] if n["id"] != removed]
        graph["edges"] = [e for e in graph["edges"] if removed not in (e["from"], e["to"])]
        (edited / "graph.json").write_text(json.dumps(graph))
        term = next(
            t
            for t in fake.stores["glossaryTerms"].values()
            if t["extension"]["ttrQname"] == removed
        )
        path = f"glossaryTerms/{term['id']}"
        fake.failures[("PATCH", path)] = [412]
        fake.calls.clear()
        args[args.index("--export-dir") + 1] = str(edited)
        assert main(args) == 1
        assert "OM 412" in capsys.readouterr().err
        assert sum(method == "PATCH" and name == path for method, name, _ in fake.calls) == 1
        assert state.read_bytes() == before
