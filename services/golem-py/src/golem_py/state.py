# SPDX-License-Identifier: Apache-2.0
"""`ResolutionState` as Pydantic models — the Python mirror of the annotation lattice.

The lattice is `contracts.md` §1 as SHIPPED at RV-P2.1: the law is
`shared/proto/.../resolver/v1/resolver.proto` (`ResolveResponse.resolution_state = 7`).
This module mirrors that proto field for field, plus the turn-local loop state the
RV-11 loop needs. Because every field is a Pydantic model, `model_dump_json()` IS the
pause snapshot and `model_validate_json()` IS the resume — the whole persistence story
(pydantic-graph 2.22 has no built-in persistence; see `graph.py`).

Clean-room: typed from `contracts.md` + the shared `.proto` and nothing else. See
PROVENANCE.md.

⚑ Three shape notes, each a decision rather than an accident:

1. `Mention.frame_roles` and `GapRecord.frame_roles` are LISTS. The Q-15 spike
   (RV-P0.2) proved a singular field cannot express the corpus — 32 of 137 mentions
   carry two roles at once (measure-as-subject) — and `p2-1` shipped both as
   `repeated`.
2. `RungLogEntry` mirrors the PROTO's shape (`round`, `action`, `mention_ids`,
   `bindings_added`, `gaps_open`), not the P0-3 spike's ad-hoc one. The rung log is a
   wire-carried audit trail that the core writes round 0 of; a Golem-local shape would
   have made the two halves of one list disagree.
3. Everything the door cannot say is left EMPTY, never invented. That rule is what
   makes a lattice reproducible (RV-39: same layer tuple + same question ⇒ same
   lattice).
"""

from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, Field

# --------------------------------------------------------------------------- enums
# Values are the proto enum names with the `*_` prefix stripped: the prefix exists
# because proto3 enum values are siblings in the enclosing scope, which is a
# proto-file problem and not ours. `TargetClass.MEMBER` reads the way contracts.md
# writes it. `core_client.py` owns the (de)prefixing, in exactly one place.


class TargetClass(StrEnum):
    """RV-38 — what a binding's target IS. The kind derives from this; never stored."""

    UNSPECIFIED = "UNSPECIFIED"
    MODEL_OBJECT = "MODEL_OBJECT"
    MEMBER = "MEMBER"
    OPERATOR = "OPERATOR"
    GROUNDING_TRIGGER = "GROUNDING_TRIGGER"


class EvidenceClass(StrEnum):
    """RV-14 — ordered, strongest first. WEAK never binds, whatever its score.

    UNSPECIFIED sorts ABOVE EXACT in the proto's numbering and must be treated as
    weaker than everything; `rank()` does that rather than leaving it to a comparison.
    """

    UNSPECIFIED = "UNSPECIFIED"
    EXACT = "EXACT"
    DECLARED_ALIAS = "DECLARED_ALIAS"
    LEARNED_ALIAS = "LEARNED_ALIAS"
    ANCHORED_FUZZY_STRONG = "ANCHORED_FUZZY_STRONG"
    UNANCHORED_FUZZY_STRONG = "UNANCHORED_FUZZY_STRONG"
    WEAK = "WEAK"

    def rank(self) -> int:
        """Lower is stronger. UNSPECIFIED is weakest, not strongest."""
        order = [
            EvidenceClass.EXACT,
            EvidenceClass.DECLARED_ALIAS,
            EvidenceClass.LEARNED_ALIAS,
            EvidenceClass.ANCHORED_FUZZY_STRONG,
            EvidenceClass.UNANCHORED_FUZZY_STRONG,
            EvidenceClass.WEAK,
        ]
        return order.index(self) if self in order else len(order)


class MatchMethod(StrEnum):
    UNSPECIFIED = "UNSPECIFIED"
    EXACT = "EXACT"
    TYPOS = "TYPOS"
    TOKENS = "TOKENS"


class SourceTag(StrEnum):
    UNSPECIFIED = "UNSPECIFIED"
    DECLARED = "DECLARED"
    METADATA = "METADATA"
    DATA = "DATA"
    LEARNED = "LEARNED"
    VOCABULARY = "VOCABULARY"  # legacy: declared|metadata, undifferentiated


class FrameRole(StrEnum):
    UNSPECIFIED = "UNSPECIFIED"
    SUBJECT = "SUBJECT"
    MEASURE = "MEASURE"
    FILTER = "FILTER"
    GROUPING = "GROUPING"


class GapKind(StrEnum):
    UNSPECIFIED = "UNSPECIFIED"
    G1_UNBOUND = "G1_UNBOUND"
    G2_AMBIGUOUS = "G2_AMBIGUOUS"
    G3_UNATTRIBUTED = "G3_UNATTRIBUTED"
    G4_METHOD_MISS = "G4_METHOD_MISS"
    G5_NLP_DARK = "G5_NLP_DARK"
    G6_INCOHERENT = "G6_INCOHERENT"


class Disposition(StrEnum):
    UNSPECIFIED = "UNSPECIFIED"
    UNRESOLVED = "UNRESOLVED"
    IGNORED = "IGNORED"
    DEGRADED = "DEGRADED"
    USER_CONFIRMED_UNKNOWN = "USER_CONFIRMED_UNKNOWN"


class ValueKind(StrEnum):
    UNSPECIFIED = "UNSPECIFIED"
    LITERAL = "LITERAL"
    GROUNDED = "GROUNDED"
    # LP contracts §2 — a QUOTED literal, taken as typed. Its text is `verbatim_text`
    # (delimiters removed), never `span.text` (the span keeps the quotes the user typed).
    VERBATIM = "VERBATIM"


# ------------------------------------------------------------------------ messages


class Span(BaseModel):
    start: int = 0
    end: int = 0
    text: str = ""


class Provenance(BaseModel):
    """The proto's `BindingProvenance` (S-1/S-4). One provenance type, not two."""

    vocabulary_source: str = ""
    algorithm: str = ""
    score: float = 0.0
    tier: str = ""
    snapshot_hash: str = ""
    model_versions: list[str] = Field(default_factory=list)
    proposing_rung: str = ""  # empty for the core's own deterministic pass


class Binding(BaseModel):
    ref: str = ""
    target_class: TargetClass = TargetClass.UNSPECIFIED
    evidence_class: EvidenceClass = EvidenceClass.UNSPECIFIED
    method: MatchMethod = MatchMethod.UNSPECIFIED
    # The three method parameters are OPTIONAL on the wire because absent ≠ 0/false:
    # `auto_bindable = false` means "do not auto-bind", absent means "no uniqueness
    # decision applies here". Keep that distinction; a default would misreport it.
    max_distance: int | None = None
    uniqueness_margin: float | None = None
    auto_bindable: bool | None = None
    source: SourceTag = SourceTag.UNSPECIFIED
    in_class_score: float = 0.0
    producer: Provenance = Field(default_factory=Provenance)


class Mention(BaseModel):
    """A content nominal phrase ⇄ 0..n candidate bindings (RV-2). Zero bindings is a
    first-class unknown (P-3) and is what raises G1."""

    id: str
    span: Span = Field(default_factory=Span)
    lemma: str = ""
    frame_roles: list[FrameRole] = Field(default_factory=list)
    bindings: list[Binding] = Field(default_factory=list)


class Attribution(BaseModel):
    """ "This literal could be a value of THAT attribute." The member binding's own ref
    is `<attribute_ref>#<id>`."""

    attribute_ref: str = ""
    binding: Binding | None = None


class Grounding(BaseModel):
    kernel: str = ""  # chrono | money | geo | the NER engine that typed it
    kind: str = ""  # DATE | MONEY | LOCATION | PERSON | ORGANIZATION | MISC
    normalized_value: str = ""
    ref: str = ""  # the `ground:` trigger ref when a slice anchored it (RV-42)
    confidence: float = 0.0


class ValueFinding(BaseModel):
    id: str
    span: Span = Field(default_factory=Span)
    kind: ValueKind = ValueKind.UNSPECIFIED
    attributions: list[Attribution] = Field(default_factory=list)
    grounding: Grounding | None = None
    # The BOUND mention whose categories scoped the lookup (RV-33). Empty is meaningful:
    # it separates G3 (nothing to miss) from G4 (a known scope that missed).
    anchor_mention_id: str = ""
    # LP contracts §2 — both OPTIONAL on the wire and meaningful only on a VERBATIM value.
    # `verbatim_text` is the §1.4 text (delimiters removed, outer whitespace trimmed);
    # `predicate_ref` is a `pred:` ref, absent when the question named no operator (then
    # §2.2's default applies — the consumer's call, not "equals").
    verbatim_text: str | None = None
    predicate_ref: str | None = None


class Hypothesis(BaseModel):
    """A proposal from a rung. RV-7 proposer-not-binder: it becomes a binding only by
    surviving the same gate as any other candidate (`resolve.gate:v1`)."""

    span: Span | None = None
    ref: str = ""
    correction: str = ""
    proposing_rung: str = ""


class GapRecord(BaseModel):
    span: Span = Field(default_factory=Span)
    kind: GapKind = GapKind.UNSPECIFIED
    frame_roles: list[FrameRole] = Field(default_factory=list)
    hypotheses_tried: list[Hypothesis] = Field(default_factory=list)
    asked_round: int = 0
    user_answer: str = ""
    disposition: Disposition = Disposition.UNRESOLVED
    mention_id: str = ""  # set iff the gap sits on a mention
    value_id: str = ""  # set iff the gap sits on a value

    def is_load_bearing(self) -> bool:
        """RV-15: an ask fires on a load-bearing gap only.

        SUBJECT is load-bearing, always. Beyond that the answer differs by WHICH layer
        the gap sits on, and the difference is deliberate (⚑ ruled at P4.2·T6):

        * a **mention** gap with no roles at all IS load-bearing. A content phrase the
          core could not bind is the question's own vocabulary; nothing shows it is
          safe to ignore, and under uncertainty the honest posture is to ask rather
          than to drop silently.
        * a **value** gap with no roles is NOT. A value's structural position is its
          ANCHOR's (contracts §1), so an unanchored literal — H2's *Praze*, a LOCATION
          hint nothing attributed — has no position to be load-bearing in. Asking about
          it would spend the conversation's single question on a word the answer does
          not depend on; carrying it as a gap note (RV-19) is what honesty looks like
          here.
        """
        if FrameRole.SUBJECT in self.frame_roles:
            return True
        if [r for r in self.frame_roles if r != FrameRole.UNSPECIFIED]:
            return False
        return not self.value_id


class RungLogEntry(BaseModel):
    """One entry of the ladder's audit trail; mirrors the proto. The core writes round
    0 for its own pass, the rungs and the re-gate append."""

    round: int = 0
    rung: str = ""  # "core" | lookup | local | capable | emulated
    action: str = ""  # e.g. "annotate", "lookup", "regate", "noop"
    mention_ids: list[str] = Field(default_factory=list)
    value_ids: list[str] = Field(default_factory=list)
    hypotheses: list[Hypothesis] = Field(default_factory=list)
    bindings_added: int = 0
    gaps_open: int = 0
    elapsed_ms: int = 0
    # Golem-local, not on the wire: why a round did nothing. RV-27's "full SHAPE
    # present" is only auditable if the no-op says which rung list was empty.
    note: str = ""


class LexiconVersions(BaseModel):
    """RV-39 layer tuple, echoed per S-1 so a lattice is reproducible.

    ⚠ `lexicon_artifact_hash` is a hash over the ENTRY TABLE, not the archive file —
    the archive's own id (`vocabularyVersion`) is a different number by design, so an
    archive repacked with unchanged terms keeps this one still.
    """

    lexicon_artifact_hash: str = ""
    member_index_versions: dict[str, str] = Field(default_factory=dict)
    overlay_version: str | None = None  # ABSENT until RV-P7 — absence is the contract


class SignedOption(BaseModel):
    """One option from the core's `AwaitingClarification` — SIGNED INTO the resume
    token. The `id` is what travels back; the ref is carried only so the ask can be
    rendered and so a pin can be turned into a hypothesis about a real ref.

    We do not invent options. An ask with an empty option set is honest: it means the
    core did not clarify this span, so the only answer available is free text or the
    escape."""

    id: str = ""
    label: str = ""
    ref: str = ""
    entity_type_ref: str = ""
    span: Span | None = None


class Pin(BaseModel):
    """The user's answer to an ask, carried into the resume turn.

    Two shapes, and the difference is load-bearing (RV-7): `option_id` names an option
    the CORE signed into the resume token, so it re-enters through `resolve.gate` as a
    hypothesis with user provenance; `free_text` is an amendment to the question, which
    can only be honoured by resolving again.
    """

    option_id: str = ""
    free_text: str = ""
    # The escape hatch (RV-15): "none of these" is a legitimate ANSWER — it settles the
    # gap as USER_CONFIRMED_UNKNOWN instead of leaving it open forever — and it must
    # never be mistaken for a free-text amendment, which re-resolves.
    escape: bool = False

    def is_option(self) -> bool:
        return bool(self.option_id)

    def is_escape(self) -> bool:
        return self.escape


class ResolutionState(BaseModel):
    """The lattice (the door's output) + the turn-local loop state.

    Everything the agent knows lives here — that is what makes a snapshot complete
    enough to resume from, and it is why the snapshot store needs no per-field schema.
    """

    # --- the lattice (the door's output, contracts §1) -------------------------
    parse: dict[str, object] = Field(default_factory=dict)
    mentions: list[Mention] = Field(default_factory=list)
    values: list[ValueFinding] = Field(default_factory=list)
    gaps: list[GapRecord] = Field(default_factory=list)
    rung_log: list[RungLogEntry] = Field(default_factory=list)
    lexicon_versions: LexiconVersions = Field(default_factory=LexiconVersions)

    # --- turn-local loop state (NOT part of the door contract) -----------------
    conversation_id: str = ""
    turn_id: str = ""
    question: str = ""
    locale: str = "cs"
    profile: str = "CHAT_QUICK"  # ⚑RV-3 / contracts §3
    caller_subject: str = ""  # OBO subject; re-checked by the core on resume
    llm_invocations: int = 0
    hitl_rounds: int = 0
    # ⛑ The instant THIS RUN started, from the injected clock. The ladder's wall-clock
    # budget is a property of the turn, so it cannot be re-read per node — a budget object
    # built at each node measures the node and never expires (a review found exactly
    # that). `start` re-anchors it on every run, fresh or resumed.
    #
    # `exclude=True` is load-bearing, not tidiness: this is the one field whose value is a
    # LIVE CLOCK READING, and a live reading inside a serialised lattice would make two
    # runs of the same turn differ in their bytes — destroying the byte-identical replay
    # everything else here is built to guarantee. It is also meaningless across processes
    # (monotonic clocks share no epoch), so there is nothing to carry: the snapshot omits
    # it and `start` sets it again before anything reads it.
    turn_started_at: float | None = Field(default=None, exclude=True)
    # Which rungs have already climbed THIS TURN. A rung runs at most once per turn:
    # that is the ladder's own semantics (each rung handles the residue the previous
    # one left) and it is also the loop's termination guarantee — without it a
    # deterministic rung, which spends no LLM budget, could be eligible forever.
    rungs_run: list[str] = Field(default_factory=list)
    pin: Pin | None = None  # the user's answer, applied on resume
    resume_token: str = ""  # RS-26; signed by the CORE, opaque to us
    # The option set the core signed into that token. Stored, never minted.
    signed_options: list[SignedOption] = Field(default_factory=list)
    # The gap the outstanding ask is about — so a resume knows what the user answered
    # without re-deriving it from an ask order that may have changed.
    asked_gap_span: Span | None = None
    trace_id: str = ""

    def open_gaps(self) -> list[GapRecord]:
        return [g for g in self.gaps if g.disposition == Disposition.UNRESOLVED]

    def load_bearing_gaps(self) -> list[GapRecord]:
        return [g for g in self.open_gaps() if g.is_load_bearing()]

    def next_round(self) -> int:
        return max((e.round for e in self.rung_log), default=0) + 1
