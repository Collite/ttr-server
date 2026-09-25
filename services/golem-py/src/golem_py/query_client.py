# SPDX-License-Identifier: Apache-2.0
"""The query door + the answer envelope (P4.3·T5).

The envelope is contracts §6: `{content, formatting_directives?, provenance,
gaps_carried[]}`. `provenance` is the RV-39 layer tuple plus the S-1 engine identity
echoed off the lattice — so an answer can always say which vocabulary bound its words
and which models read them.

⚑ **RULED at T5, and the ruling is a recon finding rather than a design choice: the
LIVE gRPC client is not built at P4.** Two reasons, both external to this service:

1. **The query door's proto is not self-contained in this repo.**
   `org/tatrman/query/v1/query.proto` imports `org/tatrman/plan/v1/{plan,context,
   parameters}.proto` and `org/tatrman/translate/v1/translator.proto`, none of which
   live in `shared/proto` — they belong to `org.tatrman:ttr-plan-proto`, whose Python
   lane is a published wheel pinned at `0.8.4` by `shared/proto/build.gradle.kts`.
   Generating golem-py's stubs for the door therefore needs either that wheel in the
   uv env or those `.proto` files vendored into the gen lane. That is a supply-chain
   decision, not code, and it is Bora's.

2. **There is no door that takes a structured question.** `RunRequest.source` is a
   query STRING in a source language — `SQL | TRANSFORMATION_DSL | DATAFRAME_DSL |
   REL_NODE` — over `SchemaCode` `DB` or `ER`. The hero lattices bind `md.*` refs, and
   `SchemaCode` has **no MD** (recorded at `p3-4`: that is why the md ref index had to
   be a second, string-keyed one). So rendering a composed md-level question into
   something the door accepts is unowned work — it is precisely the "entity-level
   query" surface the server's own thesis names, and it does not exist on the wire yet.
   ⚑ Bora: does the query door grow an md/entity-level surface, or does a Golem render
   md → SQL with the model in hand? Either is a design question with its own list, and
   inventing one here would put a second query dialect in the estate.

What P4.3 therefore ships is the PORT, the envelope, and the recorded-door tests — the
same posture the ladder's rungs take: the seam is real, tested and empty, rather than
filled with a fake that would have to be unbuilt.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol, runtime_checkable

from golem_py.compose import Filter, StructuredQuestion
from golem_py.state import GapRecord, ResolutionState


@dataclass
class QueryResult:
    """What a door returns. Rows stay opaque here — rendering is the caller's."""

    rows: list[dict[str, Any]] = field(default_factory=list)
    columns: list[str] = field(default_factory=list)
    provenance: dict[str, str] = field(default_factory=dict)
    truncated: bool = False


@runtime_checkable
class QueryPort(Protocol):
    async def run(
        self, *, question: StructuredQuestion, caller_subject: str = ""
    ) -> QueryResult: ...


def lattice_provenance(state: ResolutionState) -> dict[str, str]:
    """The RV-39 layer tuple + the S-1 echo. Same tuple + same question ⇒ same lattice,
    which is only checkable if the answer says what the tuple WAS."""
    versions = state.lexicon_versions
    provenance = {
        "lexicon_artifact_hash": versions.lexicon_artifact_hash,
        "trace_id": state.trace_id,
    }
    for category, version in versions.member_index_versions.items():
        provenance[f"member_index:{category}"] = version
    if versions.overlay_version is not None:
        provenance["overlay_version"] = versions.overlay_version
    used = state.parse.get("used") if isinstance(state.parse, dict) else None
    if isinstance(used, list) and used:
        provenance["engines"] = ", ".join(str(u) for u in used)
    return provenance


def _render_filter(f: Filter) -> str:
    """A member filter names its member; a VERBATIM one shows the text as typed, so the
    envelope does not read as an unrestricted attribute."""
    if f.verbatim:
        return f'{f.ref} {f.predicate or "(§2.2 default)"} "{f.literal}"'
    return f.member_ref or f.ref


def render_content(question: StructuredQuestion, result: QueryResult | None) -> str:
    """A structural rendering, deliberately plain.

    Prose generation is an LLM's job and this leg is 0-LLM by contract, so the content
    states what was asked and what came back. A Golem shell that wants sentences has an
    LLM; the OS Golem's honesty is in the structure.
    """
    parts = []
    if question.measures:
        parts.append("measures: " + ", ".join(question.measures))
    if question.subjects:
        parts.append("subject: " + ", ".join(question.subjects))
    if question.groupings:
        parts.append("by: " + ", ".join(question.groupings))
    if question.filters:
        parts.append("where: " + ", ".join(_render_filter(f) for f in question.filters))
    if question.time_grain:
        parts.append(f"period: {question.time_grain.normalized_value}")
    if question.operators:
        parts.append("ops: " + ", ".join(question.operators))
    if result is not None:
        parts.append(f"rows: {len(result.rows)}")
    return " · ".join(parts)


@dataclass
class AnswerEnvelope:
    """contracts §6. `gaps_carried` is the honest half: what the answer did NOT do."""

    content: str
    formatting_directives: dict[str, str] = field(default_factory=dict)
    provenance: dict[str, str] = field(default_factory=dict)
    gaps_carried: list[GapRecord] = field(default_factory=list)
    question: StructuredQuestion | None = None
    result: QueryResult | None = None


def build_envelope(
    *,
    state: ResolutionState,
    question: StructuredQuestion,
    result: QueryResult | None,
    gaps_carried: list[GapRecord],
) -> AnswerEnvelope:
    return AnswerEnvelope(
        content=render_content(question, result),
        formatting_directives=question.formatting_directives,
        provenance=lattice_provenance(state) | (result.provenance if result else {}),
        gaps_carried=gaps_carried,
        question=question,
        result=result,
    )
