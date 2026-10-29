# SPDX-License-Identifier: Apache-2.0
"""Deterministic concept/member linking with an explicit margin and abstention."""

from __future__ import annotations

import math
import re
from typing import Any

from docproc._generated.org.tatrman.fuzzy.v1 import fuzzy_pb2 as pb
from docproc.kinds.common import annotation
from docproc.runtime.registry import KindResult, RunContext
from docproc.sdk.lexmatcher import LexMatcher
from docproc.sdk.models import Annotation
from docproc.sdk.store import Warehouse


def sentence(ctx: RunContext, mention: Annotation) -> str:
    unit = ctx.units[mention.unit_id]
    spans = [s for s in mention.spans if s.unit_id == unit.id]
    if not spans:
        return unit.content_text
    start, end = min(s.start for s in spans), max(s.end for s in spans)
    for a in ctx.annotations:
        if a.type == "Sentence":
            for s in a.spans:
                if s.unit_id == unit.id and s.start <= start and s.end >= end:
                    return unit.slice(s)
    # Sentence hints may not be consumed by this family. Conservative punctuation boundaries.
    left = max((m.end() for m in re.finditer(r"[.!?]\s+", unit.content_text[:start])), default=0)
    right = re.search(r"[.!?](?:\s|$)", unit.content_text[end:])
    return unit.content_text[left : end + right.start() + 1 if right else len(unit.content_text)]


class Link:
    def __init__(self, lexmatcher: LexMatcher, warehouse: Warehouse) -> None:
        self.lexmatcher, self.warehouse = lexmatcher, warehouse

    def run(self, ctx: RunContext) -> KindResult:
        engine = ctx.manifest["engine"]
        if engine.get("pick", "rule") != "rule":
            raise ValueError("link/pick-not-in-this-release")
        mentions = [
            a
            for a in ctx.annotations
            if a.type == "Mention" and (not a.payload().get("concept") or not a.payload().get("member"))
        ]
        k = int(engine.get("k", 5))
        if k < 1:
            raise ValueError("link/invalid-k")
        sources = engine.get("candidates", ["lexicon", "knn"])
        if set(sources) - {"lexicon", "knn"}:
            raise ValueError("link/unknown-candidate-source")
        queries = [
            pb.SpanQuery(
                query=a.payload()["surface"],
                categories=engine.get("categories", {}).get(a.payload().get("kind"), []),
                limit=k,
            )
            for a in mentions
        ]
        # An empty category list in lex-matcher means search all. Never allow that leak.
        selected = [n for n, q in enumerate(queries) if q.categories]
        lex: dict[int, pb.FuzzyMatchResponse] = {}
        if "lexicon" in sources and selected:
            response = self.lexmatcher.batch([queries[n] for n in selected])
            if len(response.results) != len(selected) or any(r.is_error for r in response.results):
                raise ValueError("link/invalid-lexmatcher-response")
            lex = dict(zip(selected, response.results, strict=True))
        result = KindResult()
        unmapped = linked = 0
        candidate_log = []
        for n, mention in enumerate(mentions):
            options: dict[tuple[str, str], dict[str, Any]] = {}
            for hit in lex.get(n, pb.FuzzyMatchResponse()).matches:
                target = hit.target_ref if hit.HasField("target_ref") else hit.category.rsplit(".", 1)[0]
                concept = engine.get("targets", {}).get(target)
                if (
                    not concept
                    or not str(concept).startswith("cn.")
                    or mention.payload().get("concept") not in (None, "", concept)
                ):
                    unmapped += 1
                    continue
                if hit.HasField("auto_bindable") and not hit.auto_bindable:
                    continue
                member = (
                    {"category": hit.category, "id": hit.candidate_id, "display": hit.candidate}
                    if hit.source == pb.MEMBER
                    else None
                )
                if member and not member["id"]:
                    continue
                key = (concept, member["id"] if member else "")
                row = {
                    "system": concept,
                    "code": member["id"] if member else concept,
                    "label": hit.candidate,
                    "score": hit.score,
                    "source": "lexicon",
                    "_member": member,
                    "_concept": concept,
                }
                if math.isfinite(hit.score) and (key not in options or hit.score > options[key]["score"]):
                    options[key] = row
            if "knn" in sources:
                reply = self.warehouse.find_similar({"text": sentence(ctx, mention), "nodeKinds": ["page"], "k": k})
                for hit in reply.get("hits", []):
                    page = hit.get("page", hit)
                    ref = page.get("conceptRef", {})
                    concept = ref.get("velesQname", "") if isinstance(ref, dict) else ""
                    if not concept.startswith("cn.") or mention.payload().get("concept") not in (None, "", concept):
                        continue
                    score = float(hit.get("score", 0))
                    if not math.isfinite(score):
                        continue
                    # Concept pages can support an existing member candidate; they do not manufacture a member.
                    keys = [key for key in options if key[0] == concept] or [(concept, "")]
                    for key in keys:
                        if key in options:
                            options[key]["source"] = "both"
                            options[key]["score"] = max(options[key]["score"], score)
                        else:
                            options[key] = {
                                "system": concept,
                                "code": concept,
                                "label": page.get("title", concept),
                                "score": score,
                                "source": "knn",
                                "_member": None,
                                "_concept": concept,
                            }
            ranked = sorted(options.values(), key=lambda c: (-c["score"], c["_concept"], c["code"]))[:k]
            candidates = [{k: v for k, v in c.items() if not k.startswith("_")} for c in ranked]
            top = ranked[0] if ranked else None
            margin = top["score"] - ranked[1]["score"] if top is not None and len(ranked) > 1 else None
            choose = (
                top is not None
                and top["score"] >= float(engine.get("abstain_below", 0.6))
                and (margin is None or margin + 1e-12 >= float(engine.get("margin", 0.05)))
            )
            decision = {
                "decider": "link/rule",
                "score": top["score"] if top else 0,
                "factors": {"annotationId": mention.id, "margin": margin, "candidates": len(candidates)},
            }
            if choose:
                assert top is not None
                linked += 1
                decision["chosen"] = top["code"]
                payload = {
                    **mention.payload(),
                    "concept": top["_concept"],
                    "linkMethod": top["source"],
                    "candidates": candidates,
                }
                if top["_member"]:
                    payload["member"] = top["_member"]
                new = annotation(mention.unit_id, "Mention", payload, mention.spans)
                new.derived_from = [mention.id]
                result.annotations.append(new)
            decided = annotation(mention.unit_id, "Decision", decision, mention.spans)
            decided.derived_from = [mention.id]
            result.annotations.append(decided)
            candidate_log.append({"annotationId": mention.id, "items": candidates})
        result.abstained = bool(mentions) and linked == 0
        result.sections = {
            "candidates": {"items": candidate_log, "unmapped": unmapped},
            "decisions": {"annotationIds": [a.derived_from[0] for a in result.annotations if a.type == "Decision"]},
        }
        return result
