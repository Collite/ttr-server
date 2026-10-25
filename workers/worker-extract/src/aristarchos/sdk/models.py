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

from pydantic import BaseModel, ConfigDict, model_validator
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
