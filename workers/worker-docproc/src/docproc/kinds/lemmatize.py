# SPDX-License-Identifier: Apache-2.0
"""Batched lemmas to the full-text plane, with S-1 identity on every write."""

from __future__ import annotations

import hashlib
from collections import defaultdict
from typing import Any

from docproc.kinds.common import meta, processing_units
from docproc.runtime.registry import KindResult, RunContext
from docproc.sdk.models import RunVisibility, Unit
from docproc.sdk.nlp import Nlp, identities
from docproc.sdk.store import Warehouse


class Lemmatize:
    def __init__(self, nlp: Nlp, warehouse: Warehouse) -> None:
        self.nlp, self.warehouse = nlp, warehouse

    def run(self, ctx: RunContext) -> KindResult:
        batch = int(ctx.manifest["engine"].get("batch", 64))
        if batch < 1:
            raise ValueError("lemmatize/invalid-batch")
        groups: dict[str, list[Unit]] = defaultdict(list)
        for unit in processing_units(ctx):
            groups[meta(unit, "lang")].append(unit)
        result = KindResult()
        lemmas: list[dict[str, Any]] = []
        used = []
        for lang, units in groups.items():
            for start in range(0, len(units), batch):
                chunk = units[start : start + batch]
                response = self.nlp.lemmatize([u.content_text for u in chunk], lang)
                models = identities(response.used)
                identity = next((ev for ev in models if ev["op"] == "LEMMATIZE"), None)
                if identity is None:
                    raise ValueError("nlp/no-model-identity")
                if len(response.results) != len(chunk):
                    raise ValueError("nlp/lemma-cardinality")
                used.extend(models)
                result.usage.nlp_calls += 1
                lemmatizer = f"{identity['engine']}:{identity['model']}@{identity['modelVersion']}"
                lemmas.extend(
                    {"unitId": u.id, "contentLemmas": " ".join(r.lemmas), "lang": lang, "lemmatizer": lemmatizer}
                    for u, r in zip(chunk, response.results, strict=True)
                )
        if lemmas and ctx.item.visibility == RunVisibility.ACTIVE:
            self.warehouse.load_lemmas(ctx.item.run_id, lemmas)
        elif lemmas:
            result.sections["outputs"] = {
                "lemmas": [
                    {
                        "unitId": row["unitId"],
                        "lang": row["lang"],
                        "lemmatizer": row["lemmatizer"],
                        "lemmasSha256": "sha256:" + hashlib.sha256(row["contentLemmas"].encode()).hexdigest(),
                    }
                    for row in lemmas
                ]
            }
        result.sections["nlp-calls"] = {"calls": result.usage.nlp_calls, "used": used}
        return result
