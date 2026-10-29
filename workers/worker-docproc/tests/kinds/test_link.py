# SPDX-License-Identifier: Apache-2.0
from dataclasses import replace

import pytest

from docproc._generated.org.tatrman.fuzzy.v1 import fuzzy_pb2 as pb
from docproc.kinds.common import annotation
from docproc.kinds.link import Link
from docproc.kinds.nlp_pipeline import NlpPipeline
from docproc.sdk.models import Span, Unit, UnitKind
from tests.extraction import registry
from tests.fakes import FakeWarehouse
from tests.fakes_lexmatcher import RecordedLexMatcher, recorded_lexmatcher
from tests.fakes_nlp import recorded_nlp
from tests.pack import assert_golden, context, new_context


@pytest.mark.parametrize("doc", ["review-001.en.json", "review-002.cs.json", "claim-001.cs.txt", "claim-002.en.md"])
def test_px_linked_mentions_and_single_batch(doc: str) -> None:
    ctx = context(doc, "cz-mentions")
    with recorded_nlp() as nlp:
        mentions = [a for a in NlpPipeline(nlp).run(ctx).annotations if a.type == "Mention"]
    for n, a in enumerate(mentions):
        a.id = 100 + n
    linkctx = replace(context(doc, "link-mentions"), annotations=mentions)
    replay = RecordedLexMatcher()
    with recorded_lexmatcher(replay) as lex:
        result = Link(lex, FakeWarehouse(list(ctx.units.values()))).run(linkctx)
    assert len(replay.calls) == 1
    linked = [a for a in result.annotations if a.type == "Mention"]
    assert len(linked) == len(mentions)
    assert all(a.payload()["concept"] == "cn.Item" and a.payload()["member"]["id"].startswith("HL-") for a in linked)
    assert all(a.derived_from == [mentions[n].id] for n, a in enumerate(linked))
    assert_golden("link", doc, result.annotations, ctx.units)


def test_ambiguous_abstains_and_real_hartland_does_not_invent_px_members() -> None:
    unit = Unit(id=9, kind=UnitKind.SECTION, content_text="ventilátor")
    mention = annotation(unit, "Mention", {"kind": "item", "surface": "ventilátor"}, [Span(unit_id=9, start=0, end=10)])
    mention.id = 5
    ctx = replace(context("review-001.en.json", "link-mentions"), units={9: unit}, annotations=[mention])
    with recorded_lexmatcher() as lex:
        result = Link(lex, FakeWarehouse([unit])).run(ctx)
    assert result.abstained and len(result.annotations) == 1
    assert result.annotations[0].type == "Decision" and "chosen" not in result.annotations[0].payload()
    registry().schema_for("Decision").validate(result.annotations[0].payload())
    with recorded_lexmatcher(RecordedLexMatcher("hartland.json")) as lex:
        result = Link(lex, FakeWarehouse([unit])).run(ctx)
    assert result.abstained and not any(a.type == "Mention" for a in result.annotations)


def test_knn_union_mapping_and_refusal() -> None:
    class Lex:
        def batch(self, queries: list[pb.SpanQuery]) -> pb.BatchMatchResponse:
            return pb.BatchMatchResponse(
                results=[
                    pb.FuzzyMatchResponse(
                        matches=[
                            pb.FuzzyMatch(
                                candidate_id="HL-1001",
                                candidate="Aurora",
                                score=0.8,
                                category="er.entity.item.product_name",
                            ),
                            pb.FuzzyMatch(
                                candidate_id="ignored",
                                candidate="unmapped",
                                score=1,
                                category="other",
                                source=pb.VOCABULARY,
                                target_ref="er.entity.unknown",
                            ),
                        ]
                    )
                ]
            )

    class Store(FakeWarehouse):
        def find_similar(self, request: dict) -> dict:  # type: ignore[type-arg]
            assert request == {"text": "Aurora failed.", "nodeKinds": ["page"], "k": 5}
            return {"hits": [{"score": 0.9, "page": {"title": "Item", "conceptRef": {"metadataQname": "cn.Item"}}}]}

    unit = Unit(id=1, content_text="Aurora failed. Next sentence.")
    mention = annotation(unit, "Mention", {"kind": "item", "surface": "Aurora"}, [Span(unit_id=1, start=0, end=6)])
    ctx = replace(context("review-001.en.json", "link-mentions"), units={1: unit}, annotations=[mention])
    result = Link(Lex(), Store([unit])).run(ctx)
    assert result.annotations[0].payload()["linkMethod"] == "both"
    assert result.sections["candidates"]["unmapped"] == 1
    with pytest.raises(ValueError, match="pick-not-in-this-release"):
        Link(Lex(), Store([unit])).run(new_context(ctx, kind="link", engine={"pick": "llm"}))
