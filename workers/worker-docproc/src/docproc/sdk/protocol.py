# SPDX-License-Identifier: Apache-2.0
"""Processor protocol spine; payloads are pointers and hashes at summary verbosity.

P2 will supply the processor section proto members/strict fixture. This builder uses
its own registered spine, preserving the existing ProtocolDocument envelope.
"""

from __future__ import annotations

from typing import Any

from docproc.runtime.registry import RunContext
from docproc.sdk.models import Completion


class ProtocolWriter:
    def document(
        self, ctx: RunContext, completion: Completion, sections: dict[str, Any] | None = None
    ) -> dict[str, Any]:
        item = ctx.item
        payloads: list[tuple[str, str, dict[str, Any]]] = [
            (
                "header",
                "processorHeader",
                {
                    "family": item.family,
                    "versionHash": item.version_hash,
                    "kind": item.kind,
                    "host": "docproc",
                    "documentId": item.document_id,
                    "unitIds": item.unit_ids,
                    "generation": item.generation,
                    "visibility": item.visibility.value,
                },
            ),
            (
                "input-bundle",
                "inputBundle",
                {
                    "unitIds": list(ctx.units),
                    "annotationIds": [a.id for a in ctx.annotations],
                    "contentHashes": {str(u.id): u.content_hash for u in ctx.units.values()},
                    **({"bundleRef": item.bundle_ref} if item.bundle_ref else {}),
                },
            ),
            ("hints", "hints", {"annotationIds": [a.id for a in ctx.annotations]}),
            ("nlp-calls", "nlpCalls", {"calls": completion.usage.nlp_calls}),
            ("candidates", "candidates", {}),
            ("decisions", "decisions", {}),
            (
                "outputs",
                "outputs",
                {"annotationIds": completion.annotation_ids, "childUnitIds": completion.child_unit_ids},
            ),
            ("validators", "validators", {"verdictIds": completion.verdict_ids}),
            (
                "errors",
                "errors",
                {
                    "items": [
                        {
                            "source": "docproc",
                            "code": "timeout" if completion.error == "timeout" else "processor/failed",
                            "message": completion.error
                            if completion.error in ("timeout", "retries exhausted")
                            else "processor failed",
                        }
                    ]
                }
                if completion.error
                else {},
            ),
        ]
        if sections:
            payloads = [(key, member, {**payload, **sections.get(key, {})}) for key, member, payload in payloads]
        return {
            "protocolId": item.run_id,
            "sessionId": item.plan_id,
            "scope": {"turnId": item.run_id},
            "schemaVersion": "protocol/v1.0",
            "header": {"title": f"Processor {item.family}", "turnCountInScope": 1},
            "turns": [
                {
                    "turnId": item.run_id,
                    "seq": 1,
                    "sections": [
                        {
                            "key": key,
                            "appliedVerbosity": "VERBOSITY_FULL"
                            if key == "outputs" and "candidateRows" in payload
                            else "VERBOSITY_SUMMARY",
                            member: payload,
                        }
                        for key, member, payload in payloads
                    ],
                }
            ],
            "receipts": {},
        }
