# SPDX-License-Identifier: Apache-2.0
"""Copy-forward evidence: align the previous version's sections and report changes."""

from __future__ import annotations

from difflib import SequenceMatcher
from typing import Any

from docproc.kinds.common import annotation, meta, meta_list, sections, utc_instant
from docproc.runtime.registry import KindResult, RunContext
from docproc.sdk.models import Unit, UnitKind
from docproc.sdk.store import Warehouse


def source_value(source: dict[str, Any], name: str) -> str:
    value = source.get("metadata", {}).get(name, source.get(name, ""))
    return str(value.get("single", "")) if isinstance(value, dict) else str(value)


def title_path(unit: Unit, units: dict[int, Unit]) -> tuple[str, ...]:
    if path := meta_list(unit, "sectionPath"):
        return tuple(path)
    path = [meta(unit, "title")]
    parent = unit.parent_id
    seen = {unit.id}
    while parent in units and parent not in seen:
        seen.add(parent)
        ancestor = units[parent]
        if ancestor.kind != UnitKind.DOCUMENT:
            path.insert(0, meta(ancestor, "title"))
        parent = ancestor.parent_id
    return tuple(path)


class Diff:
    def __init__(self, warehouse: Warehouse) -> None:
        self.warehouse = warehouse

    def run(self, ctx: RunContext) -> KindResult:
        current = sections(ctx)
        if not current:
            current = list(ctx.units.values())
        sources = self.warehouse.sources_of_document(ctx.item.document_id)
        source_id = current[0].source_id
        this_source = next((s for s in sources if int(s["id"]) == source_id), None)
        if this_source is None:
            root = next((u for u in ctx.units.values() if u.kind == UnitKind.DOCUMENT), current[0])
            recorded_at = meta(root, "recordedAt")
        else:
            recorded_at = source_value(this_source, "recordedAt")
        older = [
            s
            for s in sources
            if int(s["id"]) != source_id
            and source_value(s, "documentId") == ctx.item.document_id
            and source_value(s, "recordedAt")
            and recorded_at
            and utc_instant(source_value(s, "recordedAt"), "diff/recorded-at")
            < utc_instant(recorded_at, "diff/recorded-at")
        ]
        previous = (
            max(older, key=lambda s: (utc_instant(source_value(s, "recordedAt"), "diff/recorded-at"), int(s["id"])))
            if older
            else None
        )
        old_units = {u.id: u for u in self.warehouse.units_of_source(int(previous["id"]))} if previous else {}
        candidates = {u.id: u for u in old_units.values() if u.kind == UnitKind.SECTION}
        if not candidates:
            candidates = {u.id: u for u in old_units.values() if u.kind == UnitKind.DOCUMENT}
        result = KindResult()
        for unit in current:
            match = next(
                (
                    u
                    for u in candidates.values()
                    if u.kind == unit.kind
                    and (
                        u.idx == unit.idx
                        if unit.kind == UnitKind.DOCUMENT
                        else (title_path(u, old_units), u.idx) == (title_path(unit, ctx.units), unit.idx)
                    )
                ),
                None,
            )
            if match is None and unit.content_hash:
                match = next(
                    (u for u in candidates.values() if u.kind == unit.kind and u.content_hash == unit.content_hash),
                    None,
                )
            payload: dict[str, Any] = {"status": "new"}
            if match:
                candidates.pop(match.id)
                identical = unit.content_text == match.content_text
                payload = {"status": "identical" if identical else "modified", "previousUnitId": match.id}
                if not identical:
                    payload["similarity"] = SequenceMatcher(
                        None, match.content_text, unit.content_text, autojunk=False
                    ).ratio()
            result.annotations.append(annotation(unit, "UnitDiff", payload))
        result.sections["decisions"] = {
            "previousSourceId": int(previous["id"]) if previous else None,
            "removedUnitIds": list(candidates),
        }
        return result
