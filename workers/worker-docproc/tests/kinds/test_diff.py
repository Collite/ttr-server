# SPDX-License-Identifier: Apache-2.0
from dataclasses import replace

import pytest

from docproc.kinds import common
from docproc.kinds.diff import Diff
from docproc.runtime.registry import RunContext
from docproc.sdk.models import Unit, UnitKind
from tests.fakes import FakeWarehouse, split_source_metadata
from tests.pack import assert_golden, context, metadata, new_context, units_of


def test_first_version_all_new(monkeypatch: pytest.MonkeyPatch) -> None:
    """The store lists no source for the document (`{}` on the wire, read as `[]`): the documented fallback reads the
    source's own `recordedAt`, as the runner read it with its tree (review-257 F14; never the DOCUMENT unit's, which
    split does not write), finds no older version, and every section is new (review-244 F6)."""
    doc = "claim-002.en.md"
    ctx = new_context(context(doc, "classify-sections"), kind="diff", engine={})
    units = units_of(doc)
    root = next(u for u in units if u.kind == UnitKind.DOCUMENT)
    warehouse = FakeWarehouse(units).register(doc, root.source_id)
    warehouse.sources = []
    # the runner reads a diff's whole source, root included, and the source with it
    source = warehouse.source_tree(root.source_id).source
    assert source is not None
    ctx = replace(ctx, units={root.id: root, **ctx.units}, sources={root.source_id: source})
    read: list[tuple[int, str]] = []

    def meta(unit: Unit, name: str) -> str:
        read.append((unit.id, name))
        return common.meta(unit, name)

    def source_meta(ctx: RunContext, source_id: int, name: str) -> str:
        read.append((source_id, name))
        return common.source_meta(ctx, source_id, name)

    monkeypatch.setattr("docproc.kinds.diff.meta", meta)
    monkeypatch.setattr("docproc.kinds.diff.source_meta", source_meta)
    result = Diff(warehouse).run(ctx)
    assert (root.source_id, "recordedAt") in read and (root.id, "recordedAt") not in read
    assert warehouse.source_reads == 1
    assert result.sections["decisions"] == {"previousSourceId": None, "removedUnitIds": []}
    assert all(a.payload()["status"] == "new" for a in result.annotations)
    assert_golden("diff", doc, result.annotations, ctx.units)


def test_two_versions_align_modify_add_remove_and_hash_fallback() -> None:
    doc = "claim-002.en.md"
    old = units_of(doc, source_id=10)
    current = units_of(doc, source_id=20)
    current[2].content_text += " The fault is now confirmed."
    current[2].content_hash = "sha256:changed"
    # Retitled and moved; content hash still allows copy-forward alignment.
    current[3].metadata["sectionPath"] = metadata(sectionPath=["Moved"])["sectionPath"]
    current[3].idx = 8
    old.append(
        Unit(
            id=10999,
            source_id=10,
            idx=7,
            kind=UnitKind.SECTION,
            content_text="Removed",
            metadata=metadata(title="Removed"),
        )
    )
    current.append(
        Unit(
            id=20999, source_id=20, idx=9, kind=UnitKind.SECTION, content_text="Added", metadata=metadata(title="Added")
        )
    )
    warehouse = FakeWarehouse(old + current)
    document_id = split_source_metadata(doc)["documentId"].single
    warehouse.sources = [
        {
            "id": 30,
            "metadata": {"documentId": {"single": document_id}, "recordedAt": {"single": "2026-10-28T00:00:00Z"}},
        },
        {
            "id": 10,
            "metadata": {"documentId": {"single": document_id}, "recordedAt": {"single": "2026-10-26T00:00:00Z"}},
        },
        {
            "id": 20,
            "metadata": {"documentId": {"single": document_id}, "recordedAt": {"single": "2026-10-27T00:00:00Z"}},
        },
        {
            "id": 9,
            "metadata": {"documentId": {"single": "unrelated"}, "recordedAt": {"single": "2026-10-26T23:00:00Z"}},
        },
    ]
    ctx = new_context(context(doc, "classify-sections", units=current), kind="diff", engine={})
    ctx = replace(ctx, units={u.id: u for u in current})
    result = Diff(warehouse).run(ctx)
    payloads = [a.payload() for a in result.annotations]
    assert [p["status"] for p in payloads] == ["identical", "modified", "identical", "identical", "new"]
    assert payloads[1]["previousUnitId"] == old[2].id and 0 < payloads[1]["similarity"] < 1
    assert payloads[2]["previousUnitId"] == old[3].id
    assert result.sections["decisions"] == {"previousSourceId": 10, "removedUnitIds": [10999]}
    assert_golden("diff-v2", doc, result.annotations, {u.id: u for u in current})


def test_an_unlisted_source_compares_the_listed_versions_with_its_sources_recorded_at() -> None:
    """review-244 R5: the store does not list this source (20), but it lists the document's other versions, 10
    (2026-10-26) and 30 (2026-10-28). The fallback compares those with this source's own `recordedAt` (2026-10-27),
    as the runner read it with its tree (`RunContext.sources`; review-257 F14: split writes it on the Source, and the
    DOCUMENT unit carries none): 10 is the previous version, 30 a later one, and the sections align against 10's
    units."""
    doc = "claim-002.en.md"
    old = units_of(doc, source_id=10)
    current = units_of(doc, source_id=20)
    assert current[0].kind == UnitKind.DOCUMENT and "recordedAt" not in current[0].metadata
    later = units_of(doc, source_id=30)
    warehouse = FakeWarehouse(old + current + later).register(doc, 20)
    warehouse.source_metadata[20].update(metadata(recordedAt="2026-10-27T00:00:00Z"))
    document_id = split_source_metadata(doc)["documentId"].single
    warehouse.sources = [
        {"id": i, "metadata": {"documentId": {"single": document_id}, "recordedAt": {"single": at}}}
        for i, at in [(30, "2026-10-28T00:00:00Z"), (10, "2026-10-26T00:00:00Z")]
    ]
    ctx = new_context(context(doc, "classify-sections", units=current), kind="diff", engine={})
    source = warehouse.source_tree(20).source
    assert source is not None
    ctx = replace(ctx, units={u.id: u for u in current}, sources={20: source})
    result = Diff(warehouse).run(ctx)
    assert result.sections["decisions"] == {"previousSourceId": 10, "removedUnitIds": []}
    old_sections = [u.id for u in old if u.kind == UnitKind.SECTION]
    assert [a.payload() for a in result.annotations] == [
        {"status": "identical", "previousUnitId": uid} for uid in old_sections
    ]


def test_diff_times_are_utc_and_leaf_versions_match() -> None:
    # the times are the listed sources' (as split writes them); the DOCUMENT units carry none (review-257 F15)
    old = Unit(id=1, source_id=10, kind=UnitKind.DOCUMENT, content_text="same", content_hash="hash")
    now = old.model_copy(update={"id": 2, "source_id": 20})
    later = old.model_copy(update={"id": 3, "source_id": 30})
    ctx = replace(new_context(context("claim-002.en.md", "classify-sections"), kind="diff", engine={}), units={2: now})
    wh = FakeWarehouse([old, now, later])
    wh.sources = [
        {"id": i, "documentId": ctx.item.document_id, "recordedAt": at}
        for i, at in [(10, "2026-10-01T07:00:00Z"), (20, "2026-10-01T09:30:00+02:00"), (30, "2026-10-01T08:00:00Z")]
    ]
    result = Diff(wh).run(ctx)
    assert result.sections["decisions"]["previousSourceId"] == 10
    assert result.annotations[0].payload() == {"status": "identical", "previousUnitId": 1}
    wh.sources[1]["recordedAt"] = "2026-10-01T09:30:00"
    with pytest.raises(ValueError, match="diff/recorded-at-offset-required"):
        Diff(wh).run(ctx)
