# SPDX-License-Identifier: Apache-2.0
"""Explicit complete Statement inputs from the PX row oracle, NOT deterministic rule output."""

from __future__ import annotations

import json
from dataclasses import replace
from typing import Any

from docproc.kinds.common import annotation
from docproc.runtime.registry import RunContext, TypeRegistryClient
from docproc.sdk.models import MetadataValue, UnitKind
from tests.fakes import PACK, FakeWarehouse, PackManager
from tests.pack import bundle_of, context, units_of


def registry(manager: PackManager | None = None) -> TypeRegistryClient:
    client = TypeRegistryClient(manager or PackManager(), str(PACK))
    client.load("1")
    return client


def complete_subject(doc: str = "review-001.en.json") -> tuple[RunContext, FakeWarehouse]:
    units = units_of(doc)
    reg = json.loads((PACK / "registrations" / (doc.rsplit(".", 1)[0] + ".json")).read_text())
    subject = reg["document"]["subjectId"]
    units[0].metadata["subjectId"] = MetadataValue(single=subject)
    wh = FakeWarehouse(units)
    facts = bundle_of(doc, units)
    for n, fact in enumerate(facts):
        fact.id = 200 + n
        wh.annotations.append(fact)
    rows = json.loads((PACK / "golden/mini-gold-v1/records/review.rows.json").read_text())["rows"]
    statements = []
    for n, row in enumerate(r for r in rows if r["subject"] == subject):
        fact = next(f for f in facts if f.payload()["reviewId"] == row["reviewId"])
        attrs: dict[str, Any] = {
            k: v
            for k, v in row.items()
            if k not in ("subject", "reviewId", "annotation_refs", "verification", "temporal_context")
        }
        attrs["item"] = {"qname": subject}
        attrs["customer"] = {"qname": {101: "C-20417", 102: "C-20988", 103: "C-31102", 104: "C-31560"}[row["customer"]]}
        a = annotation(
            fact.unit_id,
            "Statement[review]",
            {
                "attributes": attrs,
                "modifiers": {k: row[k] for k in ("verification", "temporal_context")},
                "verbatim": row["body"],
                "spans": [s.model_dump(by_alias=True) for s in fact.spans],
            },
            fact.spans,
        )
        a.id = 300 + n
        a.derived_from = [fact.id]
        statements.append(a)
        wh.annotations.append(a)
    ctx = context(doc, "consolidate-review", units=units)
    return replace(
        ctx,
        units={u.id: u for u in units},
        annotations=statements,
        item=ctx.item.model_copy(update={"unit_ids": [u.id for u in units if u.kind == UnitKind.DOCUMENT]}),
    ), wh
