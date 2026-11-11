# SPDX-License-Identifier: Apache-2.0
"""DX-P0 S0.4 T5 — the warehouse client's call shape against a stubbed docwh-store (respx)."""

from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest
import respx
from pydantic import ValidationError

from docproc.sdk.models import ListAnnotationsRequest, LoadAnnotationsRequest
from docproc.sdk.store import MartForbidden, SchemaRejected, StoreClient, StoreError
from tests.conftest import CONTRACTS

BASE = "http://docwh-store.test"


def fixture(name: str) -> dict[str, object]:
    value: dict[str, object] = json.loads((CONTRACTS / name).read_text(encoding="utf-8"))
    return value


def unit_view(child_ids: list[int] | None = None) -> dict[str, object]:
    """`GET /units/{id}`'s body: `GetUnitResponse {unit, childIds}` (docwh-store `UnitViewDto`) around unit.json, the
    proto `Unit`. The fixture alone is not the route's answer."""
    return {"unit": fixture("unit.json"), "childIds": child_ids or []}


@respx.mock
def test_load_annotations_posts_the_fixture_request_and_reads_the_response() -> None:
    f = fixture("loadAnnotations.json")
    route = respx.post(f"{BASE}/load/annotations").mock(return_value=httpx.Response(200, json=f["response"]))
    client = StoreClient(BASE)
    resp = client.load_annotations(LoadAnnotationsRequest.model_validate(f["request"]))
    assert json.loads(route.calls.last.request.content) == f["request"]  # the body is the fixture's request half
    assert resp.ids == [5001, 5005, 5006] and resp.mentions_linked == 2


@respx.mock
def test_get_unit_and_list_annotations() -> None:
    respx.get(f"{BASE}/units/101", params={"notebookId": "hartland-reviews"}).mock(
        return_value=httpx.Response(200, json=unit_view([102]))
    )
    page = fixture("listAnnotations.json")
    listing = respx.get(f"{BASE}/annotations").mock(return_value=httpx.Response(200, json=page["response"]))
    client = StoreClient(BASE)
    unit = client.get_unit(101, "hartland-reviews")
    assert unit.id == 101 and unit.content_text and unit.content_lemmas.startswith("recenze")
    got = client.list_annotations(ListAnnotationsRequest.model_validate(page["request"]))
    assert [a.id for a in got.annotations] == [5001, 5002] and got.next_page_token
    assert listing.calls.last.request.url.params.get_list("types") == ["Mention", "Statement[review]"]


@respx.mock
def test_get_unit_refuses_a_body_that_is_not_its_unit() -> None:
    # A bare Unit (the shape docproc 0.11.1 assumed) read as an all-defaults unit: id 0, no text, no lang.
    respx.get(f"{BASE}/units/101").mock(return_value=httpx.Response(200, json=fixture("unit.json")))
    with pytest.raises(ValidationError, match="unit"):
        StoreClient(BASE).get_unit(101)
    # Another unit than the one asked for is refused too, never processed.
    respx.get(f"{BASE}/units/102").mock(return_value=httpx.Response(200, json=unit_view()))
    with pytest.raises(StoreError) as e:
        StoreClient(BASE).get_unit(102)
    assert e.value.code == "store/unit-mismatch" and "answered unit 101" in e.value.message


@respx.mock
@pytest.mark.parametrize(
    "body",
    [
        {"error": "mart_forbidden", "detail": "source 7 is in no visible mart"},  # today's ErrorDto
        {"error": {"code": "mart_forbidden", "message": "source 7 is in no visible mart"}},  # the nested envelope
    ],
)
def test_403_is_mart_forbidden(body: dict[str, object]) -> None:
    respx.get(f"{BASE}/units/101").mock(return_value=httpx.Response(403, json=body))
    with pytest.raises(MartForbidden) as e:
        StoreClient(BASE).get_unit(101, "hartland-reviews")
    assert e.value.code == "mart_forbidden" and "no visible mart" in e.value.message


@respx.mock
def test_422_annotation_schema_is_schema_rejected_other_errors_stay_generic() -> None:
    respx.post(f"{BASE}/load/annotations").mock(
        side_effect=[
            httpx.Response(422, json={"error": {"code": "annotation/schema", "message": "/surface: required"}}),
            httpx.Response(422, json={"error": "annotation/unknown-type", "detail": "Statement[refund]"}),
        ]
    )
    client = StoreClient(BASE)
    req = LoadAnnotationsRequest.model_validate(fixture("loadAnnotations.json")["request"])
    with pytest.raises(SchemaRejected):
        client.load_annotations(req)
    with pytest.raises(StoreError) as e:
        client.load_annotations(req)
    assert not isinstance(e.value, SchemaRejected) and e.value.code == "annotation/unknown-type"


@respx.mock
def test_service_identity_paging_and_generation(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DOCPROC_SERVICE_TOKEN", "service-secret")
    unit_route = respx.get(f"{BASE}/units/101").mock(return_value=httpx.Response(200, json=unit_view()))
    listing = respx.get(f"{BASE}/annotations").mock(
        side_effect=[
            httpx.Response(200, json={"annotations": [{"id": 1}], "nextPageToken": "next"}),
            httpx.Response(200, json={"annotations": [{"id": 2}]}),
        ]
    )
    client = StoreClient(BASE)
    client.get_unit(101)
    assert "notebookId" not in unit_route.calls.last.request.url.params
    assert unit_route.calls.last.request.headers["authorization"] == "Bearer service-secret"
    request = ListAnnotationsRequest(generation="g1", generation_producers=["cz-mentions", "link-mentions"])
    assert [a.id for a in client.iter_annotations(request)] == [1, 2]
    assert listing.calls.last.request.url.params["pageToken"] == "next"
    assert listing.calls.last.request.url.params["generation"] == "g1"
    # Repeated keys; the store reads them as it reads `types` (DxRoutes GET /annotations).
    assert listing.calls.last.request.url.params.get_list("generationProducers") == ["cz-mentions", "link-mentions"]
    assert "activeOnly" not in listing.calls.last.request.url.params


@respx.mock
def test_remaining_reads_and_lemma_sink() -> None:
    tree = respx.get(f"{BASE}/sources/7/units", params={"text": "true"}).mock(
        return_value=httpx.Response(200, json={"units": [fixture("unit.json")]})
    )
    lemmas = respx.post(f"{BASE}/load/lemmas").mock(return_value=httpx.Response(200, json={}))
    client = StoreClient(BASE)
    assert client.units_of_source(7)[0].id == 101 and tree.called
    entry = {"unitId": 101, "contentLemmas": "recenze", "lang": "cs", "lemmatizer": "test"}
    client.load_lemmas("r/1", [entry])
    assert json.loads(lemmas.calls.last.request.content) == {"runId": "r/1", "lemmas": [entry]}


@respx.mock
def test_link_reads_speak_the_store_wire() -> None:
    """✅W5-2: `/findSimilar`, `GET /pages/{id}` and `GET /sources` against the fixtures docwh-store's own specs print
    (RetrievalRoutesSpec, UnitRoutesSpec): the request is the store DTO's, the answers are read as the routes write."""
    f = fixture("findSimilar.json")
    similar = respx.post(f"{BASE}/findSimilar").mock(return_value=httpx.Response(200, json=f["response"]))
    client = StoreClient(BASE)
    req: dict[str, object] = f["request"]  # type: ignore[assignment]
    got = client.find_similar(
        str(req["notebookId"]),
        str(req["query"]),
        list(req["nodeKinds"]),  # type: ignore[call-overload]
        int(req["k"]),  # type: ignore[call-overload]
    )
    assert json.loads(similar.calls.last.request.content) == req  # FindSimilarRequestDto: notebookId + query required
    hit = got.chunks[0]
    assert hit.page_id is not None and hit.chunk_id is None and hit.span is None

    p = fixture("page.json")
    preq: dict[str, object] = p["request"]  # type: ignore[assignment]
    page = respx.get(f"{BASE}/pages/{preq['pageId']}", params={"notebookId": preq["notebookId"]}).mock(
        return_value=httpx.Response(200, json=p["response"])
    )
    read = client.page(int(preq["pageId"]), str(preq["notebookId"]))  # type: ignore[call-overload]
    assert page.called and read.id == preq["pageId"] and read.concept_ref is not None
    assert read.concept_ref.metadata_qname.startswith("cn.")

    s = fixture("listSources.json")
    sreq: dict[str, object] = s["request"]  # type: ignore[assignment]
    sources = respx.get(f"{BASE}/sources", params=sreq).mock(return_value=httpx.Response(200, json=s["response"]))
    listed = client.sources_of_document(str(sreq["documentId"]))
    assert sources.called and [x["notebookId"] for x in listed] == ["hartland-reviews"]


@respx.mock
def test_no_sources_is_an_empty_list_and_a_blank_mart_is_the_routes_400() -> None:
    # the DX JSON drops an empty list: a document with no source (or none in a mart) answers `{}`
    respx.get(f"{BASE}/sources", params={"documentId": "none"}).mock(return_value=httpx.Response(200, json={}))
    assert StoreClient(BASE).sources_of_document("none") == []
    respx.post(f"{BASE}/findSimilar").mock(return_value=httpx.Response(400, json={"error": "notebook_id required"}))
    with pytest.raises(StoreError) as e:
        StoreClient(BASE).find_similar("", "x", ["page"], 5)
    assert e.value.status == 400 and e.value.code == "notebook_id required"


@respx.mock
def test_conflict_and_token_file(tmp_path: Path) -> None:
    from docproc.sdk.store import RunConflict

    token = tmp_path / "token"
    token.write_text("file-secret\n")
    route = respx.get(f"{BASE}/units/1").mock(return_value=httpx.Response(409, json={"error": "run/conflict"}))
    with pytest.raises(RunConflict):
        StoreClient(BASE, token_file=str(token)).get_unit(1)
    assert route.calls.last.request.headers["authorization"] == "Bearer file-secret"
