# SPDX-License-Identifier: Apache-2.0
"""`resolve.bind:v1` — the door client, and the HONEST mapper (P4.1·T5).

The P0-3 spike's mapper reconstructed one mention per BINDING, because the door of the
day could only report what it had bound: a span it failed to bind was invisible, and
`G1_UNBOUND` was not expressible at all. That was the spike's blocking caveat and the
reason P4 could not open before `p2-1`.

`p2-1` shipped `ResolveResponse.resolution_state` (field 7), so this mapper reads the
LATTICE directly — mentions with 0..n bindings, repeated frame roles, typed gap
records, the rung log, the RV-39 layer tuple. The reconstruction is deleted, not
patched: there is nothing left to infer.

What the mapper still refuses to do: invent. A field the response leaves empty stays
empty here. `resume_token` comes from `AwaitingClarification`, which is the ONLY place
the core signs one — the lattice carries no token and must not appear to.
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

_GENERATED = Path(__file__).resolve().parents[2] / "generated"
if str(_GENERATED) not in sys.path:  # pragma: no cover - import bootstrap
    sys.path.insert(0, str(_GENERATED))

import grpc  # noqa: E402
from org.tatrman.resolver.v1 import resolver_pb2, resolver_pb2_grpc  # noqa: E402

from golem_py.state import (  # noqa: E402
    Attribution,
    Binding,
    Disposition,
    EvidenceClass,
    FrameRole,
    GapKind,
    GapRecord,
    Grounding,
    Hypothesis,
    LexiconVersions,
    MatchMethod,
    Mention,
    Provenance,
    ResolutionState,
    RungLogEntry,
    SignedOption,
    SourceTag,
    Span,
    TargetClass,
    ValueFinding,
    ValueKind,
)

DEFAULT_TARGET = "localhost:7276"

# proto3 enum values are siblings in the ENCLOSING scope, so every value carries its
# enum's name as a prefix. Stripping it belongs in exactly ONE place — here — so that
# nothing downstream ever writes `EVIDENCE_CLASS_` in a comparison.
_PREFIXES = {
    "TargetClass": "TARGET_CLASS_",
    "EvidenceClass": "EVIDENCE_CLASS_",
    "MatchMethod": "MATCH_METHOD_",
    "SourceTag": "SOURCE_TAG_",
    "FrameRole": "FRAME_ROLE_",
    "GapKind": "GAP_KIND_",
    "Disposition": "DISPOSITION_",
    "ValueKind": "VALUE_KIND_",
}


def _enum(py_enum: Any, proto_enum_name: str, value: int) -> Any:
    """proto enum int → our string enum, prefix stripped, unknown ⇒ UNSPECIFIED.

    Unknown values are tolerated rather than raised on: an older client reading a newer
    lattice must degrade, not crash (the same posture the archive loader takes).
    """
    name = resolver_pb2.DESCRIPTOR.enum_types_by_name[proto_enum_name].values_by_number.get(value)
    if name is None:
        return py_enum.UNSPECIFIED
    stripped = name.name.removeprefix(_PREFIXES[proto_enum_name])
    try:
        return py_enum(stripped)
    except ValueError:
        return py_enum.UNSPECIFIED


def _span(msg: Any) -> Span:
    return Span(start=msg.start, end=msg.end, text=msg.text)


def _provenance(msg: Any) -> Provenance:
    return Provenance(
        vocabulary_source=msg.vocabulary_source,
        algorithm=msg.algorithm,
        score=msg.score,
        tier=msg.tier,
        snapshot_hash=msg.snapshot_hash,
        model_versions=[
            f"{m.op}:{m.engine}:{m.model}:{m.model_version}" for m in msg.model_versions
        ],
        proposing_rung=msg.proposing_rung,
    )


def binding_from_proto(msg: Any) -> Binding:
    return Binding(
        ref=msg.ref,
        target_class=_enum(TargetClass, "TargetClass", msg.target_class),
        evidence_class=_enum(EvidenceClass, "EvidenceClass", msg.evidence_class),
        method=_enum(MatchMethod, "MatchMethod", msg.method),
        # `optional` on the wire: absent ≠ 0/false. `auto_bindable = false` means "do
        # not auto-bind"; absent means "no uniqueness decision applies here".
        max_distance=msg.max_distance if msg.HasField("max_distance") else None,
        uniqueness_margin=msg.uniqueness_margin if msg.HasField("uniqueness_margin") else None,
        auto_bindable=msg.auto_bindable if msg.HasField("auto_bindable") else None,
        source=_enum(SourceTag, "SourceTag", msg.source),
        in_class_score=msg.in_class_score,
        producer=_provenance(msg.producer),
    )


def _hypothesis(msg: Any) -> Hypothesis:
    return Hypothesis(
        span=_span(msg.span) if msg.HasField("span") else None,
        ref=msg.ref,
        correction=msg.correction,
        proposing_rung=msg.proposing_rung,
    )


def gap_from_proto(msg: Any) -> GapRecord:
    return GapRecord(
        span=_span(msg.span),
        kind=_enum(GapKind, "GapKind", msg.kind),
        frame_roles=[_enum(FrameRole, "FrameRole", r) for r in msg.frame_roles],
        hypotheses_tried=[_hypothesis(h) for h in msg.hypotheses_tried],
        asked_round=msg.asked_round,
        user_answer=msg.user_answer,
        disposition=_enum(Disposition, "Disposition", msg.disposition),
        mention_id=msg.mention_id,
        value_id=msg.value_id,
    )


def rung_log_entry_from_proto(msg: Any) -> RungLogEntry:
    return RungLogEntry(
        round=msg.round,
        rung=msg.rung,
        action=msg.action,
        mention_ids=list(msg.mention_ids),
        value_ids=list(msg.value_ids),
        hypotheses=[_hypothesis(h) for h in msg.hypotheses],
        bindings_added=msg.bindings_added,
        gaps_open=msg.gaps_open,
        elapsed_ms=msg.elapsed_ms,
    )


def _parse(msg: Any) -> dict[str, object]:
    """The parse passthrough (E-T1: agents skip a second parse), kept as a plain dict.

    It is read by the compose step (frame roles are already derived core-side) and by a
    delegated plugin — neither needs a typed mirror of the whole NLP contract, and
    minting one would be a second place for the nlp proto to drift from.
    """
    return {
        "language": msg.language,
        "tokens": [
            {
                "text": t.text,
                "lemma": t.lemma,
                "upos": t.upos,
                "charStart": t.char_start,
                "charEnd": t.char_end,
                "depRelation": t.dep_relation,
                "depHead": t.dep_head,
            }
            for t in msg.tokens
        ],
        "entities": [{"text": e.text, "label": e.label} for e in msg.entities],
        # S-1 engine identity, once per lattice (never per binding).
        "used": [f"{u.op}:{u.engine}:{u.model}:{u.model_version}" for u in msg.used],
    }


def lattice_from_proto(msg: Any) -> ResolutionState:
    """`ResolutionState` (proto) → `ResolutionState` (ours). Field for field."""
    return ResolutionState(
        parse=_parse(msg.parse),
        mentions=[
            Mention(
                id=m.id,
                span=_span(m.span),
                lemma=m.lemma,
                frame_roles=[_enum(FrameRole, "FrameRole", r) for r in m.frame_roles],
                bindings=[binding_from_proto(b) for b in m.bindings],
            )
            for m in msg.mentions
        ],
        values=[
            ValueFinding(
                id=v.id,
                span=_span(v.span),
                kind=_enum(ValueKind, "ValueKind", v.kind),
                attributions=[
                    Attribution(
                        attribute_ref=a.attribute_ref,
                        binding=binding_from_proto(a.binding) if a.HasField("binding") else None,
                    )
                    for a in v.attributions
                ],
                grounding=(
                    Grounding(
                        kernel=v.grounding.kernel,
                        kind=v.grounding.kind,
                        normalized_value=v.grounding.normalized_value,
                        ref=v.grounding.ref,
                        confidence=v.grounding.confidence,
                    )
                    if v.HasField("grounding")
                    else None
                ),
                anchor_mention_id=v.anchor_mention_id,
                verbatim_text=v.verbatim_text if v.HasField("verbatim_text") else None,
                predicate_ref=v.predicate_ref if v.HasField("predicate_ref") else None,
            )
            for v in msg.values
        ],
        gaps=[gap_from_proto(gp) for gp in msg.gaps],
        rung_log=[rung_log_entry_from_proto(e) for e in msg.rung_log],
        lexicon_versions=LexiconVersions(
            lexicon_artifact_hash=msg.lexicon_versions.lexicon_artifact_hash,
            member_index_versions=dict(msg.lexicon_versions.member_index_versions),
            overlay_version=(
                msg.lexicon_versions.overlay_version
                if msg.lexicon_versions.HasField("overlay_version")
                else None
            ),
        ),
    )


def response_to_state(resp: Any, *, question: str = "") -> ResolutionState:
    """The whole response → our state. The lattice is field 7; the token is NOT in it."""
    state = lattice_from_proto(resp.resolution_state)
    state.question = question
    state.trace_id = resp.trace_id
    if resp.WhichOneof("outcome") == "awaiting":
        # The ONLY place a resume token exists. The core signs it; we store and return
        # it, never mint it (RS-26 / P0-3 T5). The option set is signed INTO that token,
        # so it is stored beside it — a pin is honoured only against this list.
        state.resume_token = resp.awaiting.resume_token
        state.signed_options = [
            SignedOption(
                id=o.id,
                label=o.label,
                ref=o.target_ref or o.resolved_id,
                entity_type_ref=o.entity_type_ref,
                span=_span(o.span) if o.HasField("span") else None,
            )
            for o in resp.awaiting.options
        ]
    return state


def options_from_response(resp: Any) -> list[dict[str, str]]:
    """The signed option set, when the core issued one. Kept as plain dicts here; the
    `Ask` output types them (P4.2)."""
    if resp.WhichOneof("outcome") != "awaiting":
        return []
    return [
        {
            "id": o.id,
            "label": o.label,
            "ref": o.target_ref or o.resolved_id,
            "entity_type_ref": o.entity_type_ref,
        }
        for o in resp.awaiting.options
    ]


class LiveCore:
    """The real door over gRPC. `.resolve(...)` is all the graph needs (CorePort)."""

    def __init__(self, target: str = DEFAULT_TARGET, timeout_s: float = 60.0):
        self.target = target
        self.timeout_s = timeout_s
        self.last_response: Any | None = None

    async def resolve(
        self,
        *,
        question: str,
        locale: str = "cs",
        conversation_id: str = "",
        caller_subject: str = "",
    ) -> ResolutionState:
        req = resolver_pb2.ResolveRequest(
            conversation_id=conversation_id,
            fresh=resolver_pb2.FreshQuestion(text=question, locale=locale),
            caller_subject=caller_subject,
        )
        async with grpc.aio.insecure_channel(self.target) as channel:
            stub = resolver_pb2_grpc.ResolverServiceStub(channel)
            resp = await stub.Resolve(req, timeout=self.timeout_s)
        self.last_response = resp
        return response_to_state(resp, question=question)

    async def resume(
        self,
        *,
        token: str,
        selected_option_id: str = "",
        free_text_answer: str = "",
        conversation_id: str = "",
        caller_subject: str = "",
    ) -> ResolutionState:
        """The core's own resume (`ResumeAnswer`). Distinct from OUR resume, which
        rejoins `assess_gaps` — this one hands the core back its signed pin so it can
        re-check the subject and rebuild the binding itself."""
        req = resolver_pb2.ResolveRequest(
            conversation_id=conversation_id,
            resume=resolver_pb2.ResumeAnswer(
                token=token,
                selected_option_id=selected_option_id,
                free_text_answer=free_text_answer,
            ),
            caller_subject=caller_subject,
        )
        async with grpc.aio.insecure_channel(self.target) as channel:
            stub = resolver_pb2_grpc.ResolverServiceStub(channel)
            resp = await stub.Resolve(req, timeout=self.timeout_s)
        self.last_response = resp
        return response_to_state(resp)
