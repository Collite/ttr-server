# SPDX-License-Identifier: Apache-2.0
import json
from dataclasses import replace
from typing import Any

import pytest

from docproc._generated.org.tatrman.fuzzy.v1 import fuzzy_pb2 as pb
from docproc.kinds.common import annotation
from docproc.kinds.link import Link
from docproc.kinds.nlp_pipeline import NlpPipeline
from docproc.runtime.registry import KindResult, RunContext
from docproc.sdk.models import Annotation, Span, Unit, UnitKind, canonical
from tests.conftest import WORKER
from tests.extraction import registry
from tests.fakes import FakeWarehouse, concept_page, page_hit
from tests.fakes_lexmatcher import RecordedLexMatcher, recorded_lexmatcher
from tests.fakes_nlp import recorded_nlp
from tests.pack import assert_golden, context, golden_rows, new_context

#: findSimilar.json's page hit, printed by docwh-store: a sentence-to-concept-page cosine as the route answers it
#: (≥ 0.11.13: the raw cosine, never normalized to the best hit's — review-244 F1). Below `abstain_below`.
REALISTIC = page_hit(9001)["score"]
#: A cosine at or above `abstain_below` (0.6): a page close enough to link on the kNN alone.
CLOSE = 0.72


def linked(result: KindResult) -> list[Annotation]:
    """The Mentions a run linked; an abstaining link's own Mention (`linkMethod: abstain`, S4.1f) links nothing."""
    return [a for a in result.annotations if a.type == "Mention" and a.payload()["linkMethod"] != "abstain"]


def with_page(units: list[Unit], qname: str, cosine: float, page_id: int = 9001) -> FakeWarehouse:
    """A store whose mart holds one concept page about [qname] that `/findSimilar` finds at [cosine]."""
    store = FakeWarehouse(units)
    store.similar = [page_hit(page_id, cosine)]
    store.pages = {page_id: concept_page(page_id, qname)}
    return store


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
    # S4.1f: the abstaining Decision, beside the Mention that carries its candidates to link-pick
    [decision] = [a for a in result.annotations if a.type == "Decision"]
    assert result.abstained and "chosen" not in decision.payload() and not linked(result)
    assert [a.payload()["linkMethod"] for a in result.annotations if a.type == "Mention"] == ["abstain"]
    registry().schema_for("Decision").validate(decision.payload())
    with recorded_lexmatcher(RecordedLexMatcher("hartland.json")) as lex:
        result = Link(lex, FakeWarehouse([unit])).run(ctx)
    assert result.abstained and not linked(result)


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

    unit = Unit(id=1, content_text="Aurora failed. Next sentence.")
    mention = annotation(unit, "Mention", {"kind": "item", "surface": "Aurora"}, [Span(unit_id=1, start=0, end=6)])
    ctx = replace(context("review-001.en.json", "link-mentions"), units={1: unit}, annotations=[mention])
    store = with_page([unit], "cn.Item", CLOSE)
    result = Link(Lex(), store).run(ctx)
    # the store's FindSimilarRequestDto, in the mention's source mart (✅W5-2)
    assert store.similar_calls == [
        {"notebookId": "hartland-public", "query": "Aurora failed.", "nodeKinds": ["page"], "k": 5}
    ]
    assert result.annotations[0].payload()["linkMethod"] == "both"
    assert result.sections["candidates"]["unmapped"] == 1
    with pytest.raises(ValueError, match="pick-not-in-this-release"):
        Link(Lex(), store).run(new_context(ctx, kind="link", engine={"pick": "llm"}))


@pytest.mark.parametrize("score,chosen", [(0.2, False), (0.95, True)])
def test_page_never_boosts_member(score: float, chosen: bool) -> None:
    class Lex:
        def batch(self, queries: list[pb.SpanQuery]) -> pb.BatchMatchResponse:
            return pb.BatchMatchResponse(
                results=[
                    pb.FuzzyMatchResponse(
                        matches=[
                            pb.FuzzyMatch(
                                candidate_id="HL-1001",
                                candidate="Aurora",
                                score=score,
                                source=pb.MEMBER,
                                category="er.entity.item.product_name",
                            )
                        ]
                    )
                ]
            )

    unit = Unit(id=1, content_text="Aurora")
    mention = annotation(unit, "Mention", {"kind": "item", "surface": "Aurora"})
    ctx = replace(context("review-001.en.json", "link-mentions"), units={1: unit}, annotations=[mention])
    result = Link(Lex(), with_page([unit], "cn.Item", CLOSE)).run(ctx)
    decision = next(a.payload() for a in result.annotations if a.type == "Decision")
    assert ("chosen" in decision) == chosen and decision["score"] == pytest.approx(score)
    assert bool(linked(result)) == chosen
    if chosen:
        assert result.annotations[0].payload()["linkMethod"] == "both"


@pytest.mark.parametrize("concept,chosen", [("cn.Customer", False), ("cn.Item", True)])
def test_knn_respects_family_targets(concept: str, chosen: bool) -> None:
    """A page at a cosine ≥ `abstain_below` links the empty-lexicon mention by kNN alone, but only to a family target."""

    class EmptyLex:
        def batch(self, queries: list[pb.SpanQuery]) -> pb.BatchMatchResponse:
            return pb.BatchMatchResponse(results=[pb.FuzzyMatchResponse()])

    unit = Unit(id=1, content_text="Aurora")
    mention = annotation(unit, "Mention", {"kind": "item", "surface": "Aurora"})
    ctx = replace(context("review-001.en.json", "link-mentions"), units={1: unit}, annotations=[mention])
    result = Link(EmptyLex(), with_page([unit], concept, CLOSE)).run(ctx)
    assert bool(linked(result)) == chosen
    if chosen:
        assert result.annotations[0].payload()["linkMethod"] == "knn"


def test_a_page_never_lifts_its_concept_over_that_concepts_member() -> None:
    """review-244 F2: a member at 0.9 and a VOCABULARY hit on the same concept at 0.5; a concept page at a higher cosine
    corroborates the member and leaves the concept-only candidate where the lexicon put it, so the member still wins."""

    class Lex:
        def batch(self, queries: list[pb.SpanQuery]) -> pb.BatchMatchResponse:
            member = pb.FuzzyMatch(
                candidate_id="HL-1001", candidate="Aurora", score=0.9, source=pb.MEMBER, category="er.entity.item.name"
            )
            word = pb.FuzzyMatch(
                candidate_id="lamp",
                candidate="lamp",
                score=0.5,
                source=pb.VOCABULARY,
                category="er.entity.item",
                target_ref="er.entity.item",
            )
            return pb.BatchMatchResponse(results=[pb.FuzzyMatchResponse(matches=[member, word])])

    unit = Unit(id=1, content_text="Aurora lamp")
    mention = annotation(unit, "Mention", {"kind": "item", "surface": "Aurora lamp"})
    ctx = replace(context("review-001.en.json", "link-mentions"), units={1: unit}, annotations=[mention])
    result = Link(Lex(), with_page([unit], "cn.Item", 0.95)).run(ctx)
    linked = next(a.payload() for a in result.annotations if a.type == "Mention")
    assert linked["member"]["id"] == "HL-1001" and linked["linkMethod"] == "both"
    concept_only = next(c for c in linked["candidates"] if c["code"] == "cn.Item")
    assert concept_only["score"] == 0.5 and concept_only["source"] == "lexicon"


class _Member:
    """lex-matcher's answer for "Aurora": member HL-1001 of `cn.Item` at 0.9."""

    def batch(self, queries: list[pb.SpanQuery]) -> pb.BatchMatchResponse:
        member = pb.FuzzyMatch(
            candidate_id="HL-1001", candidate="Aurora", score=0.9, source=pb.MEMBER, category="er.entity.item.name"
        )
        return pb.BatchMatchResponse(results=[pb.FuzzyMatchResponse(matches=[member]) for _ in queries])


@pytest.mark.parametrize(
    "cosine,method", [(0.03, "lexicon"), (CLOSE, "both")], ids=["page-below-the-floor", "page-at-or-above-it"]
)
def test_a_page_below_abstain_below_is_no_evidence(cosine: float, method: str) -> None:
    """✅R244-E: a page whose cosine is below `abstain_below` does not corroborate. findSimilar's own `min-score` is 0,
    so any page in the top k used to turn a member's link into `both`; at or above the floor it still does."""
    unit = Unit(id=1, content_text="Aurora")
    mention = annotation(unit, "Mention", {"kind": "item", "surface": "Aurora"})
    ctx = replace(context("review-001.en.json", "link-mentions"), units={1: unit}, annotations=[mention])
    store = with_page([unit], "cn.Item", cosine)
    linked = next(a.payload() for a in Link(_Member(), store).run(ctx).annotations if a.type == "Mention")
    assert linked["member"]["id"] == "HL-1001" and linked["linkMethod"] == method
    assert [(c["code"], c["source"]) for c in linked["candidates"]] == [("HL-1001", method)]
    assert len(store.page_reads) == (0 if method == "lexicon" else 1)


def test_two_pages_without_a_lexicon_option_are_knn_not_both() -> None:
    """✅R244-E: with an empty lexicon, the first `cn.Item` page (compile grounds ENTITY drafts too) proposes the concept
    as `knn`; a second page finds that key and only raises its score. `both` needs the lexicon to have proposed it."""
    unit = Unit(id=1, content_text="Aurora")
    mention = annotation(unit, "Mention", {"kind": "item", "surface": "Aurora"})
    ctx = replace(context("review-001.en.json", "link-mentions"), units={1: unit}, annotations=[mention])
    store = with_page([unit], "cn.Item", 0.65, page_id=9002)
    store.similar = [page_hit(9001, 0.70), *store.similar]
    store.pages[9001] = concept_page(9001, "cn.Item")
    linked = next(a.payload() for a in Link(_Lex(), store).run(ctx).annotations if a.type == "Mention")
    assert linked["concept"] == "cn.Item" and linked["linkMethod"] == "knn"
    assert [(c["code"], c["score"], c["source"]) for c in linked["candidates"]] == [("cn.Item", 0.70, "knn")]


@pytest.mark.parametrize("cosine", [None, REALISTIC], ids=["no-concept-page", "concept-page-at-a-realistic-cosine"])
def test_every_real_hartland_replay_abstains(cosine: float | None) -> None:
    """The 11 real Hartland replays abstain, and still do beside a `cn.Item` concept page at a realistic cosine
    (review-244 F1: at the store's old normalized 1.0, five of them linked by kNN). Below `abstain_below` that page is
    no evidence at all (✅R244-E): no kNN candidate, and it is never read."""
    from google.protobuf.json_format import ParseDict

    replay = RecordedLexMatcher("hartland.json")
    assert len(replay.rows) == 11
    for row in replay.rows:

        class Lex:
            def batch(self, queries: list[pb.SpanQuery]) -> pb.BatchMatchResponse:
                return pb.BatchMatchResponse(results=[ParseDict(row["response"], pb.FuzzyMatchResponse())])  # noqa: B023

        unit = Unit(id=1, content_text=row["query"])
        mention = annotation(unit, "Mention", {"kind": "item", "surface": row["query"]})
        ctx = replace(context("review-001.en.json", "link-mentions"), units={1: unit}, annotations=[mention])
        store = FakeWarehouse([unit]) if cosine is None else with_page([unit], "cn.Item", cosine)
        result = Link(Lex(), store).run(ctx)
        assert result.abstained and not linked(result), row["query"]
        assert len(store.similar_calls) == 1 and store.page_reads == []
        logged = [c for item in result.sections["candidates"]["items"] for c in item["items"]]
        assert all(c["source"] == "lexicon" for c in logged), row["query"]


class _Lex:
    def batch(self, queries: list[pb.SpanQuery]) -> pb.BatchMatchResponse:
        return pb.BatchMatchResponse(results=[pb.FuzzyMatchResponse() for _ in queries])


def _two_mentions(source_id: int = 0) -> tuple[Unit, RunContext]:
    unit = Unit(id=1, source_id=source_id, content_text="Aurora broke. The Aurora lamp.")
    mentions = [
        annotation(unit, "Mention", {"kind": "item", "surface": "Aurora"}, [Span(unit_id=1, start=0, end=6)]),
        annotation(unit, "Mention", {"kind": "item", "surface": "Aurora"}, [Span(unit_id=1, start=18, end=24)]),
    ]
    return unit, replace(context("review-001.en.json", "link-mentions"), units={1: unit}, annotations=mentions)


def test_knn_reads_the_mart_once_each_sentence_once_each_page_once_and_never_leaves_the_mart() -> None:
    unit, ctx = _two_mentions(source_id=7)
    # a third mention in an already-asked sentence ("The Aurora lamp."): no second /findSimilar (review-244 F7)
    lamp = annotation(unit, "Mention", {"kind": "item", "surface": "lamp"}, [Span(unit_id=1, start=25, end=29)])
    ctx = replace(ctx, annotations=[*ctx.annotations, lamp])
    store = with_page([unit], "cn.Item", CLOSE)
    # another version of the document, in another mart: the mention's own source decides
    store.sources = [
        {"id": 3, "notebookId": "hartland-claims"},
        {"id": 7, "notebookId": "hartland-public", "metadata": {"documentId": {"single": "reviews:HL-1001:2026-09"}}},
    ]
    result = Link(_Lex(), store).run(ctx)
    assert store.source_reads == 1 and store.page_reads == [(9001, "hartland-public")]
    assert [c["notebookId"] for c in store.similar_calls] == ["hartland-public", "hartland-public"]
    assert [c["query"] for c in store.similar_calls] == ["Aurora broke.", "The Aurora lamp."]
    linked = [a for a in result.annotations if a.type == "Mention"]
    assert len(linked) == 3 and {a.payload()["linkMethod"] for a in linked} == {"knn"}


@pytest.mark.parametrize(
    "sources",
    [
        [{"id": 7}],  # a store older than 0.11.13 names no mart, or the source is in none / in several
        [{"id": 3, "notebookId": "hartland-public"}],  # the unit's own source is not listed
        [],
    ],
)
def test_a_source_without_a_single_mart_is_an_error_not_a_silent_no_knn(sources: list[dict[str, object]]) -> None:
    unit, ctx = _two_mentions(source_id=7)
    store = with_page([unit], "cn.Item", CLOSE)
    store.sources = sources
    with pytest.raises(ValueError, match="link/no-mart: source 7 of reviews:HL-1001:2026-09"):
        Link(_Lex(), store).run(ctx)
    assert store.similar_calls == []


def test_no_concept_page_yet_links_by_lexicon_alone_and_lexicon_only_reads_no_store() -> None:
    """The first bp-dsk pass (W5 log): compile has written no concept page, so the kNN answers `chunks: []` and every
    link is `lexicon` — S3.3's "absent pages = no kNN candidates, never an error"."""
    ctx = context("review-001.en.json", "cz-mentions")
    with recorded_nlp() as nlp:
        mentions = [a for a in NlpPipeline(nlp).run(ctx).annotations if a.type == "Mention"]
    linkctx = replace(context("review-001.en.json", "link-mentions"), annotations=mentions)
    store = FakeWarehouse(list(ctx.units.values()))
    with recorded_lexmatcher() as lex:
        result = Link(lex, store).run(linkctx)
    assert len(store.similar_calls) == len(mentions) and store.page_reads == []
    assert {a.payload()["linkMethod"] for a in result.annotations if a.type == "Mention"} == {"lexicon"}
    lexicon_only = new_context(linkctx, kind="link", engine={**linkctx.manifest["engine"], "candidates": ["lexicon"]})
    quiet = FakeWarehouse(list(ctx.units.values()))
    with recorded_lexmatcher() as lex:
        Link(lex, quiet).run(lexicon_only)
    assert quiet.source_reads == 0 and quiet.similar_calls == []


def test_a_chunk_hit_proposes_no_concept_and_reads_no_page() -> None:
    """A hit without `pageId` is a chunk (the live bp-dsk answer for `nodeKinds: [chunk]`, 2026-11-11): never a page."""
    unit, ctx = _two_mentions()
    store = with_page([unit], "cn.Item", CLOSE)
    chunk = page_hit(9001, 0.95)
    chunk["pageId"] = chunk["citation"]["pageId"] = None
    chunk.update(chunkId=319, unitId=208, partId=208, span={"unitId": 208, "start": 0, "end": 6})
    store.similar = [chunk, *store.similar]
    result = Link(_Lex(), store).run(ctx)
    assert store.page_reads == [(9001, "hartland-public")]
    candidates = [c for item in result.sections["candidates"]["items"] for c in item["items"]]
    assert {c["score"] for c in candidates} == {CLOSE}


class _Ambiguous:
    """lex-matcher's answer for "Aurora": three `cn.Item` members, every one below `abstain_below`, out of score
    order."""

    def batch(self, queries: list[pb.SpanQuery]) -> pb.BatchMatchResponse:
        hits = [
            pb.FuzzyMatch(candidate_id=i, candidate=n, score=s, source=pb.MEMBER, category="er.entity.item.name")
            for i, n, s in [
                ("HL-1002", "Aurora mini", 0.31),
                ("HL-1001", "Aurora", 0.45),
                ("HL-1003", "Aurora XL", 0.38),
            ]
        ]
        return pb.BatchMatchResponse(results=[pb.FuzzyMatchResponse(matches=hits) for _ in queries])


# What cz-mentions writes (nlp_pipeline): every gazetteer Mention carries its Lookup's `concept`, no `member` (review-381 F1).
def cz_mention(surface: str, **extra: object) -> dict[str, object]:
    return {
        "kind": "item",
        "surface": surface,
        "linkMethod": "gazetteer",
        "candidates": [],
        "concept": "cn.Item",
        **extra,
    }


def _aurora(
    engine: dict[str, object] | None = None, payload: dict[str, object] | None = None
) -> tuple[Unit, Annotation, RunContext]:
    unit = Unit(id=1, content_text="Aurora")
    payload = payload or {"kind": "item", "surface": "Aurora"}
    mention = annotation(unit, "Mention", payload, [Span(unit_id=1, start=0, end=6)])
    mention.id = 7
    ctx = replace(context("review-001.en.json", "link-mentions"), units={1: unit}, annotations=[mention])
    if engine is not None:
        ctx = new_context(ctx, kind="link", engine={**ctx.manifest["engine"], **engine})
    return unit, mention, ctx


@pytest.mark.parametrize(
    "engine,codes,payload",
    [
        ({}, ["HL-1001", "HL-1003", "HL-1002"], None),  # default 5, three found
        ({"candidates_on_abstain": 2}, ["HL-1001", "HL-1003"], None),
        ({"candidates_on_abstain": 5, "k": 1}, ["HL-1001"], None),  # never more than the linker ranked (k)
        ({}, ["HL-1001", "HL-1003", "HL-1002"], cz_mention("Aurora", lemma="aurora")),  # review-381 F1
    ],
    ids=["default", "on-abstain-2", "k-1", "cz-mentions-shaped"],
)
def test_an_abstaining_link_writes_its_top_candidates_on_a_derived_mention(
    engine: dict[str, object], codes: list[str], payload: dict[str, object] | None
) -> None:
    """DX-D42 (S4.1f): link-pick reads candidates off a Mention, so an abstaining linker writes one: no `concept`, no
    `member`, `linkMethod: abstain`, its top min(candidates_on_abstain, k, found) candidates by score, each with its
    source. The abstaining Decision is unchanged and the run still abstains."""
    _, mention, ctx = _aurora(engine, payload)
    result = Link(_Ambiguous(), FakeWarehouse(list(ctx.units.values()))).run(ctx)
    assert result.abstained
    [abstained] = [a for a in result.annotations if a.type == "Mention"]
    written = abstained.payload()
    assert "concept" not in written and "member" not in written and written["linkMethod"] == "abstain"
    assert written["surface"] == "Aurora" and written["kind"] == "item"
    assert [c["code"] for c in written["candidates"]] == codes
    assert [c["score"] for c in written["candidates"]] == sorted(
        (c["score"] for c in written["candidates"]), reverse=True
    )
    assert all(c["source"] == "lexicon" and c["system"] == "cn.Item" for c in written["candidates"])
    assert abstained.derived_from == [mention.id] and abstained.spans == mention.spans
    registry().schema_for("Mention").validate(written)
    decision = next(a.payload() for a in result.annotations if a.type == "Decision")
    assert "chosen" not in decision and decision["score"] == 0.45


def test_a_margin_abstain_on_a_cz_mentions_mention_drops_its_concept_so_link_pick_sees_it() -> None:
    """review-381 F1: cz-mentions gives every Mention `concept: cn.Item`. A margin abstain's best is ≥ `abstain_below`,
    and link-pick skips a Mention that has a concept and such a best as a confident link (LinkPickKind band rule). So
    the derived Mention drops the input's `concept` and `member`; its candidates are already that concept's."""

    class Tie:
        def batch(self, queries: list[pb.SpanQuery]) -> pb.BatchMatchResponse:
            hits = [
                pb.FuzzyMatch(
                    candidate_id=f"MX-{n}",
                    candidate=f"Voltaic Sluchátka MX-{n}",
                    score=1.0058,
                    source=pb.MEMBER,
                    category="er.entity.item.name",
                )
                for n in range(5)
            ]
            return pb.BatchMatchResponse(results=[pb.FuzzyMatchResponse(matches=hits) for _ in queries])

    unit = Unit(id=1, content_text="sluchátka")
    mention = annotation(unit, "Mention", cz_mention("sluchátka", lemma="sluchátko"), [Span(unit_id=1, start=0, end=9)])
    mention.id = 7
    ctx = replace(context("review-001.en.json", "link-mentions"), units={1: unit}, annotations=[mention])
    result = Link(Tie(), FakeWarehouse([unit])).run(ctx)
    assert result.abstained
    [abstained] = [a for a in result.annotations if a.type == "Mention"]
    payload = abstained.payload()
    assert "concept" not in payload and "member" not in payload and payload["linkMethod"] == "abstain"
    assert payload["lemma"] == "sluchátko" and len(payload["candidates"]) == 5
    assert max(c["score"] for c in payload["candidates"]) >= ctx.manifest["engine"].get("abstain_below", 0.6)
    assert {c["system"] for c in payload["candidates"]} == {"cn.Item"}
    registry().schema_for("Mention").validate(payload)


def test_a_margin_abstain_carries_both_sources() -> None:
    """Two options too close to choose between (margin < 0.05): a lexicon member of one target and a concept page of
    another. Both are ≥ `abstain_below`, so the kNN one survives `_knn`'s floor and keeps its `knn` source."""

    class Lex:
        def batch(self, queries: list[pb.SpanQuery]) -> pb.BatchMatchResponse:
            hit = pb.FuzzyMatch(
                candidate_id="C-7",
                candidate="Aurora s.r.o.",
                score=0.64,
                source=pb.MEMBER,
                category="er.entity.customer.name",
            )
            return pb.BatchMatchResponse(results=[pb.FuzzyMatchResponse(matches=[hit])])

    unit, _, ctx = _aurora({"targets": {"er.entity.item": "cn.Item", "er.entity.customer": "cn.Customer"}})
    result = Link(Lex(), with_page([unit], "cn.Item", 0.62)).run(ctx)
    assert result.abstained
    [abstained] = [a for a in result.annotations if a.type == "Mention"]
    assert abstained.payload()["linkMethod"] == "abstain" and "concept" not in abstained.payload()
    assert [(c["system"], c["code"], c["score"], c["source"]) for c in abstained.payload()["candidates"]] == [
        ("cn.Customer", "C-7", 0.64, "lexicon"),
        ("cn.Item", "cn.Item", 0.62, "knn"),
    ]


def test_candidates_on_abstain_zero_is_todays_abstain_and_no_candidates_writes_no_mention() -> None:
    """`candidates_on_abstain: 0` restores the pre-S4.1f output exactly: one abstaining Decision, no Mention. A mention
    the linker found nothing for gets no Mention either: an empty candidate list gives link-pick nothing to pick."""
    _, _, ctx = _aurora({"candidates_on_abstain": 0})
    result = Link(_Ambiguous(), FakeWarehouse(list(ctx.units.values()))).run(ctx)
    assert result.abstained and [a.type for a in result.annotations] == ["Decision"]
    _, _, ctx = _aurora()
    result = Link(_Lex(), FakeWarehouse(list(ctx.units.values()))).run(ctx)
    assert result.abstained and [a.type for a in result.annotations] == ["Decision"]


@pytest.mark.parametrize("bad", [-1, 1.5, "5", True])
def test_candidates_on_abstain_must_be_a_non_negative_integer(bad: object) -> None:
    _, _, ctx = _aurora({"candidates_on_abstain": bad})
    with pytest.raises(ValueError, match="link/invalid-candidates-on-abstain"):
        Link(_Ambiguous(), FakeWarehouse(list(ctx.units.values()))).run(ctx)


def test_candidates_on_abstain_five_point_zero_is_five() -> None:
    """review-381 F2: the manifest schema's `integer` accepts 5.0, so the linker reads it as 5, never as a failed run."""
    outputs = []
    for value in (5, 5.0):
        _, _, ctx = _aurora({"candidates_on_abstain": value})
        outputs.append(
            [a.dump() for a in Link(_Ambiguous(), FakeWarehouse(list(ctx.units.values()))).run(ctx).annotations]
        )
    assert outputs[0] == outputs[1] and len(outputs[0]) == 2


@pytest.mark.parametrize("doc", ["review-001.en.json", "review-002.cs.json", "claim-001.cs.txt", "claim-002.en.md"])
def test_confident_links_are_unchanged_by_candidates_on_abstain(doc: str) -> None:
    """S4.1f T1(d): every PX mention links, and the output is the same bytes with the knob at its default and at 0
    (the goldens themselves are untouched by S4.1f)."""
    ctx = context(doc, "cz-mentions")
    with recorded_nlp() as nlp:
        mentions = [a for a in NlpPipeline(nlp).run(ctx).annotations if a.type == "Mention"]
    for n, a in enumerate(mentions):
        a.id = 100 + n
    linkctx = replace(context(doc, "link-mentions"), annotations=mentions)
    off = new_context(linkctx, kind="link", engine={**linkctx.manifest["engine"], "candidates_on_abstain": 0})
    outputs = []
    for c in (linkctx, off):
        with recorded_lexmatcher() as lex:
            outputs.append(Link(lex, FakeWarehouse(list(ctx.units.values()))).run(c).annotations)
    assert [golden_rows(doc, o, ctx.units) for o in outputs[:1]] == [golden_rows(doc, outputs[1], ctx.units)]
    assert all(a.payload().get("linkMethod") != "abstain" for a in outputs[0])


LINK_PICK_INPUT = WORKER / "tests/fixtures/contracts/link.abstained.json"


def link_pick_input() -> dict[str, list[dict[str, Any]]]:
    """What link-pick reads after this linker ran (S4.1f T3, mirrored byte for byte into kantheon's
    `shared/proto/fixtures/dx/`): the Mentions of two real Hartland replays (hartland.json) the linker abstains on, a
    five-way tie at 1.006 ("sluchátka", a margin abstain) and a best of 0.348 ("Aurora lamp", below `abstain_below`),
    then the first PX mention it links (review-002), all as the store lists them (ids, producer, run)."""
    from google.protobuf.json_format import ParseDict

    rows = {row["query"]: row for row in RecordedLexMatcher("hartland.json").rows}
    out: list[tuple[Annotation, RunContext]] = []
    for n, query in enumerate(["sluchátka", "Aurora lamp"]):

        class Lex:
            def batch(self, queries: list[pb.SpanQuery]) -> pb.BatchMatchResponse:
                return pb.BatchMatchResponse(results=[ParseDict(rows[query]["response"], pb.FuzzyMatchResponse())])  # noqa: B023

        unit = Unit(id=201 + n, content_text=query)
        mention = annotation(unit, "Mention", cz_mention(query), [Span(unit_id=unit.id, start=0, end=len(query))])
        mention.id = 5001 + n
        ctx = replace(context("review-001.en.json", "link-mentions"), units={unit.id: unit}, annotations=[mention])
        out += [(a, ctx) for a in Link(Lex(), FakeWarehouse([unit])).run(ctx).annotations if a.type == "Mention"]
    doc = "review-002.cs.json"
    px = context(doc, "cz-mentions")
    with recorded_nlp() as nlp:
        mention = next(a for a in NlpPipeline(nlp).run(px).annotations if a.type == "Mention")
    mention.id = 5003
    linkctx = replace(context(doc, "link-mentions"), annotations=[mention])
    with recorded_lexmatcher() as lex:
        out += [
            (a, linkctx)
            for a in Link(lex, FakeWarehouse(list(px.units.values()))).run(linkctx).annotations
            if a.type == "Mention"
        ]
    for n, (a, run) in enumerate(
        out
    ):  # as the runner stamps them (each its own document's run) and the store numbers them
        a.id, a.producer, a.producer_version, a.run_id = (
            7001 + n,
            run.item.family,
            run.item.version_hash,
            run.item.run_id,
        )
    return {"annotations": [a.dump() for a, _ in out]}


def test_the_link_pick_input_fixture_is_this_linkers_output() -> None:
    """kantheon's link-pick spec reads only linker-produced Mentions (S4.1f: no hand-written candidates). Re-record
    with DX_RECORD=1 and copy the file to kantheon's shared/proto/fixtures/dx/ (fixtures-check holds them equal)."""
    import os

    want = link_pick_input()
    methods = [json.loads(a["payloadJson"])["linkMethod"] for a in want["annotations"]]
    assert methods == ["abstain", "abstain", "lexicon"]
    # review-381 F1: the inputs are cz-mentions-shaped (`concept: cn.Item`), the abstain Mentions still carry none.
    assert ["concept" in json.loads(a["payloadJson"]) for a in want["annotations"]] == [False, False, True]
    # review-381 F3: each Mention names its own document's run.
    assert [a["runId"].split("-")[-1].split(".")[0] for a in want["annotations"]] == ["001", "001", "002"]
    if os.environ.get("DX_RECORD"):
        LINK_PICK_INPUT.write_text(canonical(want))
    assert LINK_PICK_INPUT.read_text() == canonical(want)
