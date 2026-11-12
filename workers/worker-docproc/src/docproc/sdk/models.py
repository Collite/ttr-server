# SPDX-License-Identifier: Apache-2.0
"""The DX message models, held to the shared JSON fixtures (``tests/fixtures/contracts``).

The JSON rules every model follows — the same as the Kotlin side (``dx-protocol``) and
the proto3 JSON mapping: camelCase keys; a field at its default (None, "", 0, an empty
list, an enum's ``*_UNSPECIFIED``) is ABSENT, never written out. ``dump`` produces that
form; ``canonical`` the fixture text (sorted keys, 2-space indent, trailing newline).

Readers are tolerant (review-158 ⚑R158-B): ``model_validate`` ignores an unknown key and
reads an unknown enum value as the enum's ``*_UNSPECIFIED`` member, so a field or value a
newer producer adds never makes a message undecodable for this release (at-least-once
delivery would redeliver every message of a rolling deploy until the retry rule archived
the backlog). ``strict`` is the twin the fixture tests decode with: unknown keys and
unknown enum values are refused, so a typo in a fixture still fails CI. A consumer that
reads an ``*_UNSPECIFIED`` (``unspecified_fields``) fails that message once with a log —
the retry rule redelivers or dead-letters it — and never acts on a guess.
"""

from __future__ import annotations

import json
from enum import StrEnum
from typing import Any, Self, get_args

from pydantic import BaseModel, ConfigDict, Field, ValidationInfo, field_validator, model_validator
from pydantic.alias_generators import to_camel

_STRICT = "dx_strict"


class DxEnum(StrEnum):
    """A DX enum. Its FIRST member is the proto's ``*_UNSPECIFIED`` zero value."""

    @classmethod
    def unspecified(cls) -> Self:
        return next(iter(cls))


def _dx_enum(annotation: Any) -> type[DxEnum] | None:
    for t in (annotation, *get_args(annotation)):
        if isinstance(t, type) and issubclass(t, DxEnum):
            return t
    return None


def _strict(info: ValidationInfo) -> bool:
    return isinstance(info.context, dict) and info.context.get(_STRICT) is True


class DxModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")

    def dump(self) -> dict[str, Any]:
        return self.model_dump(by_alias=True, exclude_defaults=True, mode="json")

    @classmethod
    def strict(cls, data: Any) -> Self:
        """The strict twin of ``model_validate`` / ``model_validate_json`` (a ``str``/``bytes`` is JSON text)."""
        if isinstance(data, str | bytes):
            return cls.model_validate_json(data, context={_STRICT: True})
        return cls.model_validate(data, context={_STRICT: True})

    @model_validator(mode="before")
    @classmethod
    def _no_unknown_keys_when_strict(cls, data: Any, info: ValidationInfo) -> Any:
        if _strict(info) and isinstance(data, dict):
            known = set(cls.model_fields) | {f.alias for f in cls.model_fields.values() if f.alias}
            unknown = sorted(set(data) - known)
            if unknown:
                raise ValueError(f"unknown keys {unknown}")
        return data

    @field_validator("*", mode="before")
    @classmethod
    def _unknown_enum_value(cls, value: Any, info: ValidationInfo) -> Any:
        if info.field_name is None or _strict(info) or not isinstance(value, str):
            return value
        enum = _dx_enum(cls.model_fields[info.field_name].annotation)
        if enum is not None and value not in enum.__members__.values():
            return enum.unspecified()
        return value


def unspecified_fields(model: DxModel, prefix: str = "") -> list[str]:
    """The (dotted, wire-named) fields of ``model`` holding an ``*_UNSPECIFIED`` — values this release does not know."""
    out: list[str] = []
    for name, field in type(model).model_fields.items():
        value = getattr(model, name)
        wire = prefix + (field.alias or name)
        if isinstance(value, DxEnum) and value is type(value).unspecified():
            out.append(wire)
        elif isinstance(value, DxModel):
            out += unspecified_fields(value, wire + ".")
    return out


def canonical(obj: Any) -> str:
    """The canonical fixture text — the Kotlin ``CanonicalJson.print`` byte for byte."""
    return json.dumps(obj, sort_keys=True, indent=2, ensure_ascii=False) + "\n"


class RunVisibility(DxEnum):
    RUN_VISIBILITY_UNSPECIFIED = "RUN_VISIBILITY_UNSPECIFIED"
    CANDIDATE = "CANDIDATE"
    ACTIVE = "ACTIVE"
    SHADOW = "SHADOW"


class CompletionStatus(DxEnum):
    COMPLETION_STATUS_UNSPECIFIED = "COMPLETION_STATUS_UNSPECIFIED"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    ABSTAINED = "ABSTAINED"
    REUSED = "REUSED"


class InputSelector(DxModel):
    types: list[str]
    visibility: str  # "active" | "active+generation"
    # With "active+generation": the families the generation re-runs. Their live output is left out of a candidate's
    # inputs; the generation's own runs stand in for it (contracts §1.4, P2 S2.3).
    generation_producers: list[str] = []


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
    visibility: RunVisibility = RunVisibility.RUN_VISIBILITY_UNSPECIFIED
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
    status: CompletionStatus = CompletionStatus.COMPLETION_STATUS_UNSPECIFIED
    annotation_ids: list[int] = []
    child_unit_ids: list[int] = []
    artefact_ref: str = ""
    usage: Usage = Usage()
    error: str | None = None
    verdict_ids: list[str] = []


# ── The warehouse contracts (DX contracts §1.1–§1.4; the proto truth is kantheon's `org.tatrman.docwh.store.v1`) ───
# Proto3 JSON, held through the fixtures: int64 ids are numbers, an annotation's `payloadJson` is a JSON STRING (Rule 7),
# a span is [start, end) in Unicode code points of the unit's NFC text — which is how a Python `str` indexes.


class UnitKind(DxEnum):
    UNIT_KIND_UNSPECIFIED = "UNIT_KIND_UNSPECIFIED"
    DOCUMENT = "DOCUMENT"
    SECTION = "SECTION"
    CHUNK = "CHUNK"


class AnnotationTier(DxEnum):
    ANNOTATION_TIER_UNSPECIFIED = "ANNOTATION_TIER_UNSPECIFIED"
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
    return json_text(payload)


def json_text(value: Any) -> str:
    """Free-form evidence as a JSON string, the way `payloadJson` is written (Rule 7) — the artefact's `*Json` members."""
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


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
    feeds_records: bool = False
    version: str = ""


class Severity(DxEnum):
    SEVERITY_UNSPECIFIED = "SEVERITY_UNSPECIFIED"
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
    generation: str | None = None


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

    generation: str | None = None  # active + runs in this generation (DX-C-2 P3 seam)
    generation_producers: list[str] = []  # with generation: these producers' live output left out

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


class GetUnitResponse(DxModel):
    """`GET /units/{id}`: the unit and its children's ids. `unit` is required: a body without one (a bare `Unit`)
    would otherwise read as an all-defaults unit, id 0 and no text."""

    unit: Unit
    child_ids: list[int] = []
    messages: list[ResponseMessage] = []


class Source(DxModel):
    """proto `Source` as `GET /sources?documentId=` lists it. `notebook_id` (docwh-store 0.11.13, DX-P3 W5-2) is the
    mart the source's load bound it into — "" when the store knows no single one (or is older)."""

    id: int = 0
    asset_ref: str = ""
    mime_type: str = ""
    title: str = ""
    metadata: dict[str, MetadataValue] = {}
    created_at: str = ""
    embedding_status: str = ""  # proto `EmbeddingStatus`, vestigial: nothing reads it
    notebook_id: str = ""


class ListSourcesRequest(DxModel):
    """`GET /sources` query: a document's versions (the service identity names no mart). The request half of the
    listSources.json fixture, decoded in test_contracts; `StoreClient.sources_of_document` builds its query by hand."""

    document_id: str = ""
    version: str | None = None
    notebook_id: str = ""


class ListSourcesResponse(DxModel):
    """`GET /sources` — newest `metadata.recordedAt` first; no source is `{}` on the wire (an empty list is absent).
    Decoded in test_contracts; the client still returns the raw source dicts, which `diff` reads (review-244 cleanup)."""

    sources: list[Source] = []
    messages: list[ResponseMessage] = []


class SimilarRequest(DxModel):
    """`POST /findSimilar`'s body (proto `SimilarRequest`, docwh-store `FindSimilarRequestDto`)."""

    notebook_id: str = ""
    query: str = ""
    k: int = 0
    node_kinds: list[str] = []


class GetPageRequest(DxModel):
    """`GET /pages/{id}?notebookId=` (proto `GetPageRequest`). The request half of the page.json fixture, decoded in
    test_contracts; `StoreClient.page` builds its path and query by hand."""

    page_id: int = 0
    notebook_id: str = ""


class Citation(DxModel):
    source_id: int = 0
    part_id: int = 0
    page_id: int | None = None
    title: str = ""
    locator: str = ""
    source_ref: str = ""
    unit_id: int = 0


class ContextChunk(DxModel):
    """proto `ContextChunk` as docwh-store's v1 retrieval routes print it (ktor JSON: every field written, `null`s
    included). A page hit has `pageId` and no unit, chunk or span; `lead` is the `RetrievalLead` name. `score` is, on
    `/findSimilar`, the hit's cosine to the query (`1 − distance`, not normalized; docwh-store ≥ 0.11.13), and on
    `/getContext` the fused rank score, its planes max-normalized (contracts §1.4).

    `pageId` and `score` are what link reads, so they are required (`pageId` may be `null`: a chunk hit). A hit whose
    keys drifted is refused, never read as a chunk at score 0, i.e. "no candidates" (review-244 R2). The other fields
    stay lenient: nothing reads them."""

    part_id: int = 0
    source_id: int = 0
    page_id: int | None
    text: str = ""
    score: float
    lead: str = ""
    citation: Citation | None = None
    unit_id: int = 0
    chunk_id: int | None = None
    span: Span | None = None
    mention_ids: list[int] = []


class ContextResult(DxModel):
    """`POST /findSimilar`'s answer (docwh-store `ContextResponseDto`): nothing similar is `chunks: []`. The route
    always writes `chunks` (ktor JSON), so it is required here: an answer without it is a drifted shape, refused,
    never read as "no candidates" (review-244 F3). Unknown keys stay tolerated, so the route may add fields."""

    chunks: list[ContextChunk]
    grounded: bool = False


class ConceptRef(DxModel):
    entity_type: str = ""
    entity_id: str = ""
    display_label: str = ""
    metadata_qname: str = ""  # compile's: the mirrored cn concept the page is about


class Page(DxModel):
    """proto `Page` (`GET /pages/{id}`): a compiled wiki page; `concept_ref` is set on a concept page."""

    id: int = 0
    kind: str = ""  # proto `PageKind` name
    title: str = ""
    content_md: str = ""
    concept_ref: ConceptRef | None = None
    derived_from_parts: list[int] = []
    updated_at: str = ""


class ListAnnotationsPage(DxModel):
    """`ListAnnotationsResponse` — one page."""

    annotations: list[Annotation] = []
    next_page_token: str = ""
    messages: list[ResponseMessage] = []


class ProcessorRole(DxEnum):
    PROCESSOR_ROLE_UNSPECIFIED = "PROCESSOR_ROLE_UNSPECIFIED"
    ROLE_PRIMARY = "ROLE_PRIMARY"
    ROLE_MENTOR = "ROLE_MENTOR"
    ROLE_AUDITOR = "ROLE_AUDITOR"
    ROLE_RESIDUAL = "ROLE_RESIDUAL"
    ROLE_RETIRED = "ROLE_RETIRED"


class Processor(DxModel):
    family: str = ""
    version: str = ""
    version_hash: str = ""
    kind: str = ""
    host: str = ""
    role: ProcessorRole = ProcessorRole.PROCESSOR_ROLE_UNSPECIFIED
    manifest_yaml: str = ""
    consumes: list[str] = []
    produces: list[str] = []
    unit: str = ""
    when: str = ""
    mirror_of: str | None = None
