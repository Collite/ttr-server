# SPDX-License-Identifier: Apache-2.0
"""Validated annotation joins and closed templates, never eval or dynamic imports."""

from __future__ import annotations

import json
import re
from importlib.resources import files
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator, ValidationError

from docproc.kinds.common import annotation, verdict
from docproc.runtime.predicate import _MISSING, Predicate, matches_type, path_value
from docproc.runtime.registry import KindResult, RunContext, TypeRegistryClient
from docproc.sdk.models import Annotation


def pack_file(pack: Path | None, name: str) -> Path:
    if pack is None:
        raise ValueError("rule/pack-path-required")
    target = (pack / name).resolve()
    if not target.is_relative_to(pack.resolve()):
        raise ValueError("rule/path-outside-pack")
    return target


def surface(ctx: RunContext, a: Annotation) -> str:
    return "\n".join(ctx.units[s.unit_id].slice(s) for s in a.spans)


def template(value: Any, bound: dict[str, Annotation | None], ctx: RunContext) -> Any:
    if isinstance(value, dict):
        return {k: template(v, bound, ctx) for k, v in value.items()}
    if not isinstance(value, str):
        return value
    if value.startswith("coalesce(") and value.endswith(")"):
        # Arguments are closed references or JSON/scalar literals; commas inside quoted literals are safe.
        body = value[9:-1]
        args = re.split(r',(?=(?:[^"\']|"[^"]*"|\'[^\']*\')*$)', body)
        if len(args) < 2:
            raise ValueError("rule/invalid-coalesce")
        for arg in args:
            arg = arg.strip()
            try:
                literal = json.loads(arg)
            except ValueError:
                literal = arg
            result = template(literal, bound, ctx)
            if result is not None:
                return result
        return None
    m = re.fullmatch(r"\$\{([a-z][a-z0-9_]*)\.(surface|payload(?:\.[a-zA-Z_][a-zA-Z0-9_]*)+)\}", value)
    if m:
        if m[1] not in bound:
            raise ValueError("rule/unknown-binding")
        a = bound[m[1]]
        if a is None:
            return None
        if m[2] == "surface":
            return surface(ctx, a)
        found = path_value(a.payload(), m[2][8:])
        return None if found is _MISSING else found
    if "${" in value or value.startswith("coalesce("):
        raise ValueError("rule/invalid-template")
    return value


def related(ctx: RunContext, anchor: Annotation, other: Annotation, rel: str) -> bool:
    if rel == "same_unit":
        return anchor.unit_id == other.unit_id
    if rel == "same_document":
        return ctx.units[anchor.unit_id].source_id == ctx.units[other.unit_id].source_id
    pairs = [(a, b) for a in anchor.spans for b in other.spans if a.unit_id == b.unit_id]
    if rel == "within":
        return bool(other.spans) and all(
            any(a.unit_id == b.unit_id and a.start <= b.start and b.end <= a.end for a in anchor.spans)
            for b in other.spans
        )
    if rel == "contains":
        return related(ctx, other, anchor, "within")
    if rel == "overlaps":
        return any(a.start < b.end and b.start < a.end for a, b in pairs)
    raise ValueError("rule/invalid-relation")


class RuleDsl:
    def __init__(self, registry: TypeRegistryClient, pack_path: str = "") -> None:
        self.registry = registry
        self.pack = Path(pack_path) if pack_path else None
        self.schema = Draft202012Validator(
            json.loads(files("docproc").joinpath("_data/schemas/rules.schema.json").read_text())
        )

    def run(self, ctx: RunContext, name: str) -> KindResult:
        rules = yaml.safe_load(pack_file(self.pack, name).read_text())
        self.schema.validate(rules)
        result = KindResult()
        ids = set()
        # Syntax gates run before matching; an invalid dormant rule is still an invalid pack.
        for rule in rules["rules"]:
            if rule["id"] in ids:
                raise ValueError("rule/duplicate-id")
            ids.add(rule["id"])
            names = [rule["match"].get("name", "match")] + [b["name"] for b in rule.get("with", [])]
            if len(set(names)) != len(names):
                raise ValueError("rule/duplicate-binding")
            if rule["emit"]["spans"] != "none" and rule["emit"]["spans"] not in names:
                raise ValueError("rule/unknown-span-binding")
            for bind in [rule["match"], *rule.get("with", [])]:
                Predicate(bind.get("where", ""))
            # Preflight all template references, even when the rule matches no input.
            template(rule["emit"]["payload"], dict.fromkeys(names), ctx)
        for rule in rules["rules"]:
            anchor_spec = rule["match"]
            pred = Predicate(anchor_spec.get("where", ""))
            anchors = [
                a
                for a in ctx.annotations
                if matches_type(a, anchor_spec["type"])
                and pred.evaluate([a])
                and ("unit" not in anchor_spec or str(ctx.units[a.unit_id].kind).lower() == anchor_spec["unit"])
            ]
            for anchor in anchors:
                bindings: list[dict[str, Annotation | None]] = [{anchor_spec.get("name", "match"): anchor}]
                for spec in rule.get("with", []):
                    where = Predicate(spec.get("where", ""))
                    candidates = [
                        a
                        for a in ctx.annotations
                        if matches_type(a, spec["type"])
                        and where.evaluate([a])
                        and related(ctx, anchor, a, spec["rel"])
                    ]
                    # Repeated surface mentions of the same linked member within one anchor are one binding.
                    # Distinct members remain distinct; the rule never guesses between them.
                    unique: dict[tuple[Any, ...], Annotation] = {}
                    for candidate in sorted(candidates, key=lambda a: (a.spans[0].start if a.spans else -1, a.id)):
                        p = candidate.payload()
                        member = p.get("member")
                        binding_key = (
                            (candidate.type, p.get("concept"), member.get("category"), member.get("id"))
                            if member
                            else (candidate.id,)
                        )
                        unique.setdefault(binding_key, candidate)
                    choices: list[Annotation | None] = list(unique.values())
                    if not choices and not spec.get("required", True):
                        choices = [None]
                    bindings = [{**b, spec["name"]: a} for b in bindings for a in choices]
                seen = set()
                for bound in bindings:
                    emit = rule["emit"]
                    a = bound.get(emit["spans"])
                    spans = a.spans if a is not None else []
                    payload = template(emit["payload"], bound, ctx)
                    # Model statements carry payload spans as well as their envelope spans.
                    if emit["type"].startswith("Statement["):
                        payload["spans"] = [s.model_dump(by_alias=True) for s in spans]
                    try:
                        self.registry.schema_for(emit["type"]).validate(payload)
                    except (ValidationError, KeyError) as exc:
                        result.verdicts.append(
                            verdict(anchor.unit_id, "rule/schema", f"{rule['id']}: {type(exc).__name__}")
                        )
                        continue
                    out = annotation(anchor.unit_id, emit["type"], payload, spans)
                    out.confidence = emit.get("confidence")
                    out.derived_from = sorted({a.id for a in bound.values() if a is not None})
                    key = (out.payload_json, tuple(out.derived_from), tuple((s.unit_id, s.start, s.end) for s in spans))
                    if key not in seen:
                        result.annotations.append(out)
                        seen.add(key)
        return result
