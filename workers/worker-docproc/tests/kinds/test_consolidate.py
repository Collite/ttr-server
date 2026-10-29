# SPDX-License-Identifier: Apache-2.0
from dataclasses import replace
from pathlib import Path

import pytest

from docproc.kinds.consolidate import Consolidator
from docproc.sdk.models import MetadataValue, payload_text
from tests.extraction import complete_subject, registry
from tests.fakes import PACK


@pytest.mark.parametrize("doc", ["review-001.en.json", "review-002.cs.json"])
def test_complete_records_and_rerun_keys(doc: str) -> None:
    ctx, wh = complete_subject(doc)
    kind = Consolidator(wh, registry(), str(PACK))
    first = kind.consolidate(ctx)
    records = [a for a in first.annotations if a.type == "Record.review"]
    assert len(records) == 2 and not first.verdicts
    keys = [a.payload()["chosen"] for a in first.annotations if a.type == "Decision"]
    assert keys == [f.payload()["reviewId"] for f in wh.annotations if f.type == "Import.review"]
    assert all(not a.spans and a.unit_id == ctx.item.unit_ids[0] for a in records)
    for a in first.annotations:
        registry().schema_for(a.type).validate(a.payload())
    second = kind.consolidate(replace(ctx, item=ctx.item.model_copy(update={"run_id": "rerun/1"})))
    assert keys == [a.payload()["chosen"] for a in second.annotations if a.type == "Decision"]


def test_subject_isolation_and_missing_record_fields() -> None:
    ctx, wh = complete_subject()
    other = next(iter(ctx.units.values())).model_copy(deep=True)
    other.id = 999
    other.metadata["subjectId"] = MetadataValue(single="other")
    with pytest.raises(ValueError, match="single-subject"):
        Consolidator(wh, registry(), str(PACK)).consolidate(replace(ctx, units={**ctx.units, 999: other}))
    a = ctx.annotations[0].model_copy(deep=True)
    payload = a.payload()
    del payload["attributes"]["sentiment"]
    a.payload_json = payload_text(payload)
    output = Consolidator(wh, registry(), str(PACK)).consolidate(replace(ctx, annotations=[a]))
    assert not output.annotations and output.verdicts[0].payload()["rule"] == "consolidate/schema"


def test_unknown_identity_reuses_maximum_overlap() -> None:
    ctx, wh = complete_subject()
    wh.annotations = [a for a in wh.annotations if a.type != "Import.review"]
    first = Consolidator(wh, registry(), str(PACK)).consolidate(ctx)
    for n, a in enumerate(first.annotations):
        a.id, a.run_id = 500 + n, ctx.item.run_id
    wh.annotations.extend(first.annotations)
    again = Consolidator(wh, registry(), str(PACK)).consolidate(ctx)
    assert [a.payload()["chosen"] for a in first.annotations if a.type == "Decision"] == [
        a.payload()["chosen"] for a in again.annotations if a.type == "Decision"
    ]
    assert all(a.payload()["factors"]["identity"] == "NEW" for a in again.annotations if a.type == "Decision")


@pytest.mark.parametrize("doc", ["review-001.en.json", "review-002.cs.json"])
def test_reviewed_normalize_and_record_goldens(doc: str) -> None:
    from tests.pack import assert_golden

    ctx, wh = complete_subject(doc)
    kind = Consolidator(wh, registry(), str(PACK))
    normalized = kind.normalize(ctx)
    assert_golden("normalize", doc, normalized.annotations, ctx.units)
    consolidated = kind.consolidate(ctx)
    assert_golden("consolidate", doc, consolidated.annotations, ctx.units)


def test_normalize_duplicates_conflicts_and_document_boundary() -> None:
    ctx, wh = complete_subject()
    first = ctx.annotations[0]
    duplicate = first.model_copy(deep=True)
    duplicate.id = 999
    payload = duplicate.payload()
    payload["attributes"]["rating"] = 1
    duplicate.payload_json = payload_text(payload)
    result = Consolidator(wh, registry(), str(PACK)).normalize(replace(ctx, annotations=[first, duplicate]))
    assert len(result.annotations) == 2
    statement, decision = result.annotations
    assert statement.derived_from == [first.id, duplicate.id]
    assert decision.payload()["factors"]["conflicts"][0]["values"] == [2, 1]


def test_state_updates_vs_contemporaneous_conflicts_and_roles(tmp_path: Path) -> None:
    import shutil

    import yaml

    ctx, wh = complete_subject()
    target = tmp_path / "pack"
    shutil.copytree(PACK, target)
    policy = yaml.safe_load((target / "consolidation.yaml").read_text())
    policy["entities"]["review"]["nature"] = "state"
    policy["entities"]["review"].pop("identity")
    (target / "consolidation.yaml").write_text(yaml.safe_dump(policy))
    first = ctx.annotations[0]
    second = first.model_copy(deep=True)
    second.id = 901
    payload = second.payload()
    payload["attributes"]["rating"] = 5
    payload["attributes"]["recorded_at"] = "2026-10-01T00:00:00Z"
    second.payload_json = payload_text(payload)
    kind = Consolidator(wh, registry(), str(target))
    updated = kind.consolidate(replace(ctx, annotations=[first, second]))
    record = next(a.payload() for a in updated.annotations if a.type == "Record.review")
    assert record["attributes"]["rating"] == 5 and not record.get("conflicts")
    assert record["history"][0]["value"] == 2
    assert record["evidence"][1]["role"] == "updates"
    payload["attributes"]["recorded_at"] = first.payload()["attributes"]["recorded_at"]
    second.payload_json = payload_text(payload)
    conflicting = kind.consolidate(replace(ctx, annotations=[first, second]))
    record = next(a.payload() for a in conflicting.annotations if a.type == "Record.review")
    assert record["conflicts"][0]["status"] == "open" and record["evidence"][1]["role"] == "contradicts"
    second.payload_json = first.payload_json
    restated = kind.consolidate(replace(ctx, annotations=[first, second]))
    assert (
        next(a.payload() for a in restated.annotations if a.type == "Record.review")["evidence"][1]["role"]
        == "restates"
    )


def test_normalized_conflicts_survive_to_record_and_modifier_lineage() -> None:
    from docproc.kinds.common import annotation

    ctx, wh = complete_subject()
    first = ctx.annotations[0]
    duplicate = first.model_copy(deep=True)
    duplicate.id = 901
    payload = duplicate.payload()
    payload["attributes"]["rating"] = 1
    duplicate.payload_json = payload_text(payload)
    wh.annotations.append(duplicate)
    scope = first.spans[0].model_dump(by_alias=True)
    mod = annotation(first.unit_id, "Modifier", {"kind": "temporal_context", "scope": scope, "trigger": "past"})
    mod.id = 902
    wh.annotations.append(mod)
    kind = Consolidator(wh, registry(), str(PACK))
    normalized = kind.normalize(replace(ctx, annotations=[first, duplicate, mod]))
    statement = next(a for a in normalized.annotations if a.type == "Statement[review]")
    assert statement.payload()["modifiers"]["temporal_context"] == "past" and mod.id in statement.derived_from
    for n, a in enumerate(normalized.annotations):
        a.id = 1000 + n
        wh.annotations.append(a)
    consolidated = kind.consolidate(replace(ctx, annotations=[statement]))
    record = next(a for a in consolidated.annotations if a.type == "Record.review")
    assert record.payload()["conflicts"][0]["status"] == "open"
    assert [v["value"] for v in record.payload()["conflicts"][0]["values"]] == [2, 1]


def test_split_largest_evidence_part_keeps_old_key(tmp_path: Path) -> None:
    import shutil

    import yaml

    from docproc.kinds.common import annotation
    from docproc.kinds.consolidate import digest

    ctx, wh = complete_subject()
    target = tmp_path / "pack"
    shutil.copytree(PACK, target)
    policy = yaml.safe_load((target / "consolidation.yaml").read_text())
    policy["entities"]["review"] = {"nature": "state", "blocking": ["rating"]}
    (target / "consolidation.yaml").write_text(yaml.safe_dump(policy))
    a, b = ctx.annotations
    c = b.model_copy(deep=True)
    c.id = 302
    wh.annotations.append(c)
    ids = [a.id, b.id, c.id]
    old_key = digest("review", "HL-1001", a.id)
    prior = annotation(
        ctx.item.unit_ids[0], "Record.review", {"evidence": [{"annotationId": i, "role": "creates"} for i in ids]}
    )
    prior.id, prior.run_id = 800, "previous/1"
    decision = annotation(
        prior.unit_id,
        "Decision",
        {
            "decider": "consolidate/key",
            "chosen": old_key,
            "score": 1,
            "factors": {"recordType": "Record.review", "recordEvidence": ids, "subjectId": "HL-1001"},
        },
    )
    decision.id, decision.run_id = 801, prior.run_id
    wh.annotations.extend([prior, decision])
    result = Consolidator(wh, registry(), str(target)).consolidate(replace(ctx, annotations=[a, b, c]))
    decisions = [a.payload() for a in result.annotations if a.type == "Decision"]
    assert len(decisions) == 2
    assert next(d for d in decisions if d["factors"]["recordEvidence"] == [b.id, c.id])["chosen"] == old_key
    assert next(d for d in decisions if d["factors"]["recordEvidence"] == [a.id])["chosen"] != old_key
