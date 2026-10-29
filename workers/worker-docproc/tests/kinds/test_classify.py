# SPDX-License-Identifier: Apache-2.0
import pytest

from docproc.kinds.classify import Classify
from tests.pack import DOCS, assert_golden, context, expected


@pytest.mark.parametrize("doc", DOCS)
def test_pack_sections(doc: str) -> None:
    ctx = context(doc, "classify-sections")
    result = Classify().run(ctx)
    assert [a.payload()["kind"] for a in result.annotations] == [
        s.get(
            "sectionKind",
            "spec_table"
            if s.get("title") in ("Specifications", "Elektrické parametry", "Rozměry a hmotnost")
            else "claim_body",
        )
        for s in expected(doc)["sections"]
    ]
    assert_golden("classify", doc, result.annotations, ctx.units)


def test_rule_forms_order_gazetteer_and_unicode() -> None:
    from docproc.sdk.models import Unit, UnitKind
    from tests.fakes import PACK
    from tests.pack import metadata, new_context

    unit = Unit(
        id=9,
        kind=UnitKind.SECTION,
        content_text="🙂 ventilátoru Breeze",
        metadata=metadata(title="PŘEHLED", lang="cs", role="review"),
    )
    ctx = new_context(
        context("review-002.cs.json", "classify-sections"),
        kind="classify",
        engine={
            "rules": [
                {"kind": "wrong", "lang": "en"},
                {
                    "kind": "review_body",
                    "heading": "^přehled$",
                    "metadata": {"role": "review"},
                    "lang": "cs",
                    "contains_any": ["gazetteers/items.tsv"],
                },
                {"kind": "later"},
            ],
            "default": "other",
        },
    )
    from dataclasses import replace

    ctx = replace(ctx, units={unit.id: unit})
    assert Classify(str(PACK)).run(ctx).annotations[0].payload() == {"kind": "review_body", "rule": "rule:1"}
    ctx.manifest["engine"]["rules"] = [{"heading": "missing", "kind": "no"}]
    assert Classify().run(ctx).annotations[0].payload()["kind"] == "other"
