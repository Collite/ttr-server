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
