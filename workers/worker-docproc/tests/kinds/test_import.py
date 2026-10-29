# SPDX-License-Identifier: Apache-2.0
import pytest

from docproc.kinds.import_ import Import
from docproc.runtime.registry import TypeRegistryClient
from tests.fakes import PackManager
from tests.pack import assert_golden, bundle_of, context, units_of


@pytest.mark.parametrize("doc", ["review-001.en.json", "review-002.cs.json"])
def test_recorded_facts(doc: str) -> None:
    manager = PackManager()
    registry = TypeRegistryClient(manager)
    registry.load("1")
    manager.bundles[f"bundle:{doc}"] = bundle_of(doc, units_of(doc))
    ctx = context(doc, "import", manager=manager)
    result = Import(manager, registry).run(ctx)
    assert not result.verdicts
    assert all(a.producer == "import:json-reviews@1" and a.producer_version == "" for a in result.annotations)
    assert_golden("import", doc, result.annotations, ctx.units)


def test_one_bad_fact_does_not_discard_valid_siblings() -> None:
    from docproc.sdk.models import Annotation, Span, payload_text

    doc = "review-002.cs.json"
    manager = PackManager()
    registry = TypeRegistryClient(manager)
    registry.load("1")
    ctx = context(doc, "import", manager=manager)
    facts = bundle_of(doc, units_of(doc))
    manager.extra_types = [
        {
            "key": "Import.review",
            "schema": {
                "type": "object",
                "required": ["rating"],
                "properties": {"rating": {"type": "integer", "minimum": 1, "maximum": 5}},
            },
        }
    ]
    registry.load("1")
    bad_span = facts[0].model_copy(update={"spans": [Span(unit_id=10001, start=0, end=9999)]})
    bad_payload = facts[0].model_copy(update={"payload_json": payload_text({"rating": 8})})
    missing_identity = Annotation(unit_id=10001, type="Import", payload_json="{}")
    non_object = facts[0].model_copy(update={"payload_json": "[1,2]"})
    manager.bundles[ctx.item.bundle_ref or ""] = [
        facts[0],
        bad_span,
        bad_payload,
        missing_identity,
        non_object,
        facts[1],
    ]
    result = Import(manager, registry).run(ctx)
    assert len(result.annotations) == 2
    assert [a.payload()["rule"] for a in result.verdicts] == ["import/rejected"] * 4
    assert [a.payload()["rating"] for a in result.annotations] == [1, 4]


def test_unit_level_fact_has_no_span() -> None:
    from docproc.sdk.models import Annotation

    manager = PackManager()
    registry = TypeRegistryClient(manager)
    registry.load("1")
    ctx = context("review-001.en.json", "import", manager=manager)
    manager.bundles[ctx.item.bundle_ref or ""] = [
        Annotation(unit_id=10001, type="Import", producer="import:json-reviews@1", payload_json='{"rating":5}')
    ]
    result = Import(manager, registry).run(ctx)
    assert not result.verdicts and not result.annotations[0].spans
    assert result.annotations[0].payload() == {"source": "json-reviews", "recorded": True, "rating": 5}
