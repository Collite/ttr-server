# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import hashlib
import json
from dataclasses import replace
from typing import Any

import pytest

from docproc.kinds.chunk.kind import Chunk
from docproc.kinds.chunk.strategies import page, paragraph, structural, table_aware
from docproc.kinds.chunk.tokens import Tokenizer
from docproc.runtime.registry import TypeRegistryClient
from docproc.sdk.models import Annotation, Unit, UnitKind, canonical
from tests.conftest import WORKER
from tests.fakes import FakeWarehouse, PackManager
from tests.fakes_nlp import recorded_nlp
from tests.pack import context, metadata, units_of


def test_paragraph_kotlin_oracle_and_px_recursive() -> None:
    oracle = json.loads((WORKER / "tests/fixtures/paragraph-kotlin.json").read_text())
    for doc, sections in oracle["documents"].items():
        units = units_of(doc)
        for unit, expected in zip(units[1:], sections, strict=True):
            spans = paragraph.chunk(unit.content_text, unit, {})
            assert [{"start": s.start, "end": s.end} for s in spans] == expected["spans"], doc
    doc = "sheet-001.en.html"
    ctx = context(doc, "chunk-recursive")
    from tests.pack import expected as px

    with recorded_nlp() as nlp:
        result = Chunk(nlp, FakeWarehouse(units_of(doc))).run(ctx)
    want = px(doc)["chunks"]["items"]
    assert [(a.spans[0].start, a.spans[0].end, a.payload()["tokenCount"]) for a in result.annotations] == [
        (a["span"]["start"], a["span"]["end"], a["tokenCount"]) for a in want
    ]


def test_page_heading_and_table_rows_with_repeated_header() -> None:
    text = "intro\nHEADER X\nrow one value\nrow two value\nrow three value\nafter"
    lo, hi = text.index("HEADER"), text.index("after")
    rows = [lo, text.index("row one"), text.index("row two"), text.index("row three")]
    unit = Unit(
        id=1,
        content_text=text,
        metadata=metadata(
            pageBreaks=[str(lo), str(hi)],
            headings=[f"{lo}-{lo + 6}:1"],
            tables=[f"{lo}-{hi}|" + ",".join(map(str, rows))],
        ),
    )
    assert [(s.start, s.end) for s in page.chunk(text, unit, {})] == [(0, 5), (6, 58), (59, 64)]
    headings = structural.chunk(text, unit, {"tokenizer": "whitespace", "max_tokens": 100})
    assert [text[s.start : s.end] for s in headings] == ["intro", text[lo:hi].strip() + "\nafter"]
    chunks = table_aware.chunk(text, unit, {"tokenizer": "whitespace", "max_tokens": 5, "overlap": 0})
    table_chunks = [s for s in chunks if lo <= s.start < hi]
    assert len(table_chunks) == 3
    assert table_chunks[0].start == lo and not table_chunks[0].extra_spans
    assert all(s.extra_spans == ((lo, rows[1]),) for s in table_chunks[1:])
    assert all(s.start in rows and s.end in rows + [hi] for s in table_chunks)
    assert all(
        Tokenizer({"tokenizer": "whitespace"}).count("\n".join(text[a:b] for a, b in s.ranges())) <= 5
        for s in table_chunks
    )
    oversize = table_aware.chunk(text, unit, {"tokenizer": "whitespace", "max_tokens": 2, "overlap": 0})
    assert any(s.payload.get("oversize") for s in oversize)


def test_section_hint_prefix_provenance_and_canonical_params_hash() -> None:
    root = Unit(id=1, kind=UnitKind.DOCUMENT, metadata=metadata(title="Product"))
    parent = Unit(id=2, parent_id=1, kind=UnitKind.SECTION, metadata=metadata(title="Specs"))
    unit = Unit(id=3, parent_id=2, kind=UnitKind.SECTION, content_text="🙂 value", metadata=metadata(title="fallback"))
    ctx = context("sheet-001.en.html", "chunk-recursive")
    hint = Annotation(id=42, unit_id=3, type="Section", payload_json='{"title":"Voltage"}')
    ctx = replace(ctx, units={3: unit}, annotations=[hint])
    ctx.manifest["engine"]["params"] = {"max_tokens": 20, "tokenizer": "whitespace"}
    with recorded_nlp() as nlp:
        result = Chunk(nlp, FakeWarehouse([root, parent, unit])).run(ctx)
    a = result.annotations[0]
    assert a.payload()["context"] == {"prefix": "Product › Specs › Voltage", "parentUnitId": 3}
    assert a.derived_from == [42] and a.producer_version == ctx.item.version_hash
    assert (
        a.payload()["paramsHash"]
        == "sha256:" + hashlib.sha256(canonical(ctx.manifest["engine"]["params"]).encode()).hexdigest()
    )
    registry = TypeRegistryClient(PackManager())
    registry.load("1")
    registry.schema_for("Chunk").validate(a.payload())


def test_parent_section_own_text_is_processed_with_its_child() -> None:
    from docproc.kinds.common import processing_units

    root = Unit(id=1, kind=UnitKind.DOCUMENT)
    parent = Unit(id=2, parent_id=1, kind=UnitKind.SECTION, content_text="Introduction")
    child = Unit(id=3, parent_id=2, kind=UnitKind.SECTION, content_text="Detail")
    ctx = replace(context("sheet-001.en.html", "chunk-recursive"), units={u.id: u for u in (root, parent, child)})
    ctx.manifest["engine"]["context"] = {}
    assert [u.id for u in processing_units(ctx)] == [2, 3]
    with recorded_nlp() as nlp:
        result = Chunk(nlp, FakeWarehouse([root, parent, child])).run(ctx)
    assert [a.unit_id for a in result.annotations] == [2, 3] and not result.verdicts


def test_recursive_encodes_unit_once(monkeypatch: pytest.MonkeyPatch) -> None:
    from docproc.kinds.chunk.strategies.recursive import windows
    from docproc.kinds.chunk.tokens import encoding

    encoder = encoding()
    original = encoder.encode
    calls: list[str] = []

    def counted(text: str, **kwargs: Any) -> list[int]:
        calls.append(text)
        return original(text, **kwargs)

    monkeypatch.setattr(encoder, "encode", counted)
    text = "Long document. " * 10000
    chunks = windows(text, {"max_tokens": 200, "overlap": 0}, [". "])
    assert len(chunks) > 10 and calls == [text]
