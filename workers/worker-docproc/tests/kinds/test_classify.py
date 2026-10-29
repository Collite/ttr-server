# SPDX-License-Identifier: Apache-2.0
import pytest

from docproc.kinds.classify import Classify
from docproc.runtime.registry import KindRegistry, RunContext
from tests.pack import DOCS, assert_golden, context, expected


def checked(ctx: RunContext) -> RunContext:
    registry = KindRegistry()
    registry.register("classify", Classify())
    assert ctx.processor
    assert registry.manifest(ctx.processor) == ctx.manifest
    return ctx


@pytest.mark.parametrize("doc", DOCS)
def test_pack_sections(doc: str) -> None:
    ctx = context(doc, "classify-sections")
    result = Classify().run(checked(ctx))
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
                {"kind": "later", "name": "fallback"},
            ],
            "default": "other",
        },
    )
    from dataclasses import replace

    ctx = replace(ctx, units={unit.id: unit})
    assert Classify(str(PACK)).run(checked(ctx)).annotations[0].payload() == {"kind": "review_body", "rule": "rule:1"}
    ctx = new_context(
        ctx, kind="classify", engine={"rules": [{"heading": "missing", "kind": "no"}], "default": "other"}
    )
    assert Classify().run(checked(ctx)).annotations[0].payload()["kind"] == "other"


def test_where_uses_only_current_unit_annotations() -> None:
    from dataclasses import replace

    from docproc.kinds.common import annotation
    from tests.pack import new_context

    ctx = context("review-001.en.json", "classify-sections")
    units = list(ctx.units.values())
    ctx = new_context(
        ctx,
        kind="classify",
        engine={"rules": [{"kind": "selected", "where": "exists(Mention[item])"}], "default": "other"},
    )
    ctx = replace(ctx, annotations=[annotation(units[0], "Mention", {"kind": "item", "surface": "Aurora"})])
    assert [a.payload()["kind"] for a in Classify().run(checked(ctx)).annotations] == ["selected", "other"]


def test_dormant_invalid_where_refused() -> None:
    from tests.pack import new_context

    ctx = new_context(
        context("review-001.en.json", "classify-sections"),
        kind="classify",
        engine={"rules": [{"kind": "no", "heading": "never matches", "where": "Mention."}], "default": "other"},
    )
    with pytest.raises(ValueError, match="predicate/syntax"):
        Classify().run(checked(ctx))
