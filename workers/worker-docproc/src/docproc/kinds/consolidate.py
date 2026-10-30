# SPDX-License-Identifier: Apache-2.0
"""Linear v1 document normalization and subject consolidation; no world-DB writes.

The renderer's Record payload currently has no recordKey. Stable keys and identity
are persisted in companion Decisions and read by ETL; schemas remain unmodified.
"""

from __future__ import annotations

import hashlib
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any

import yaml
from jsonschema import ValidationError

from docproc.kinds.common import annotation, meta, utc_instant, verdict
from docproc.kinds.rule_dsl import pack_file, related
from docproc.runtime.predicate import _MISSING, path_value
from docproc.runtime.registry import KindResult, RunContext, TypeRegistryClient
from docproc.sdk.models import Annotation, ListAnnotationsRequest, Span, Unit, UnitKind, canonical
from docproc.sdk.store import Warehouse

ROLE_RANK = ("restates", "confirms", "updates", "contradicts")


def digest(*parts: Any) -> str:
    return hashlib.sha256(canonical(list(parts)).encode()).hexdigest()


def subject_scope(ctx: RunContext) -> tuple[str, list[Unit]]:
    roots = [u for u in ctx.units.values() if u.kind == UnitKind.DOCUMENT]
    subjects = {meta(u, "subjectId") for u in roots}
    if not roots or len(subjects) != 1 or not next(iter(subjects)):
        raise ValueError("consolidate/single-subject-required")
    if any(u.source_id not in {r.source_id for r in roots} for u in ctx.units.values()):
        raise ValueError("consolidate/unit-outside-subject")
    return next(iter(subjects)), roots


def record_decision(record: Annotation, decisions: list[Annotation], subject: str) -> dict[str, Any]:
    evidence = sorted(e["annotationId"] for e in record.payload()["evidence"])
    found = [
        a.payload()
        for a in decisions
        if a.type == "Decision"
        and a.payload().get("decider") == "consolidate/key"
        and a.payload().get("factors", {}).get("recordType") == record.type
        and a.payload()["factors"].get("subjectId") == subject
        and a.payload()["factors"].get("recordEvidence") == evidence
    ]
    if len(found) != 1 or not found[0].get("chosen"):
        raise ValueError("record/key-decision-required")
    return found[0]


class Consolidator:
    def __init__(self, warehouse: Warehouse, registry: TypeRegistryClient, pack_path: str = "") -> None:
        self.warehouse, self.registry = warehouse, registry
        self.pack = Path(pack_path) if pack_path else None

    def _all(self, ctx: RunContext) -> dict[int, Annotation]:
        # Source/human evidence can be outside the family's consume selector.
        rows = {a.id: a for a in ctx.annotations}
        for uid in ctx.units:
            for a in self.warehouse.iter_annotations(
                ListAnnotationsRequest(
                    unit_id=uid,
                    generation=ctx.item.generation if ctx.item.consume.visibility == "active+generation" else None,
                )
            ):
                rows[a.id] = a
        return rows

    def _known(self, a: Annotation, policy: dict[str, Any], all_: dict[int, Annotation]) -> str | None:
        source = policy.get("identity", {}).get("known_from")
        if not source:
            return None
        pending, seen, values = [a], set(), set()
        while pending:
            current = pending.pop()
            if current.id in seen:
                continue
            seen.add(current.id)
            if source.startswith(current.type + ".") and (
                current.producer.startswith("human:")
                or current.producer.startswith("import:")
                and current.payload().get("recorded") is True
            ):
                value = path_value(current.payload(), source[len(current.type) + 1 :])
                if value is not _MISSING and value is not None and str(value):
                    values.add(str(value))
            pending.extend(all_[uid] for uid in current.derived_from if uid in all_)
        if len(values) > 1:
            raise ValueError("consolidate/ambiguous-known-identity")
        return next(iter(values)) if values else None

    def _policy(self, entity: str) -> dict[str, Any]:
        policy = yaml.safe_load(pack_file(self.pack, "consolidation.yaml").read_text())["entities"][entity]
        if policy.get("nature") not in ("occurrence", "state"):
            raise ValueError("consolidate/temporal-nature-required")
        return dict(policy)

    def normalize(self, ctx: RunContext) -> KindResult:
        all_ = self._all(ctx)
        result = KindResult()
        statements = sorted((a for a in ctx.annotations if a.type.startswith("Statement[")), key=lambda a: a.id)
        parents = list(range(len(statements)))
        known = [self._known(a, self._policy(a.type[10:-1]), all_) for a in statements]

        def find(i: int) -> int:
            while parents[i] != i:
                parents[i] = parents[parents[i]]
                i = parents[i]
            return i

        for i, a in enumerate(statements):
            for j in range(i):
                b = statements[j]
                if (
                    a.type == b.type
                    and ctx.units[a.unit_id].source_id == ctx.units[b.unit_id].source_id
                    and (related(ctx, a, b, "overlaps") or known[i] is not None and known[i] == known[j])
                ):
                    parents[find(i)] = find(j)
        components: dict[int, list[Annotation]] = defaultdict(list)
        for i, a in enumerate(statements):
            components[find(i)].append(a)
        groups = sorted(components.values(), key=lambda group: group[0].id)
        for group in groups:
            first = group[0]
            payload = first.payload()
            conflicts: list[dict[str, Any]] = []
            for a in group[1:]:
                for layer in ("attributes", "modifiers"):
                    for field, value in a.payload().get(layer, {}).items():
                        target = payload.setdefault(layer, {})
                        if field in target and target[field] != value:
                            conflicts.append(
                                {
                                    "attribute": f"{layer}.{field}",
                                    "values": [target[field], value],
                                    "annotationIds": [first.id, a.id],
                                }
                            )
                        else:
                            target[field] = value
            bound_modifier_ids = []
            modifier_origins = {
                kind: next(a.id for a in group if a.payload().get("modifiers", {}).get(kind) == value)
                for kind, value in payload.get("modifiers", {}).items()
            }
            for modifier in ctx.annotations:
                if modifier.type != "Modifier":
                    continue
                p = modifier.payload()
                scope = p.get("scope", {})
                if any(
                    s.unit_id == scope.get("unitId")
                    and scope.get("start", -1) <= s.start
                    and scope.get("end", -1) >= s.end
                    for a in group
                    for s in a.spans
                ):
                    kind = p["kind"]
                    schema = self.registry.schema_for(first.type).schema
                    assert isinstance(schema, dict)
                    if kind in schema.get("properties", {}).get("modifiers", {}).get("properties", {}):
                        value = p.get("value", p.get("trigger"))
                        field_schema = schema["properties"]["modifiers"]["properties"][kind]
                        if value not in field_schema.get("enum", [value]):
                            result.verdicts.append(verdict(first.unit_id, "normalize/modifier-unmapped", kind))
                            continue
                        target = payload.setdefault("modifiers", {})
                        if kind in target and target[kind] != value:
                            name = f"modifiers.{kind}"
                            conflict = next((c for c in conflicts if c["attribute"] == name), None)
                            if conflict is None:
                                conflict = {
                                    "attribute": name,
                                    "values": [target[kind]],
                                    "annotationIds": [modifier_origins[kind]],
                                }
                                conflicts.append(conflict)
                            if value not in conflict["values"]:
                                conflict["values"].append(value)
                                conflict["annotationIds"].append(modifier.id)
                        else:
                            target[kind] = value
                            modifier_origins.setdefault(kind, modifier.id)
                        bound_modifier_ids.append(modifier.id)
            # Disagreement is kept in a companion Decision: Statement schemas do not contain conflicts.
            spans_by_unit: dict[int, list[tuple[int, int]]] = defaultdict(list)
            for bound in group:
                for span in bound.spans:
                    spans_by_unit[span.unit_id].append((span.start, span.end))
            merged_spans: list[Span] = []
            for uid, ranges in sorted(spans_by_unit.items()):
                merged: list[tuple[int, int]] = []
                for start, end in sorted(ranges):
                    if merged and start <= merged[-1][1]:
                        merged[-1] = (merged[-1][0], max(end, merged[-1][1]))
                    else:
                        merged.append((start, end))
                merged_spans.extend(Span(unit_id=uid, start=start, end=end) for start, end in merged)
            payload["spans"] = [s.model_dump(by_alias=True) for s in merged_spans]
            payload["verbatim"] = "\n".join(ctx.units[s.unit_id].slice(s) for s in merged_spans)
            out = annotation(first.unit_id, first.type, payload, merged_spans)
            out.derived_from = sorted({a.id for a in group} | set(bound_modifier_ids))
            try:
                self.registry.schema_for(out.type).validate(payload)
                result.annotations.append(out)
            except ValidationError:
                result.verdicts.append(verdict(first.unit_id, "normalize/schema", "normalized statement is invalid"))
            if conflicts:
                decision = annotation(
                    first.unit_id,
                    "Decision",
                    {"decider": "normalize/conflicts", "score": 1, "factors": {"conflicts": conflicts}},
                )
                decision.derived_from = out.derived_from
                result.annotations.append(decision)
        return result

    def consolidate(self, ctx: RunContext) -> KindResult:
        subject, roots = subject_scope(ctx)
        all_ = self._all(ctx)
        statements = sorted((a for a in ctx.annotations if a.type.startswith("Statement[")), key=lambda a: a.id)
        groups: dict[tuple[str, str, str], list[Annotation]] = defaultdict(list)
        for a in statements:
            entity = a.type[10:-1]
            policy = self._policy(entity)
            known = self._known(a, policy, all_)
            block = canonical(
                [
                    a.payload().get("attributes", {}).get(k, {}).get("qname")
                    if isinstance(a.payload().get("attributes", {}).get(k), dict)
                    else a.payload().get("attributes", {}).get(k)
                    for k in policy.get("blocking", [])
                ]
            )
            # Occurrences with no recorded identity remain separate events, even for the same blocking key.
            group_key = known or (block if policy["nature"] == "state" else f"new:{a.id}")
            groups[(entity, block, group_key)].append(a)
        result = KindResult()
        used_previous: set[int] = set()
        redirects = []
        previous = [
            a
            for root in roots
            for a in self.warehouse.iter_annotations(
                ListAnnotationsRequest(unit_id=root.id, types=[f"Record.{e}" for e, _, _ in groups])
            )
        ]
        # Global greedy maximum-overlap assignment: the largest split retains the old key.
        assignments: dict[tuple[str, str, str], Annotation] = {}
        edges = []
        for group_id, evidence in groups.items():
            ids_for_group = {a.id for a in evidence}
            for prior in previous:
                if prior.type != f"Record.{group_id[0]}":
                    continue
                overlap = len(ids_for_group & {e["annotationId"] for e in prior.payload()["evidence"]})
                if overlap:
                    edges.append((-overlap, prior.id, group_id, prior))
        for _count, _id, group_id, prior in sorted(edges, key=lambda edge: edge[:3]):
            if group_id not in assignments and prior.id not in used_previous:
                assignments[group_id] = prior
                used_previous.add(prior.id)
        assigned_keys: dict[tuple[str, str, str], str] = {}
        for group_id, prior in assignments.items():
            if self._known(groups[group_id][0], self._policy(group_id[0]), all_) is not None:
                continue
            decisions = list(
                self.warehouse.iter_annotations(ListAnnotationsRequest(run_id=prior.run_id, types=["Decision"]))
            )
            assigned_keys[group_id] = str(record_decision(prior, decisions, subject)["chosen"])
        reserved = {(group_id[0], key) for group_id, key in assigned_keys.items()}
        used_keys: set[tuple[str, str]] = set()
        for (entity, _block, _key), evidence in sorted(groups.items()):
            policy = self._policy(entity)
            known = self._known(evidence[0], policy, all_)
            ids = sorted(a.id for a in evidence)
            overlaps = sorted(
                (
                    (len(set(ids) & {e["annotationId"] for e in a.payload()["evidence"]}), a)
                    for a in previous
                    if a.type == f"Record.{entity}"
                ),
                key=lambda pair: (-pair[0], pair[1].id),
            )
            reusable = assignments.get((entity, _block, _key))
            key = known
            if key is None and reusable is not None:
                key = assigned_keys[(entity, _block, _key)]
            if key is None:
                key = digest(entity, subject, ids[0])
                if (entity, key) in reserved:
                    key = digest(entity, subject, ids[0], "split", ids)
            if (entity, key) in used_keys:
                result.verdicts.append(verdict(evidence[0].unit_id, "consolidate/identity-block-conflict", entity))
                continue
            used_keys.add((entity, key))

            def time_of(a: Annotation) -> datetime:
                root = next((u for u in roots if u.source_id == ctx.units[a.unit_id].source_id), ctx.units[a.unit_id])
                value = str(a.payload().get("attributes", {}).get("recorded_at", meta(root, "recordedAt")))
                return utc_instant(value, "consolidate/evidence-time")

            evidence.sort(key=lambda a: (time_of(a), a.id))
            attributes: dict[str, Any] = {}
            modifiers: dict[str, Any] = {}
            origins: dict[str, Annotation] = {}
            roles = []
            history = []
            conflicts: dict[str, dict[str, Any]] = {}
            for n, a in enumerate(evidence):
                role = "creates" if n == 0 else "restates"
                for layer, target in (("attributes", attributes), ("modifiers", modifiers)):
                    for field, value in a.payload().get(layer, {}).items():
                        name = f"{layer}.{field}"
                        if field not in target:
                            target[field], origins[name] = value, a
                            if n:
                                role = max(role, "confirms", key=ROLE_RANK.index)
                        elif target[field] != value:
                            prior = origins[name]
                            if policy["nature"] == "state" and time_of(a) > time_of(prior):
                                history.append(
                                    {
                                        "attribute": name,
                                        "value": target[field],
                                        "annotationId": prior.id,
                                        "asOf": time_of(prior).isoformat(),
                                        "consolidationRun": ctx.item.run_id,
                                    }
                                )
                                target[field], origins[name] = value, a
                                if n:
                                    role = max(role, "updates", key=ROLE_RANK.index)
                            else:
                                conflict = conflicts.setdefault(
                                    name,
                                    {
                                        "attribute": name,
                                        "status": "open",
                                        "values": [{"value": target[field], "annotationIds": [prior.id]}],
                                    },
                                )
                                variant = next((v for v in conflict["values"] if v["value"] == value), None)
                                if variant:
                                    variant["annotationIds"].append(a.id)
                                else:
                                    conflict["values"].append({"value": value, "annotationIds": [a.id]})
                                if n:
                                    role = max(role, "contradicts", key=ROLE_RANK.index)
                        elif (
                            n
                            and role == "restates"
                            and ctx.units[a.unit_id].source_id != ctx.units[evidence[0].unit_id].source_id
                        ):
                            role = max(role, "confirms", key=ROLE_RANK.index)
                if any(
                    a.payload().get("modifiers", {}).get(k) in ("negative", "negated", "refuted")
                    for k in ("polarity", "certainty", "verification")
                ):
                    role = "refutes"
                roles.append({"annotationId": a.id, "role": role})
            newest = evidence[-1]
            source = ctx.units[newest.unit_id].source_id
            root = next(u for u in roots if u.source_id == source)
            payload: dict[str, Any] = {
                "attributes": attributes,
                "modifiers": modifiers,
                "evidence": roles,
                "consolidationRun": ctx.item.run_id,
            }
            if history:
                payload["history"] = history
            # Normalization stores disagreeing alternatives in a companion Decision because
            # the generated Statement schema has no conflicts property. Carry them forward.
            for normalized in evidence:
                ancestors = set(normalized.derived_from)
                for d in all_.values():
                    if (
                        d.type != "Decision"
                        or d.payload().get("decider") != "normalize/conflicts"
                        or ancestors != set(d.derived_from)
                    ):
                        continue
                    for conflict in d.payload()["factors"]["conflicts"]:
                        name = conflict["attribute"]
                        target = conflicts.setdefault(name, {"attribute": name, "status": "open", "values": []})
                        for value, source_id in zip(conflict["values"], conflict["annotationIds"], strict=True):
                            if not any(v["value"] == value for v in target["values"]):
                                target["values"].append({"value": value, "annotationIds": [source_id]})
            if conflicts:
                payload["conflicts"] = list(conflicts.values())
            concept = newest.payload().get("concept")
            if concept:
                payload["concept"] = concept
            out = annotation(root.id, f"Record.{entity}", payload)
            out.derived_from = ids
            try:
                self.registry.schema_for(out.type).validate(payload)
            except ValidationError:
                result.verdicts.append(
                    verdict(
                        root, "consolidate/schema", f"{out.type}: required fields missing or invalid; no record emitted"
                    )
                )
                continue
            result.annotations.append(out)
            decision = annotation(
                root.id,
                "Decision",
                {
                    "decider": "consolidate/key",
                    "chosen": key,
                    "score": 1,
                    "factors": {
                        "recordType": out.type,
                        "subjectId": subject,
                        "recordEvidence": ids,
                        "identity": "known" if known else "NEW",
                        "nature": policy["nature"],
                    },
                },
            )
            decision.derived_from = ids
            result.annotations.append(decision)
            for count, prior in overlaps:
                if count > 0:
                    redirects.append({"previousAnnotationId": prior.id, "recordKey": key, "overlap": count})
        result.sections = {
            "decisions": {
                "recordKeys": [a.payload() for a in result.annotations if a.type == "Decision"],
                "redirects": redirects,
            }
        }
        return result
