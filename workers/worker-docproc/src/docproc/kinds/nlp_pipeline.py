# SPDX-License-Identifier: Apache-2.0
"""Declarative conversion of NLP annotation layers into warehouse structural types."""

from __future__ import annotations

import re
import unicodedata
from typing import Any

from docproc.kinds.common import annotation, meta, processing_units, verdict
from docproc.runtime.registry import KindResult, RunContext
from docproc.sdk.models import Span
from docproc.sdk.nlp import Nlp, feature, identities

DEFAULT_MAP: list[dict[str, Any]] = [
    {"from": "Lookup", "where": {"majorType": "item"}, "to": "Mention", "kind": "item", "concept_feature": "concept"},
    {"from": "DATE", "to": "Date"},
    {"from": "Modifier", "to": "Modifier"},
]


def date_precision(value: str) -> str:
    if re.fullmatch(r"\d{4}", value):
        return "year"
    if re.fullmatch(r"\d{4}-\d{2}", value):
        return "month"
    if re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        return "day"
    return "time"


class NlpPipeline:
    def __init__(self, nlp: Nlp) -> None:
        self.nlp = nlp

    def run(self, ctx: RunContext) -> KindResult:
        engine = ctx.manifest["engine"]
        mapping: list[dict[str, Any]] = engine.get("map", DEFAULT_MAP)
        includes = list(dict.fromkeys(rule["from"] for rule in mapping))
        result = KindResult()
        used, ignored = [], 0
        for unit in processing_units(ctx):
            if not unicodedata.is_normalized("NFC", unit.content_text):
                raise ValueError("nlp/non-nfc-unit")
            response = self.nlp.pipeline(unit.content_text, meta(unit, "lang"), engine["pipeline"], includes)
            used.extend(identities(response.used, required=False))
            result.usage.nlp_calls += 1
            seen: set[tuple[str, int, int, str]] = set()
            diagnosed: set[tuple[str, int, int]] = set()
            normalized_dates = {
                (a.char_start, a.char_end)
                for layer in response.document.annotation_sets
                for a in layer.annotations
                if a.type == "DATE"
                and any(feature(a.features[k]) for k in ("normalized_value", "value") if k in a.features)
            }
            for layer in response.document.annotation_sets:
                for source in layer.annotations:
                    features = {k: feature(v) for k, v in source.features.items()}
                    rule = next(
                        (
                            rule
                            for rule in mapping
                            if rule["from"] == source.type
                            and all(features.get(k) == v for k, v in rule.get("where", {}).items())
                        ),
                        None,
                    )
                    if rule is None:
                        ignored += 1
                        continue
                    span = Span(unit_id=unit.id, start=source.char_start, end=source.char_end)
                    surface = unit.slice(span)
                    reported = features.get("surface", features.get("text", features.get("string", surface)))
                    if (
                        response.document.text != unit.content_text
                        or not 0 <= span.start < span.end <= len(unit.content_text)
                        or surface != reported
                    ):
                        diagnostic_key = (source.type, span.start, span.end)
                        if diagnostic_key not in diagnosed:
                            result.verdicts.append(
                                verdict(unit, "nlp/offset-mismatch", f"{source.type} [{span.start},{span.end})")
                            )
                            diagnosed.add(diagnostic_key)
                        continue
                    target = rule["to"]
                    payload: dict[str, Any]
                    if target == "Mention":
                        payload = {
                            "kind": rule.get("kind", features.get("majorType", "item")),
                            "surface": surface,
                            "linkMethod": "gazetteer",
                            "candidates": [],
                        }
                        for key, source_key in [
                            ("lemma", rule.get("lemma_feature", "lemma")),
                            ("concept", rule.get("concept_feature", "concept")),
                        ]:
                            if features.get(source_key):
                                payload[key] = features[source_key]
                    elif target == "Date":
                        value = str(features.get("normalized_value", features.get("value", "")))
                        if not value:
                            if (span.start, span.end) in normalized_dates:
                                continue
                            result.verdicts.append(
                                verdict(unit, "nlp/date-no-value", f"DATE [{span.start},{span.end})")
                            )
                            continue
                        payload = {"value": value, "precision": date_precision(value), "text": surface}
                    elif target == "Modifier":
                        scope = Span(
                            unit_id=unit.id,
                            start=int(features.get("scope_start", span.start)),
                            end=int(features.get("scope_end", span.end)),
                        )
                        if not 0 <= scope.start < scope.end <= len(unit.content_text):
                            result.verdicts.append(verdict(unit, "nlp/offset-mismatch", "Modifier scope outside unit"))
                            continue
                        payload = {
                            "kind": rule.get("kind", features.get("kind", "certainty")),
                            "scope": {"unitId": unit.id, "start": scope.start, "end": scope.end},
                            "trigger": surface,
                        }
                    else:
                        raise ValueError(f"nlp/unsupported-target: {target}")
                    output = annotation(unit, target, payload, [span])
                    output_key = (target, span.start, span.end, output.payload_json)
                    if output_key not in seen:
                        result.annotations.append(output)
                        seen.add(output_key)
        result.sections["nlp-calls"] = {"calls": result.usage.nlp_calls, "used": used, "unmapped": ignored}
        return result
