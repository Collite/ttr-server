# SPDX-License-Identifier: Apache-2.0
"""Ordered deterministic section routing labels (the graph evaluates `when`)."""

from __future__ import annotations

import csv
import re
import unicodedata
from pathlib import Path
from typing import Any

from docproc.kinds.common import annotation, meta, sections
from docproc.runtime.registry import KindResult, RunContext
from docproc.sdk.models import Unit


class Classify:
    def __init__(self, pack_path: str = "") -> None:
        self.pack_path = Path(pack_path) if pack_path else None

    def _contains(self, unit: Unit, paths: list[str]) -> bool:
        if self.pack_path is None:
            raise ValueError("classify/pack-path-required")
        text = unit.content_text.casefold()
        for name in paths:
            path = (self.pack_path / name).resolve()
            if not path.is_relative_to(self.pack_path.resolve()):
                raise ValueError("classify/gazetteer-outside-pack")
            with path.open() as stream:
                if path.suffix == ".tsv":
                    terms = [row["surface"] for row in csv.DictReader(stream, delimiter="\t")]
                else:
                    terms = [line.strip() for line in stream if line.strip() and not line.startswith("#")]
            if any(unicodedata.normalize("NFC", term).casefold() in text for term in terms):
                return True
        return False

    def _matches(self, unit: Unit, rule: dict[str, Any]) -> bool:
        heading = rule.get("heading", rule.get("when_heading"))
        if (
            heading is not None
            and re.search(str(heading), unicodedata.normalize("NFC", meta(unit, "title")), re.IGNORECASE | re.UNICODE)
            is None
        ):
            return False
        if "when_field" in rule and meta(unit, "field") != rule["when_field"]:
            return False
        if any(meta(unit, k) != str(v) for k, v in rule.get("metadata", {}).items()):
            return False
        if "lang" in rule and meta(unit, "lang") != rule["lang"]:
            return False
        return "contains_any" not in rule or self._contains(unit, rule["contains_any"])

    def run(self, ctx: RunContext) -> KindResult:
        engine = ctx.manifest["engine"]
        result = KindResult()
        for unit in sections(ctx):
            kind, matched = str(engine.get("default", "other")), "default"
            for n, rule in enumerate(engine.get("rules", [])):
                if self._matches(unit, rule):
                    kind, matched = rule["kind"], str(rule.get("name", f"rule:{n}"))
                    break
            result.annotations.append(annotation(unit, "SectionKind", {"kind": kind, "rule": matched}))
        return result
