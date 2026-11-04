# SPDX-License-Identifier: Apache-2.0
"""The processor section spine's proto members (kantheon `protocol/v1/protocol.proto`, contracts §8 "as built at P2
S2.3"), as JSON names. kantheon reads an artefact with the strict proto parser, so a key docproc writes that is not
here makes the whole artefact unreadable there (review-197 deviation 11)."""

from __future__ import annotations

from typing import Any

SECTIONS: dict[str, tuple[str, set[str]]] = {
    "header": (
        "processorHeader",
        {
            "family",
            "versionHash",
            "kind",
            "host",
            "documentId",
            "unitIds",
            "generation",
            "visibility",
            "unitScope",
            "runId",
            "planId",
            "stepId",
        },
    ),
    "input-bundle": ("inputBundle", {"unitIds", "annotationIds", "contentHashes", "bundleRef"}),
    "hints": ("hints", {"annotationIds", "types"}),
    "nlp-calls": ("nlpCalls", {"calls", "used", "unmapped"}),
    "candidates": ("candidates", {"items", "unmapped"}),
    "decisions": ("decisions", {"annotationIds", "recordKeysJson", "redirects", "previousSourceId", "removedUnitIds"}),
    "outputs": (
        "outputs",
        {
            "annotationIds",
            "childUnitIds",
            "byType",
            "recordRows",
            "recordDiffJson",
            "candidateRowsJson",
            "lemmas",
            "pageIds",
            "vectors",
            "concepts",
            "pagesMerged",
        },
    ),
    "validators": ("validators", {"verdictIds", "postConditions"}),
    "errors": ("errors", {"items", "recovery", "diagnosticsJson"}),
}
# Repeated message members: (section key, field) → the element's members.
ELEMENTS: dict[tuple[str, str], set[str]] = {
    ("nlp-calls", "used"): {"op", "engine", "model", "modelVersion"},
    ("candidates", "items"): {"annotationId", "items"},
    ("decisions", "redirects"): {"previousAnnotationId", "recordKey", "overlap"},
    ("outputs", "recordRows"): {"entity", "rowId", "consolidationRun"},
    ("outputs", "lemmas"): {"unitId", "lang", "lemmatizer", "lemmasSha256"},
    ("errors", "items"): {"source", "code", "message"},
}
LINK_CANDIDATE = {"system", "code", "label", "score", "source"}


def unknown_members(document: dict[str, Any]) -> list[str]:
    """Every key of a run artefact the proto has no member for, as dotted paths; [] when kantheon can read it."""
    found = [
        k
        for k in document
        if k not in {"protocolId", "sessionId", "scope", "schemaVersion", "header", "turns", "receipts"}
    ]
    for turn in document.get("turns", []):
        for section in turn["sections"]:
            key = section["key"]
            if key not in SECTIONS:
                found.append(f"section {key}")
                continue
            member, fields = SECTIONS[key]
            found += [f"{key}.{k}" for k in section if k not in {"key", "appliedVerbosity", member}]
            body = section.get(member, {})
            found += [f"{key}.{k}" for k in body if k not in fields]
            for (owner, field), allowed in ELEMENTS.items():
                if owner != key:
                    continue
                for element in body.get(field, []):
                    found += [f"{key}.{field}[].{k}" for k in element if k not in allowed]
                    if key == "candidates":
                        for candidate in element.get("items", []):
                            found += [f"candidates.items[].items[].{k}" for k in candidate if k not in LINK_CANDIDATE]
    return found
