# SPDX-License-Identifier: Apache-2.0
"""Seven offset-only strategies and the reviewed plugin SPI."""

from __future__ import annotations

import hashlib
from collections.abc import Callable
from typing import Any

from docproc.kinds.chunk import plugins
from docproc.kinds.chunk.checks import failures
from docproc.kinds.chunk.strategies import (
    fixed_tokens,
    page,
    paragraph,
    recursive,
    sentence_window,
    structural,
    table_aware,
)
from docproc.kinds.chunk.tokens import Tokenizer
from docproc.kinds.chunk.types import ChunkSpan, UnitMeta
from docproc.kinds.common import annotation, meta, meta_list, processing_units, verdict
from docproc.runtime.registry import KindResult, RunContext
from docproc.sdk.models import Span, Unit, UnitKind, canonical
from docproc.sdk.nlp import Nlp, identities
from docproc.sdk.store import Warehouse

BUILTINS: dict[str, Callable[[str, UnitMeta, dict[str, Any]], list[ChunkSpan]]] = {
    "paragraph": paragraph.chunk,
    "fixed-tokens": fixed_tokens.chunk,
    "sentence-window": sentence_window.chunk,
    "recursive": recursive.chunk,
    "structural": structural.chunk,
    "page": page.chunk,
    "table-aware": table_aware.chunk,
}


class Chunk:
    def __init__(self, nlp: Nlp, warehouse: Warehouse, pack_path: str = "") -> None:
        self.nlp, self.warehouse, self.pack_path = nlp, warehouse, pack_path

    def _prefix(self, unit: Unit, ctx: RunContext) -> str:
        requested = ctx.manifest["engine"].get("context", {}).get("prefix", [])
        ancestors: list[Unit] = []
        parent = unit.parent_id
        seen = {unit.id}
        while parent is not None:
            if parent in seen:
                raise ValueError("chunk/parent-cycle")
            seen.add(parent)
            ancestor = ctx.units.get(parent) or self.warehouse.get_unit(parent)
            ancestors.insert(0, ancestor)
            parent = ancestor.parent_id
        titles: list[str] = []
        if "doc_title" in requested:
            titles.extend(meta(u, "title") for u in ancestors if u.kind == UnitKind.DOCUMENT)
        if "section_path" in requested:
            path = meta_list(unit, "sectionPath")
            hint = next((a for a in ctx.annotations if a.type == "Section" and a.unit_id == unit.id), None)
            if path and hint and hint.payload().get("title"):
                path[-1] = str(hint.payload()["title"])
            if not path:
                path = [meta(u, "title") for u in ancestors if u.kind != UnitKind.DOCUMENT]
                section = next((a for a in ctx.annotations if a.type == "Section" and a.unit_id == unit.id), None)
                path.append(
                    str(section.payload().get("title", meta(unit, "title"))) if section else meta(unit, "title")
                )
            titles.extend(path)
        return " › ".join(t for t in titles if t)

    def run(self, ctx: RunContext) -> KindResult:
        engine = ctx.manifest["engine"]
        strategy = str(engine["strategy"])
        if strategy in ("semantic", "summary-prefix"):
            raise ValueError("chunk/strategy-not-in-this-release")
        params = dict(engine.get("params", {}))
        params_hash = "sha256:" + hashlib.sha256(canonical(params).encode()).hexdigest()
        result = KindResult()
        used = []
        for unit in processing_units(ctx):
            effective = dict(params)
            effective["partial"] = bool(engine.get("partial") or params.get("partial", False))
            if strategy == "sentence-window":
                response = self.nlp.sentences(unit.content_text, meta(unit, "lang"))
                used.extend(identities(response.used))
                result.usage.nlp_calls += 1
                effective["_sentences"] = [(s.char_start, s.char_end) for s in response.sentences]
                if any(not 0 <= lo < hi <= len(unit.content_text) for lo, hi in effective["_sentences"]):
                    result.verdicts.append(verdict(unit, "nlp/offset-mismatch", "sentence outside unit"))
                    continue
            plugin = strategy.startswith(("plugin:", "pack:"))
            try:
                if plugin:
                    chunker = plugins.load(strategy, self.pack_path)
                    effective["partial"] = bool(effective["partial"] or getattr(chunker, "partial", False))
                    chunks = chunker.chunk(unit.content_text, unit.model_copy(deep=True), dict(effective))
                else:
                    chunks = BUILTINS[strategy](unit.content_text, unit, effective)
                invalid = failures(unit.content_text, chunks, effective, strategy)
            except (Exception, SystemExit) as exc:
                if not plugin:
                    raise
                result.verdicts.append(verdict(unit, "chunk/plugin-error", f"{type(exc).__name__}: {exc}"))
                continue
            if invalid:
                result.verdicts.extend(verdict(unit, rule, "chunk output rejected for this unit") for rule in invalid)
                continue
            result.chunk_params[unit.id] = effective
            tokenizer = Tokenizer(params)
            prefix = self._prefix(unit, ctx)
            previous: ChunkSpan | None = None
            for ordinal, chunk in enumerate(chunks):
                count = tokenizer.count("\n".join(unit.content_text[lo:hi] for lo, hi in chunk.ranges()))
                payload: dict[str, Any] = {
                    **chunk.payload,
                    "strategy": strategy,
                    "paramsHash": params_hash,
                    "ordinal": ordinal,
                    "tokenCount": count,
                    "context": {"parentUnitId": unit.id, **({"prefix": prefix} if prefix else {})},
                }
                if previous and chunk.start < previous.end:
                    payload["overlapWithPrev"] = tokenizer.count(
                        unit.content_text[chunk.start : min(previous.end, chunk.end)]
                    )
                spans = [Span(unit_id=unit.id, start=lo, end=hi) for lo, hi in chunk.ranges()]
                output = annotation(unit, "Chunk", payload, spans)
                output.producer_version = ctx.item.version_hash
                section = next((a for a in ctx.annotations if a.type == "Section" and a.unit_id == unit.id), None)
                output.derived_from = [section.id] if section and section.id else []
                result.annotations.append(output)
                previous = chunk
        if used:
            result.sections["nlp-calls"] = {"calls": result.usage.nlp_calls, "used": used}
        return result
