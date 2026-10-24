# SPDX-License-Identifier: Apache-2.0
import json
import shutil
from pathlib import Path
from unittest.mock import patch

import pytest
import requests
import responses
from pydantic import ValidationError

from ttr_connect_openmetadata.export_reader import DirSource, ExportError, NotModified, UrlSource
from ttr_connect_openmetadata.model import Export, Graph
from ttr_connect_openmetadata.state import State, read_state, write_state

FIXTURE = Path(__file__).parent / "fixtures/export"
URL = "http://metadata.example/model/export"


def register_export(mock: responses.RequestsMock, versions: list[str] | None = None) -> None:
    for file, etag in zip(
        ["graph.json", "defs.json", "cn.ttl"],
        versions or ['"fixture-1"'] * 3,
        strict=True,
    ):
        mock.get(URL, body=(FIXTURE / file).read_bytes(), headers={"ETag": etag})


def test_url_projection_queries_and_etags(export: Export) -> None:
    with responses.RequestsMock() as mock:
        register_export(mock)
        result = UrlSource(URL).read(State(snapshot="old", ids=[], om_version="2.0.6"))
        assert result == export
        assert [call.request.url for call in mock.calls] == [
            URL + "?model=world&format=graph.json",
            URL + "?model=world&format=defs.json",
            URL + "?model=cn&format=ttl&profile=owl",
        ]
        assert all(call.request.headers["If-None-Match"] == "old" for call in mock.calls)


def test_304_needs_saved_state_and_short_circuits() -> None:
    state = State(snapshot="fixture-1", ids=["cn.kind.Party"], om_version="2.0.6")
    with responses.RequestsMock() as mock:
        mock.get(URL, status=304, headers={"ETag": 'W/"fixture-1"'})
        assert UrlSource(URL).read(state) == NotModified("fixture-1")
        assert len(mock.calls) == 1
    with responses.RequestsMock() as mock:
        mock.get(URL, status=304)
        with pytest.raises(ExportError, match="304 without"):
            UrlSource(URL).read()
    with responses.RequestsMock() as mock:
        mock.get(URL, status=304, headers={"ETag": "another"})
        with pytest.raises(ExportError, match="304 ETag"):
            UrlSource(URL).read(state)


@pytest.mark.parametrize(
    "versions",
    [
        ["fixture-1", "new", "new"],
        ["fixture-1", "fixture-1", "new"],
        ["", "", ""],
        ["another", "another", "another"],
    ],
)
def test_reject_inconsistent_or_absent_etag(versions: list[str]) -> None:
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        register_export(mock, versions)
        with pytest.raises(ExportError):
            UrlSource(URL).read()


def test_reject_mid_read_304() -> None:
    with responses.RequestsMock() as mock:
        mock.get(URL, body=(FIXTURE / "graph.json").read_text(), headers={"ETag": "fixture-1"})
        mock.get(URL, status=304)
        with pytest.raises(ExportError, match="during a changed"):
            UrlSource(URL).read(State(snapshot="old", ids=[], om_version="2.0.6"))


@pytest.mark.parametrize("error", [requests.Timeout("timeout"), 503, 404])
def test_fetch_errors(error: Exception | int) -> None:
    with responses.RequestsMock() as mock:
        if isinstance(error, Exception):
            mock.get(URL, body=error)
        else:
            mock.get(URL, status=error)
        with pytest.raises(ExportError, match="cannot fetch"):
            UrlSource(URL).read()


@pytest.mark.parametrize("url", ["file:///tmp/a", URL + "?format=dot", URL + "#f"])
def test_reject_bad_url(url: str) -> None:
    with pytest.raises(ExportError, match="HTTP"):
        UrlSource(url).read()


@pytest.mark.parametrize("change", ["snapshot", "grammar", "world", "scope", "graph", "ttl"])
def test_invalid_directory(tmp_path: Path, change: str) -> None:
    shutil.copytree(FIXTURE, tmp_path, dirs_exist_ok=True)
    if change == "ttl":
        (tmp_path / "cn.ttl").write_text("not valid Turtle")
    else:
        data = json.loads((tmp_path / "graph.json").read_text())
        data[change] = {"packages": ["cn"]} if change == "scope" else "wrong"
        (tmp_path / "graph.json").write_text(json.dumps(data))
    with pytest.raises(ExportError, match="invalid export"):
        DirSource(tmp_path).read()


def test_missing_directory(tmp_path: Path) -> None:
    with pytest.raises(ExportError, match="cannot read"):
        DirSource(tmp_path).read()


def test_forward_fields_preserved_and_stubs_valid(export: Export) -> None:
    graph = export.graph.model_dump(by_alias=True)
    graph["future"] = {"value": 1}
    result = Graph.model_validate(graph)
    assert result.model_extra == {**(export.graph.model_extra or {}), "future": {"value": 1}}
    assert any(n.external for n in result.nodes)


def test_duplicate_identity_rejected(export: Export) -> None:
    graph = export.graph.model_dump(by_alias=True)
    graph["nodes"].append(graph["nodes"][0])
    with pytest.raises(ValidationError, match="duplicate"):
        Graph.model_validate(graph)


def test_state_atomic_round_trip_and_failed_replace(tmp_path: Path) -> None:
    path = tmp_path / "nested/state.json"
    assert read_state(path) is None
    state = State(snapshot="s1", ids=["z", "a", "a"], om_version="2.0.6")
    write_state(path, state)
    loaded = read_state(path)
    assert loaded == state.model_copy(update={"ids": ["a", "z"]})
    old_bytes = path.read_bytes()
    with patch("ttr_connect_openmetadata.state.os.replace", side_effect=OSError("disk")):
        with pytest.raises(OSError):
            write_state(path, state.model_copy(update={"snapshot": "s2"}))
    assert path.read_bytes() == old_bytes
    assert list(path.parent.iterdir()) == [path]
    path.write_text("broken")
    with pytest.raises(ValidationError):
        read_state(path)
