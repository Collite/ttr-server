# SPDX-License-Identifier: Apache-2.0
"""Every failed postcondition yields a Verdict, and excludes the offending output."""

from __future__ import annotations

from collections.abc import Callable
from datetime import date
from typing import Any

from docproc.runtime.registry import TypeRegistryClient
from docproc.sdk.models import Annotation, Unit, UnitKind, payload_text


def span_within_unit(annotation: Annotation, units: dict[int, Unit]) -> None:
    if annotation.unit_id not in units:
        raise ValueError(f"unknown unit {annotation.unit_id}")
    for span in annotation.spans:
        unit = units.get(span.unit_id)
        if unit is None or not 0 <= span.start <= span.end <= len(unit.content_text):
            raise ValueError(f"span [{span.start},{span.end}) outside unit {span.unit_id}")


def date_parses(annotation: Annotation, units: dict[int, Unit]) -> None:
    value = annotation.payload().get("value", annotation.payload().get("date"))
    if not isinstance(value, str):
        raise ValueError("missing date string")
    date.fromisoformat(value)


def code_exists_in(annotation: Annotation, units: dict[int, Unit]) -> None:
    raise NotImplementedError("pack vocabulary not yet configured")


class Validators:
    def __init__(self, registry: TypeRegistryClient) -> None:
        self.registry = registry
        self.rules: dict[str, Callable[[Annotation, dict[int, Unit]], None]] = {
            "span_within_unit": span_within_unit,
            "date_parses": date_parses,
        }

    def validate(
        self, annotations: list[Annotation], units: dict[int, Unit], conditions: list[Any]
    ) -> tuple[list[Annotation], list[Annotation]]:
        good, verdicts = [], []
        for n, annotation in enumerate(annotations):
            failures = []
            rules = ["span_within_unit", "schema_valid", *conditions]
            for rule in dict.fromkeys(str(c) for c in rules):
                try:
                    if rule == "schema_valid":
                        self.registry.schema_for(annotation.type).validate(annotation.payload())
                    elif rule.startswith("code_exists_in"):
                        code_exists_in(annotation, units)
                    else:
                        if rule not in self.rules:
                            raise NotImplementedError(f"validator not registered: {rule}")
                        self.rules[rule](annotation, units)
                except Exception as exc:
                    failures.append((rule, str(exc)))
            if not failures:
                good.append(annotation)
            for rule, message in failures:
                # Unknown output unit: anchor the diagnostic to the first known input unit.
                unit_id = annotation.unit_id if annotation.unit_id in units else next(iter(units))
                verdicts.append(
                    Annotation(
                        unit_id=unit_id,
                        type="Verdict",
                        payload_json=payload_text(
                            {"rule": rule, "ok": False, "message": f"output #{n} {annotation.type}: {message}"}
                        ),
                    )
                )
        return good, verdicts

    def validate_chunks(
        self,
        annotations: list[Annotation],
        units: dict[int, Unit],
        engine: dict[str, Any],
        unit_params: dict[int, dict[str, Any]],
    ) -> tuple[list[Annotation], list[Annotation]]:
        """The runtime's mandatory chunk batch checks, including empty output units."""
        from docproc.kinds.chunk.checks import failures
        from docproc.kinds.chunk.types import ChunkSpan
        from docproc.kinds.common import verdict

        good: list[Annotation] = []
        diagnostics: list[Annotation] = []

        expected = [
            uid
            for uid, u in units.items()
            if u.kind != UnitKind.DOCUMENT or not any(v.parent_id == uid for v in units.values())
        ]
        for uid in expected:
            params = unit_params.get(uid, dict(engine.get("params", {})))
            outputs = [a for a in annotations if a.type == "Chunk" and a.unit_id == uid]
            if not outputs and any(
                a.unit_id == uid and a.type == "Verdict" and a.payload().get("ok") is False for a in annotations
            ):
                continue  # The kind already rejected this unit atomically (e.g. plugin exception).
            spans = [
                ChunkSpan(a.spans[0].start, a.spans[0].end, tuple((s.start, s.end) for s in a.spans[1:]), a.payload())
                for a in outputs
                if a.spans
            ]
            bad = failures(units[uid].content_text, spans, params, engine["strategy"])
            if any(not a.spans or any(s.unit_id != uid for s in a.spans) for a in outputs):
                bad.append("span_within_unit")
            if engine["strategy"] == "sentence-window" and outputs and "_sentences" not in params:
                bad.append("overlap_within_declared")
            for a in outputs:
                try:
                    self.registry.schema_for(a.type).validate(a.payload())
                except Exception:
                    bad.append("schema_valid")
                    break
            if bad:
                diagnostics.extend(
                    verdict(uid, rule, "chunk batch rejected for this unit") for rule in dict.fromkeys(bad)
                )
            else:
                good.extend(outputs)
        for a in annotations:
            if a.type == "Chunk" and a.unit_id not in expected:
                diagnostics.append(verdict(next(iter(units)), "span_within_unit", "chunk output outside input units"))
        good.extend(a for a in annotations if a.type != "Chunk")
        return good, diagnostics
