# SPDX-License-Identifier: Apache-2.0
import json
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
    assert statement.payload()["modifiers"]["temporal_context"] == "current" and mod.id in statement.derived_from
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


def test_state_times_compare_instants_across_offsets(tmp_path: Path) -> None:
    import shutil

    import yaml

    ctx, wh = complete_subject()
    target = tmp_path / "pack"
    shutil.copytree(PACK, target)
    policy = yaml.safe_load((target / "consolidation.yaml").read_text())
    policy["entities"]["review"] = {"nature": "state", "blocking": ["item"]}
    (target / "consolidation.yaml").write_text(yaml.safe_dump(policy))
    first = ctx.annotations[0].model_copy(deep=True)
    second = first.model_copy(deep=True)
    second.id = 950
    payload = first.payload()
    payload["attributes"]["recorded_at"] = "2026-10-01T09:00:00+02:00"
    first.payload_json = payload_text(payload)
    payload["attributes"]["recorded_at"] = "2026-10-01T08:00:00Z"
    payload["attributes"]["rating"] = 5
    second.payload_json = payload_text(payload)
    kind = Consolidator(wh, registry(), str(target))
    result = kind.consolidate(replace(ctx, annotations=[first, second]))
    record = next(a.payload() for a in result.annotations if a.type == "Record.review")
    assert record["attributes"]["rating"] == 5
    assert record["history"][0]["asOf"] == "2026-10-01T07:00:00+00:00"
    assert not record.get("conflicts")


def test_normalize_conflicts_do_not_leak_across_shared_modifier() -> None:
    from docproc.kinds.common import annotation
    from docproc.sdk.models import Span

    ctx, wh = complete_subject()
    a, b = ctx.annotations
    # Two distinct non-overlapping evidence groups in the same unit, sharing a scope.
    b = b.model_copy(update={"unit_id": a.unit_id, "spans": [Span(unit_id=a.unit_id, start=40, end=50)]}, deep=True)
    ap = a.payload()
    ap["spans"] = [{"unitId": a.unit_id, "start": 0, "end": 10}]
    a = a.model_copy(update={"spans": [Span(unit_id=a.unit_id, start=0, end=10)], "payload_json": payload_text(ap)})
    bp = b.payload()
    bp["spans"] = [s.model_dump(by_alias=True) for s in b.spans]
    b.payload_json = payload_text(bp)
    duplicate = a.model_copy(update={"id": 901}, deep=True)
    p = duplicate.payload()
    p["attributes"]["rating"] = 1
    duplicate.payload_json = payload_text(p)
    mod = annotation(
        a.unit_id,
        "Modifier",
        {"kind": "temporal_context", "value": "current", "scope": {"unitId": a.unit_id, "start": 0, "end": 100}},
    )
    mod.id = 902
    wh.annotations.extend([b, duplicate, mod])
    kind = Consolidator(wh, registry(), str(PACK))
    normalized = kind.normalize(replace(ctx, annotations=[a, duplicate, b, mod]))
    statements = [a for a in normalized.annotations if a.type.startswith("Statement")]
    assert len(statements) == 2 and all(mod.id in a.derived_from for a in statements)
    for n, a in enumerate(normalized.annotations):
        a.id = 1000 + n
    wh.annotations.extend(normalized.annotations)
    result = kind.consolidate(replace(ctx, annotations=statements))
    records = [a.payload() for a in result.annotations if a.type == "Record.review"]
    assert len(records) == 2 and sum(bool(p.get("conflicts")) for p in records) == 1


@pytest.mark.parametrize("reverse", [False, True])
def test_evidence_role_only_increases(reverse: bool) -> None:
    ctx, wh = complete_subject()
    first = ctx.annotations[0].model_copy(deep=True)
    first_payload = first.payload()
    first_payload["attributes"].pop("title", None)
    first.payload_json = payload_text(first_payload)
    second = first.model_copy(update={"id": 901}, deep=True)
    p = second.payload()
    p["attributes"]["rating"] = 1
    p["attributes"]["title"] = "new"
    if reverse:
        p["attributes"] = dict(reversed(list(p["attributes"].items())))
    second.payload_json = json.dumps(p)
    result = Consolidator(wh, registry(), str(PACK)).consolidate(replace(ctx, annotations=[first, second]))
    record = next(a.payload() for a in result.annotations if a.type == "Record.review")
    assert record["evidence"][1]["role"] == "contradicts"


def test_normalize_preserves_modifier_disagreement() -> None:
    from docproc.kinds.common import annotation

    ctx, wh = complete_subject()
    first = ctx.annotations[0]
    mod = annotation(
        first.unit_id,
        "Modifier",
        {"kind": "temporal_context", "value": "past", "scope": first.spans[0].model_dump(by_alias=True)},
    )
    mod.id = 901
    another = mod.model_copy(update={"id": 902})
    result = Consolidator(wh, registry(), str(PACK)).normalize(replace(ctx, annotations=[first, mod, another]))
    statement, decision = result.annotations
    assert statement.payload()["modifiers"]["temporal_context"] == "current"
    assert decision.payload()["factors"]["conflicts"] == [
        {"attribute": "modifiers.temporal_context", "values": ["current", "past"], "annotationIds": [first.id, mod.id]}
    ]


def test_normalize_transitive_components() -> None:
    from itertools import permutations

    from docproc.sdk.models import Span

    ctx, wh = complete_subject()
    first = ctx.annotations[0]
    kind = Consolidator(wh, registry(), str(PACK))
    for ids in permutations([901, 902, 903]):
        inputs = []
        for id_, (start, end) in zip(ids, [(0, 10), (5, 20), (15, 30)], strict=True):
            a = first.model_copy(
                update={"id": id_, "derived_from": [], "spans": [Span(unit_id=first.unit_id, start=start, end=end)]},
                deep=True,
            )
            p = a.payload()
            p["spans"] = [s.model_dump(by_alias=True) for s in a.spans]
            a.payload_json = payload_text(p)
            inputs.append(a)
        output = kind.normalize(replace(ctx, annotations=inputs))
        assert len(output.annotations) == 1 and not output.verdicts
        assert output.annotations[0].derived_from == [901, 902, 903]
        assert [(s.start, s.end) for s in output.annotations[0].spans] == [(0, 30)]


def test_evidence_time_error_is_redacted() -> None:
    ctx, wh = complete_subject()
    a = ctx.annotations[0].model_copy(deep=True)
    p = a.payload()
    p["attributes"]["recorded_at"] = "SECRET"
    a.payload_json = payload_text(p)
    with pytest.raises(ValueError, match="^consolidate/evidence-time-invalid$"):
        Consolidator(wh, registry(), str(PACK)).consolidate(replace(ctx, annotations=[a]))


def test_two_modifiers_keep_both_values_and_their_lineage() -> None:
    from docproc.kinds.common import annotation

    ctx, wh = complete_subject()
    a = ctx.annotations[0].model_copy(deep=True)
    p = a.payload()
    p["modifiers"].pop("temporal_context")
    a.payload_json = payload_text(p)
    first = annotation(
        a.unit_id,
        "Modifier",
        {"kind": "temporal_context", "value": "current", "scope": a.spans[0].model_dump(by_alias=True)},
    )
    first.id = 901
    second = first.model_copy(update={"id": 902}, deep=True)
    p = second.payload()
    p["value"] = "past"
    second.payload_json = payload_text(p)
    result = Consolidator(wh, registry(), str(PACK)).normalize(replace(ctx, annotations=[a, first, second]))
    statement, decision = result.annotations
    assert statement.payload()["modifiers"]["temporal_context"] == "current"
    assert decision.payload()["factors"]["conflicts"] == [
        {"attribute": "modifiers.temporal_context", "values": ["current", "past"], "annotationIds": [901, 902]}
    ]
