# SPDX-License-Identifier: Apache-2.0
from docproc.kinds.diff import Diff
from tests.fakes import FakeWarehouse
from tests.pack import assert_golden, context, new_context, units_of


def test_first_version_all_new() -> None:
    doc = "claim-002.en.md"
    ctx = new_context(context(doc, "classify-sections"), kind="diff", engine={})
    result = Diff(FakeWarehouse(units_of(doc))).run(ctx)
    assert all(a.payload()["status"] == "new" for a in result.annotations)
    assert_golden("diff", doc, result.annotations, ctx.units)


def test_two_versions_align_modify_add_remove_and_hash_fallback() -> None:
    from dataclasses import replace

    from docproc.sdk.models import Unit, UnitKind
    from tests.pack import metadata

    doc = "claim-002.en.md"
    old = units_of(doc, source_id=10)
    current = units_of(doc, source_id=20)
    current[0].metadata["recordedAt"] = metadata(recordedAt="2026-10-27T00:00:00Z")["recordedAt"]
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
    document_id = current[0].metadata["documentId"].single
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


def test_diff_times_are_utc_and_leaf_versions_match() -> None:
    from dataclasses import replace

    import pytest

    from docproc.sdk.models import Unit, UnitKind
    from tests.pack import metadata

    old = Unit(
        id=1,
        source_id=10,
        kind=UnitKind.DOCUMENT,
        content_text="same",
        content_hash="hash",
        metadata=metadata(recordedAt="2026-10-01T07:00:00Z"),
    )
    now = old.model_copy(
        update={"id": 2, "source_id": 20, "metadata": metadata(recordedAt="2026-10-01T09:30:00+02:00")}
    )
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
