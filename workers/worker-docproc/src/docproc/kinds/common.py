# SPDX-License-Identifier: Apache-2.0
"""Small shared helpers for structural kinds; no text copies in annotations."""

from __future__ import annotations

from collections.abc import Iterable
from typing import Any

from docproc.runtime.registry import RunContext
from docproc.sdk.models import Annotation, Span, Unit, UnitKind, payload_text


def meta(unit: Unit, name: str, default: str = "") -> str:
    value = unit.metadata.get(name)
    return (value.single if value else None) or default


def meta_list(unit: Unit, name: str) -> list[str]:
    value = unit.metadata.get(name)
    return list(value.list.values) if value and value.list else []


def sections(ctx: RunContext) -> list[Unit]:
    return [u for u in ctx.units.values() if u.kind != UnitKind.DOCUMENT]


def processing_units(ctx: RunContext) -> list[Unit]:
    # Sections can own intro text and have child sections. Only a DOCUMENT
    # container is skipped; a document without sections is itself processed.
    return [
        u
        for u in ctx.units.values()
        if u.kind != UnitKind.DOCUMENT or not any(v.parent_id == u.id for v in ctx.units.values())
    ]


def annotation(unit: Unit | int, type_: str, payload: dict[str, Any], spans: Iterable[Span] = ()) -> Annotation:
    return Annotation(
        unit_id=unit.id if isinstance(unit, Unit) else unit,
        type=type_,
        spans=list(spans),
        payload_json=payload_text(payload),
    )


def verdict(unit: Unit | int, rule: str, message: str) -> Annotation:
    return annotation(unit, "Verdict", {"rule": rule, "ok": False, "message": message})
