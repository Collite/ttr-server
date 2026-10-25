# SPDX-License-Identifier: Apache-2.0
"""The DX message models, held to the shared JSON fixtures (``tests/fixtures/contracts``).

The JSON rules every model follows — the same as the Kotlin side (``dx-protocol``) and
the proto3 JSON mapping: camelCase keys; a field at its default (None, "", 0, an empty
list) is ABSENT, never written out; unknown keys are refused. ``dump`` produces that
form; ``canonical`` the fixture text (sorted keys, 2-space indent, trailing newline).
"""

from __future__ import annotations

import json
from enum import StrEnum
from typing import Any, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


class DxModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")

    def dump(self) -> dict[str, Any]:
        return self.model_dump(by_alias=True, exclude_defaults=True, mode="json")


def canonical(obj: Any) -> str:
    """The canonical fixture text — the Kotlin ``CanonicalJson.print`` byte for byte."""
    return json.dumps(obj, sort_keys=True, indent=2, ensure_ascii=False) + "\n"


class RunVisibility(StrEnum):
    CANDIDATE = "CANDIDATE"
    ACTIVE = "ACTIVE"
    SHADOW = "SHADOW"


class CompletionStatus(StrEnum):
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    ABSTAINED = "ABSTAINED"
    REUSED = "REUSED"


class InputSelector(DxModel):
    types: list[str]
    visibility: str  # "active" | "active+generation"


class WorkItem(DxModel):
    """A step on ``work_<family>[_hi]``: pointers only. ``run_id = "<step_id>/<attempt>"`` is the idempotency key."""

    step_id: str
    attempt: int
    run_id: str
    family: str
    version_hash: str
    kind: str
    document_id: str
    version: str
    plan_id: str
    generation: str
    unit_ids: list[int]
    consume: InputSelector
    bundle_ref: str | None = None
    visibility: RunVisibility
    priority: str  # "hi" | "lo"
    deadline_at: str

    @model_validator(mode="after")
    def _keys(self) -> Self:
        if self.run_id != f"{self.step_id}/{self.attempt}":
            raise ValueError(f"run_id must be <step_id>/<attempt>, was {self.run_id!r}")
        if self.priority not in ("hi", "lo"):
            raise ValueError("priority must be hi | lo")
        return self


class Usage(DxModel):
    latency_ms: int = 0
    tokens_in: int = 0
    tokens_out: int = 0
    cost_usd: float = 0.0
    model_echo: str = ""
    llm_calls: int = 0
    nlp_calls: int = 0


class Completion(DxModel):
    """The ``done`` queue message."""

    run_id: str
    step_id: str
    status: CompletionStatus
    annotation_ids: list[int] = []
    child_unit_ids: list[int] = []
    artefact_ref: str = ""
    usage: Usage = Usage()
    error: str | None = None
    verdict_ids: list[str] = []


# ── The warehouse contracts (DX contracts §1.1–§1.4; the proto truth is kantheon's `org.tatrman.kallimachos.v1`) ───
# Proto3 JSON, held through the fixtures: int64 ids are numbers, an annotation's `payloadJson` is a JSON STRING (Rule 7),
# a span is [start, end) in Unicode code points of the unit's NFC text — which is how a Python `str` indexes.


class UnitKind(StrEnum):
    DOCUMENT = "DOCUMENT"
    SECTION = "SECTION"
    CHUNK = "CHUNK"


class AnnotationTier(StrEnum):
    STRUCTURAL = "STRUCTURAL"
    MODEL = "MODEL"


class StringList(DxModel):
    values: list[str] = []


class MetadataValue(DxModel):
    """`oneof kind { string single; StringList list }`."""

    single: str | None = None
    list: StringList | None = None


class Unit(DxModel):
    id: int = 0
    source_id: int = 0
    idx: int = 0
    kind: UnitKind | None = None
    content_text: str = ""
    metadata: dict[str, MetadataValue] = {}
    parent_id: int | None = None
    content_lemmas: str = ""
    content_hash: str = ""

    def slice(self, span: Span) -> str:
        """The text a span covers — code points, so plain `str` slicing."""
        return self.content_text[span.start : span.end]


class Span(DxModel):
    unit_id: int = 0
    start: int = 0
    end: int = 0


def payload_text(payload: dict[str, Any]) -> str:
    """An annotation payload as the `payloadJson` string: compact, keys sorted, non-ASCII verbatim."""
    return json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


class Annotation(DxModel):
    id: int = 0
    unit_id: int = 0
    spans: list[Span] = []
    type: str = ""
    payload_json: str = ""
    producer: str = ""
    producer_version: str = ""
    run_id: str = ""
    derived_from: list[int] = []
    confidence: float | None = None
    created_at: str = ""
    superseded_by: int | None = None
    reused_from: int | None = None

    def payload(self) -> dict[str, Any]:
        value: dict[str, Any] = json.loads(self.payload_json)
        return value


class AnnotationType(DxModel):
    key: str = ""
    tier: AnnotationTier | None = None
    # wire name `schemaJson`; the attribute avoids pydantic's own `BaseModel.schema_json`
    schema_text: str = Field("", alias="schemaJson")
    unit_level: str = ""
    feeds_gold: bool = False
    version: str = ""


class Severity(StrEnum):
    INFO = "INFO"
    WARNING = "WARNING"
    ERROR = "ERROR"


class ResponseMessage(DxModel):
    """Rule 6 — `repeated ResponseMessage messages = 99` on every response."""

    severity: Severity | None = None
    code: str = ""
    human_message: str = ""
    source_file: str = ""


class LoadAnnotationsRequest(DxModel):
    run_id: str = ""
    annotations: list[Annotation] = []
    visibility: RunVisibility | None = None


class LoadAnnotationsResponse(DxModel):
    ids: list[int] = []
    mentions_linked: int = 0
    messages: list[ResponseMessage] = []


class ListAnnotationsRequest(DxModel):
    notebook_id: str = ""
    source_id: int | None = None
    unit_id: int | None = None
    types: list[str] = []
    producers: list[str] = []
    run_id: str | None = None
    active_only: bool | None = None  # absent = true (the server's default)
    page_size: int = 0
    page_token: str = ""

    def query(self) -> list[tuple[str, str | int | float | bool | None]]:
        """The `GET /annotations` query string: camelCase keys, repeated keys for lists, defaults left out."""
        out: list[tuple[str, str | int | float | bool | None]] = []
        for key, value in self.dump().items():
            if isinstance(value, list):
                out += [(key, str(v)) for v in value]
            elif isinstance(value, bool):
                out.append((key, "true" if value else "false"))
            else:
                out.append((key, str(value)))
        return out


class ListAnnotationsPage(DxModel):
    """`ListAnnotationsResponse` — one page."""

    annotations: list[Annotation] = []
    next_page_token: str = ""
    messages: list[ResponseMessage] = []
