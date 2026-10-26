# SPDX-License-Identifier: Apache-2.0
"""DX-P0 S0.4 T4 — the warehouse contracts against the shared fixtures: every fixture parses into its model and
re-serializes to the same canonical bytes (the kantheon side round-trips the same files through the proto printer);
spans are code points of the unit's NFC text."""

from __future__ import annotations

import json
import unicodedata
from typing import Any

import pytest
from pydantic import BaseModel, ValidationError

from aristarchos.sdk.models import (
    Annotation,
    AnnotationTier,
    AnnotationType,
    ListAnnotationsPage,
    ListAnnotationsRequest,
    LoadAnnotationsRequest,
    LoadAnnotationsResponse,
    Unit,
    UnitKind,
    canonical,
    payload_text,
)
from tests.conftest import CONTRACTS

SINGLE: list[tuple[str, type[BaseModel]]] = [
    ("unit.json", Unit),
    ("annotation.mention.json", Annotation),
    ("annotation.statement.json", Annotation),
    ("annotation.verdict.json", Annotation),
    ("annotation.import.json", Annotation),
    ("annotationType.json", AnnotationType),
]
PAIRS: list[tuple[str, type[BaseModel], type[BaseModel]]] = [
    ("loadAnnotations.json", LoadAnnotationsRequest, LoadAnnotationsResponse),
    ("listAnnotations.json", ListAnnotationsRequest, ListAnnotationsPage),
]


def dump(m: BaseModel) -> dict[str, Any]:
    return m.dump()  # type: ignore[attr-defined, no-any-return]


@pytest.mark.parametrize(("name", "model"), SINGLE)
def test_fixture_round_trips_byte_equal(name: str, model: type[BaseModel]) -> None:
    text = (CONTRACTS / name).read_text(encoding="utf-8")
    assert canonical(dump(model.strict(text))) == text  # type: ignore[attr-defined]


@pytest.mark.parametrize(("name", "request_model", "response_model"), PAIRS)
def test_request_response_fixture_round_trips(
    name: str, request_model: type[BaseModel], response_model: type[BaseModel]
) -> None:
    text = (CONTRACTS / name).read_text(encoding="utf-8")
    o = json.loads(text)
    assert set(o) == {"request", "response"}
    back = {
        "request": dump(request_model.strict(o["request"])),  # type: ignore[attr-defined]
        "response": dump(response_model.strict(o["response"])),  # type: ignore[attr-defined]
    }
    assert canonical(back) == text


def test_spans_are_code_points_of_the_nfc_text() -> None:
    unit = Unit.strict((CONTRACTS / "unit.json").read_text(encoding="utf-8"))
    mention = Annotation.strict((CONTRACTS / "annotation.mention.json").read_text(encoding="utf-8"))
    assert unicodedata.is_normalized("NFC", unit.content_text)
    assert unit.kind is UnitKind.SECTION and unit.parent_id == 100
    assert unit.slice(mention.spans[0]) == mention.payload()["surface"] == "pozastavená"
    assert len(unit.content_text) == 80  # code points; UTF-16 would count the leading emoji twice


def test_payload_json_is_a_string_and_round_trips() -> None:
    a = Annotation.strict((CONTRACTS / "annotation.statement.json").read_text(encoding="utf-8"))
    p = a.payload()
    assert p["concept"] == "cn.Review" and p["modifiers"]["verification"] == "verified_purchase"
    assert payload_text(p) == a.payload_json  # compact, sorted, non-ASCII verbatim — the producer's form
    assert a.derived_from == [5001]


def test_defaults_absent_and_unknown_keys_refused_by_the_strict_twin() -> None:
    imp = Annotation.strict((CONTRACTS / "annotation.import.json").read_text(encoding="utf-8"))
    assert imp.producer_version == "" and "producerVersion" not in imp.dump()  # import: no processor version
    t = AnnotationType.strict((CONTRACTS / "annotationType.json").read_text(encoding="utf-8"))
    assert t.tier is AnnotationTier.STRUCTURAL and t.feeds_gold is False and "feedsGold" not in t.dump()
    with pytest.raises(ValidationError):
        Annotation.strict({"unitId": 1, "payload": {}})


def test_a_newer_kallimachos_response_still_reads() -> None:
    """review-158 F5 — the REST client decodes responses tolerantly: a new key, a new enum value."""
    o = json.loads((CONTRACTS / "unit.json").read_text(encoding="utf-8"))
    o["embeddingRef"] = "vec://1"
    o["kind"] = "PARAGRAPH"
    unit = Unit.model_validate(o)
    assert unit.kind is UnitKind.UNIT_KIND_UNSPECIFIED and unit.content_text
    with pytest.raises(ValidationError):
        Unit.strict(o)


def test_list_request_query_string() -> None:
    req = ListAnnotationsRequest.model_validate(
        json.loads((CONTRACTS / "listAnnotations.json").read_text(encoding="utf-8"))["request"]
    )
    assert req.query() == [
        ("notebookId", "hartland-reviews"),
        ("unitId", "101"),
        ("types", "Mention"),
        ("types", "Statement[review]"),
        ("activeOnly", "true"),
        ("pageSize", "50"),
    ]
