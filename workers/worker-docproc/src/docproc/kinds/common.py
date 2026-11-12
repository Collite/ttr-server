# SPDX-License-Identifier: Apache-2.0
"""Small shared helpers for structural kinds; no text copies in annotations."""

from __future__ import annotations

from collections.abc import Iterable
from datetime import UTC, datetime
from typing import Any

from docproc.runtime.registry import RunContext
from docproc.sdk.models import Annotation, Source, Span, Unit, UnitKind, payload_text


def meta(item: Unit | Source, name: str, default: str = "") -> str:
    """A single metadata value of a unit or a source; [default] when it is absent or blank."""
    value = item.metadata.get(name)
    return (value.single if value else None) or default


def source_meta(ctx: RunContext, source_id: int, name: str, default: str = "") -> str:
    """A member's `Source.metadata` value, where split writes documentId, version, recordedAt, feed, subjectId and
    lang (T8f W5-4; the DOCUMENT unit carries only title and lang). [default] when the store did not answer the source
    (`RunContext.sources`: a docwh-store before 0.11.14)."""
    source = ctx.sources.get(source_id)
    return meta(source, name, default) if source is not None else default


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


def utc_instant(value: str, code: str) -> datetime:
    """Parse an offset-bearing instant without exposing its value in an exception."""
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise ValueError(f"{code}-invalid") from None
    if parsed.tzinfo is None:
        raise ValueError(f"{code}-offset-required")
    return parsed.astimezone(UTC)
