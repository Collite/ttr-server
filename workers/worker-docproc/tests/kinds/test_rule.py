# SPDX-License-Identifier: Apache-2.0
import json
from dataclasses import replace
from pathlib import Path

import pytest
import yaml

from docproc.kinds.classify import Classify
from docproc.kinds.rule import Rule
from docproc.kinds.rule_dsl import RuleDsl, template
from docproc.sdk.models import Annotation, Span
from tests.conftest import WORKER
from tests.extraction import registry
from tests.fakes import PACK, FakeWarehouse
from tests.pack import assert_golden, bundle_of, context, new_context, units_of


@pytest.mark.parametrize("doc", ["review-001.en.json", "review-002.cs.json"])
def test_px_dsl_produces_sparse_statements_and_lineage(doc: str) -> None:
    units = units_of(doc)
    ctx = context(doc, "normalize-review")
    facts = bundle_of(doc, units)
    linked: list[Annotation] = []
    # Use reviewed link goldens as upstream input, resolving stable unit references.
    for n, row in enumerate(json.loads((WORKER / "tests/goldens/link" / f"{doc}.json").read_text())):
        if row["type"] != "Mention":
            continue
        unit = next(u for u in units[1:] if row["unit"].endswith(f":s{u.idx}"))
        linked.append(
            Annotation(
                id=100 + n,
                unit_id=unit.id,
                type="Mention",
                payload_json=json.dumps(row["payload"]),
                spans=[Span(unit_id=unit.id, start=s["start"], end=s["end"]) for s in row["spans"]],
            )
        )
    for n, a in enumerate(facts):
        a.id = 200 + n
    labels = Classify(str(PACK)).run(context(doc, "classify-sections")).annotations
    for n, a in enumerate(labels):
        a.id = 300 + n
    ctx = replace(ctx, annotations=facts + labels + linked)
    result = Rule(FakeWarehouse(units), registry(), str(PACK)).run(ctx)
    assert not result.verdicts and len(result.annotations) == 2
    assert all(set(a.payload()["attributes"]) == {"item", "rating"} for a in result.annotations)
    assert all(len(a.derived_from) == 3 for a in result.annotations)
    assert_golden("rule", doc, result.annotations, ctx.units)


def test_closed_templates_and_unregistered_function(tmp_path: Path) -> None:
    ctx = context("review-001.en.json", "normalize-review")
    assert template('coalesce(${missing.payload.foo}, "fallback")', {"missing": None}, ctx) == "fallback"
    with pytest.raises(ValueError, match="invalid-template"):
        template("${x.__class__}", {}, ctx)
    with pytest.raises(ValueError, match="function-unregistered"):
        Rule(FakeWarehouse([]), registry(), str(PACK)).run(
            new_context(ctx, kind="rule", engine={"function": "os.system"})
        )
    rules = {
        "version": 1,
        "rules": [
            {
                "id": "bad",
                "match": {"type": "Mention"},
                "emit": {"type": "Mention", "spans": "match", "payload": {"surface": "${evil.surface}"}},
            }
        ],
    }
    (tmp_path / "bad.yaml").write_text(yaml.safe_dump(rules))
    with pytest.raises(ValueError, match="unknown-binding"):
        RuleDsl(registry(), str(tmp_path)).run(ctx, "bad.yaml")


@pytest.mark.parametrize("literal", ["unknown", "a,b"])
def test_single_quoted_coalesce(literal: str) -> None:
    ctx = context("review-001.en.json", "normalize-review")
    assert template(f"coalesce(${{m.payload.x}}, '{literal}')", {"m": None}, ctx) == literal


@pytest.mark.parametrize("refined", [True, False])
def test_rule_drops_raw_mentions_only_when_refined(refined: bool) -> None:
    from docproc.kinds.common import annotation
    from docproc.kinds.link import Link
    from docproc.kinds.nlp_pipeline import NlpPipeline
    from tests.fakes_lexmatcher import recorded_lexmatcher
    from tests.fakes_nlp import recorded_nlp

    ctx = context("review-001.en.json", "normalize-review")
    # One review anchor, with its raw NLP mentions and optional link refinements.
    unit = next(iter(ctx.units.values()))
    with recorded_nlp() as nlp:
        raw = [
            a
            for a in NlpPipeline(nlp)
            .run(replace(context("review-001.en.json", "cz-mentions"), units={unit.id: unit}))
            .annotations
            if a.type == "Mention"
        ]
    for n, a in enumerate(raw):
        a.id = 100 + n
    with recorded_lexmatcher() as lex:
        linked = [
            a
            for a in Link(lex, FakeWarehouse([unit]))
            .run(replace(context("review-001.en.json", "link-mentions"), units={unit.id: unit}, annotations=raw))
            .annotations
            if a.type == "Mention"
        ]
    for n, a in enumerate(linked):
        a.id = 300 + n
    facts = bundle_of("review-001.en.json", units_of("review-001.en.json"))
    facts = [a for a in facts if a.unit_id == unit.id]
    for n, a in enumerate(facts):
        a.id = 500 + n
    label = annotation(unit, "SectionKind", {"kind": "review_body"})
    label.id = 700
    result = RuleDsl(registry(), str(PACK)).run(
        replace(ctx, units={unit.id: unit}, annotations=raw + (linked if refined else []) + facts + [label]),
        "rules/normalize-review.v1.yaml",
    )
    assert len(result.annotations) == (1 if refined else 0)
    assert len(result.verdicts) == (0 if refined else 1)


def test_refined_anchor_is_not_double_bound(tmp_path: Path) -> None:
    from docproc.kinds.common import annotation

    ctx = context("review-001.en.json", "normalize-review")
    unit = next(iter(ctx.units))
    raw = annotation(unit, "Mention", {"kind": "item", "surface": "raw"})
    raw.id = 1
    refined = annotation(unit, "Mention", {"kind": "item", "surface": "refined"})
    refined.id = 2
    refined.derived_from = [1]
    rules = {
        "version": 1,
        "rules": [
            {
                "id": "anchor",
                "match": {"type": "Mention", "name": "m"},
                "emit": {
                    "type": "Mention",
                    "spans": "m",
                    "payload": {"kind": "item", "surface": "${m.payload.surface}"},
                },
            }
        ],
    }
    (tmp_path / "rule.yaml").write_text(yaml.safe_dump(rules))
    result = RuleDsl(registry(), str(tmp_path)).run(replace(ctx, annotations=[raw, refined]), "rule.yaml")
    assert len(result.annotations) == 1 and result.annotations[0].payload()["surface"] == "refined"
