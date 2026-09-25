# SPDX-License-Identifier: Apache-2.0
"""One named pipeline, run end to end (contracts §7, NL-4/NL-14).

Engine ops, then gazetteer lists, then rule phases — in that order, and within
each stage in the order the pipeline wrote. The order is not a detail: the
gazetteer matches on `lemma`, which an engine op has to have produced, and the
rules match on `Lookup`, which the gazetteer has to have produced. A pipeline that
listed them the other way round would run, match nothing, and look exactly like a
pack with a typo in it.

**Degrade is a message, not a failure** (NL-14). An op the active lane cannot
route is skipped, `NLS-NLP-011` is appended, and every remaining phase still runs.
The Czech invoices hero is the reason: without cs NER the pack's fallback rule
reaches the same `QueryPattern` through morphology, and aborting the request would
throw away an answer the pack author explicitly arranged to still be reachable.

**Every stage is traced** (`PhaseTrace`, contracts §2.2). One entry per executed
step with what it added and how long it took, because "the rules produced nothing"
has at least four causes — no tokens, no lemmas, no Lookups, no match — and the
trace is what tells them apart without a second request.

**The morph front (LM, NLS-P9.1).** A pipeline marked `morph: true` replaces the
first two engine ops with two in-process ones: our own tokenizer and the snapshot
annotator (`MORPH_TOKENIZE`, `MORPH_ANNOTATE`). Nothing about the front's
engine-free posture changes — the whole of `ttrnlp.morph` is stdlib plus a file,
which is what `tests/test_engine_free_front.py` exists to keep true.

Three things about that swap are worth stating where they happen.

*It is language-gated by the artifact, not by config.* The snapshot declares
its own language; a Czech snapshot in front of an English request would tokenize
by Czech rules and look up Czech forms, and every answer would be a miss reported
to the queue. So the morph front runs when the resolved language IS the
snapshot's, and any other language takes the engine path it always took.

*The morph tokenizer owns the `Token` layer.* Ops morph does not cover
(`SENTENCE_SPLIT`, `NER`, `DEP_PARSE`) still run, and their sentences and
entities are merged onto the morph document — but their tokens are dropped,
because two tokenizers over one text produce two `Token` layers at different
offsets and every rule after them would match against whichever it met first.
One substrate, produced once (LM-9).

*The queue is written after the run, not during it.* `annotate_morph` reports
every non-lexicon answer to a miss sink, and the sink accumulates in memory; the
single flush happens here, once, when the document is finished.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Any

from ttrnlp.doc import Document, build_document
from ttrnlp.doc.importers import TOKEN_TYPE
from ttrnlp.morph import annotate_morph, build_morph_gazetteer
from ttrnlp.morph import build_document as morph_build_document
from ttrnlp.packs.loader import LoadedState
from ttrnlp.rules.pipeline import run_phases

from nlp_service.config import (
    STEP_MORPH_ANNOTATE,
    STEP_MORPH_TOKENIZE,
    AppConfig,
    PipelineConfig,
)
from nlp_service.diagnostics import NLS_NLP_011, message
from nlp_service.engines import EngineRegistry
from nlp_service.engines.base import EngineVersion, NlpOp
from nlp_service.morph_queue import NullSink, QueueSink
from nlp_service.morph_state import MORPH_ENGINE, MorphSnapshot
from nlp_service.pipeline.orchestrator import Orchestrator
from nlp_service.routing import normalize_language

logger = logging.getLogger(__name__)

KIND_ENGINE = "engine"
KIND_GAZETTEER = "gazetteer"
KIND_RULES = "rules"
KIND_MORPH = "morph"


@dataclass
class Trace:
    """One executed step (contracts §2.2 `PhaseTrace`)."""

    phase: str
    kind: str
    annotations_added: int
    elapsed_ms: int


@dataclass
class PipelineResult:
    document: Document
    language: str
    language_confidence: float = 1.0
    used: list[EngineVersion] = field(default_factory=list)
    traces: list[Trace] = field(default_factory=list)
    messages: list[dict] = field(default_factory=list)
    trace_id: str = ""
    elapsed_ms: int = 0


class UnknownPipelineError(LookupError):
    """The requested pipeline is not configured. Carries what is."""

    def __init__(self, name: str, available: list[str]):
        self.name = name
        self.available = available
        configured = ", ".join(sorted(available)) or "none configured"
        super().__init__(f"unknown pipeline {name!r} — configured: {configured}")


def _ops_of(spec: PipelineConfig) -> list[NlpOp]:
    """The spec's ops as enum members, skipping names this build does not know.

    An unknown op name is a config error, but not one worth failing a request
    over: the pipeline table is validated at boot, and by the time a request
    arrives the useful thing is to run what is recognisable.
    """
    ops: list[NlpOp] = []
    for name in spec.ops:
        try:
            ops.append(NlpOp(name))
        except ValueError:
            logger.warning("pipeline op %r is not a known NlpOp — skipped", name)
    return ops


class PipelineRunner:
    """Runs configured pipelines against the current pack snapshot."""

    def __init__(
        self,
        config: AppConfig,
        registry: EngineRegistry,
        orchestrator: Orchestrator | None = None,
        *,
        morph: MorphSnapshot | None = None,
        queue: QueueSink | None = None,
    ):
        self._config = config
        self._registry = registry
        self._orchestrator = orchestrator or Orchestrator(config, registry)
        self._morph = morph
        self._queue = queue or NullSink()
        #: The match-if-any gazetteer (LM-8), built per pack snapshot and keyed
        #: on its id. Compiling the tries is the expensive part and the pack
        #: state is immutable, so one build per snapshot is both correct and the
        #: only one that can happen.
        self._morph_gazetteers: dict[str, object] = {}

    def pipeline_names(self) -> list[str]:
        return sorted(self._config.pipelines)

    def uses_morph(self, spec: PipelineConfig, language: str) -> bool:
        """Whether this run takes the morph front.

        Both halves have to be true: the pipeline asked for it, and the loaded
        snapshot is in the language being analysed. The second is why an English
        request against a Czech-only deployment degrades to the engine path
        instead of looking every word up in the wrong language and reporting all
        of them to the enrichment queue.
        """
        if not spec.morph or self._morph is None:
            return False
        state = self._morph.state
        return state is not None and state.manifest.language == language

    def morph_ready(self, spec: PipelineConfig) -> bool:
        """Whether a `morph: true` pipeline has a snapshot at all.

        `RunPipeline` answers `FAILED_PRECONDITION` when it does not — the same
        posture, and for the same reason, as a pack tree that failed to load:
        the request is fine and the server is not ready for it.
        """
        if not spec.morph:
            return True
        return self._morph is not None and self._morph.state is not None

    def unrouted_ops(self, language: str, ops: list[NlpOp]) -> list[NlpOp]:
        """Ops the active lane cannot serve — the NL-14 degrade set.

        "Cannot serve" means the route fell through to the degrade floor AND the
        floor cannot produce the op either. A floor-served TOKENIZE is a real
        (if plain) result and not a degrade.
        """
        return [
            op
            for op in ops
            if self._registry.route(language, op).is_floor
            and not self._registry.floor_serves(language, op)
        ]

    def run(
        self,
        text: str,
        pipeline: str,
        state: LoadedState,
        *,
        language: str = "",
    ) -> PipelineResult:
        """Run `pipeline` over `text` against `state`.

        Raises:
            UnknownPipelineError: If `pipeline` is not configured.
        """
        spec = self._config.pipelines.get(pipeline)
        if spec is None:
            raise UnknownPipelineError(pipeline, self.pipeline_names())

        started = time.perf_counter()
        traces: list[Trace] = []
        messages: list[dict] = []

        doc, language, confidence, used, engine_traces = self._run_front(
            text, language, spec, messages
        )
        traces.extend(engine_traces)
        traces.extend(self._run_gazetteer(doc, spec, state, language))
        traces.extend(self._run_rules(doc, spec, state))

        doc.features["pipeline"] = pipeline
        doc.features["lane"] = self._config.lane

        # One write per request, off the hot path (`morph_queue` module
        # docstring). A pipeline that reported nothing flushes nothing.
        self._queue.flush()

        return PipelineResult(
            document=doc,
            language=language,
            language_confidence=confidence,
            used=used,
            traces=traces,
            messages=messages,
            elapsed_ms=int((time.perf_counter() - started) * 1000),
        )

    # ---- stages -----------------------------------------------------------

    def _run_front(
        self, text: str, language: str, spec: PipelineConfig, messages: list[dict]
    ) -> tuple[Document, str, float, list[EngineVersion], list[Trace]]:
        """Produce the annotated substrate: the morph front, or the engine one.

        **Detection comes first, and everything after it is decided against the
        resolved language.** The NL-14 degrade set is language-specific
        (`NER.cs` is unrouted in the default lane and `NER.en` is not) and so,
        since NLS-P9.1, is the choice of front. Deciding either against the
        *requested* language is only right when there is one; on an empty
        `language` this used to fall back to `default_language`, which meant an
        English document on a `cs` default lost NER to a degrade computed for
        Czech and got an `NLS-NLP-011` naming `cs/NER` — a phase silently
        missing its input, and a message pointing at the wrong language while the
        document said `en`. `Analyze` computes its degrade the same way.
        """
        resolved, confidence, used = self._resolve_language(text, language, messages)
        if self.uses_morph(spec, resolved):
            return self._run_morph(text, resolved, confidence, used, spec, messages)
        return self._run_engines(text, resolved, confidence, used, spec, messages)

    def _resolve_language(
        self, text: str, language: str, messages: list[dict]
    ) -> tuple[str, float, list[EngineVersion]]:
        """The requested language, or a detection pass when there is none.

        A detection-only call into the orchestrator, exactly as Analyze makes
        when `language` is empty (contracts §2.2). It costs no backend hop at
        all: langid is lingua, in-process, the one engine that never left the
        front — which is what lets the morph pipeline stay engine-free while
        still detecting the language it is about to decide on.
        """
        if language:
            # Folded like every other caller-supplied tag. `resolved` does not
            # only pick routes (`route()` folds for itself) — it becomes the
            # Document's `language` and the morph PROFILE key, and a profile
            # named `cs-CZ` exists nowhere.
            return normalize_language(language), 1.0, []

        detection = self._orchestrator.analyze(
            text=text, language="", ops={NlpOp.DETECT_LANGUAGE}
        )
        messages.extend(detection.messages)
        return (
            detection.language or self._config.default_language,
            detection.language_confidence,
            list(detection.used),
        )

    def _run_engines(
        self,
        text: str,
        resolved: str,
        confidence: float,
        used: list[EngineVersion],
        spec: PipelineConfig,
        messages: list[dict],
    ) -> tuple[Document, str, float, list[EngineVersion], list[Trace]]:
        """Engine ops through the existing orchestrator, then into a Document.

        The orchestrator is reused rather than reimplemented: it already does
        lane-routed grouping, one call per backend, the token merge and the S-1
        `used[]` stamping, and a second path through the same backends would
        drift from `Analyze` in ways only a live cluster would show.
        """
        started = time.perf_counter()
        ops = _ops_of(spec)
        wanted = self._wanted_ops(ops, resolved, messages)

        analysis = self._orchestrator.analyze(
            text=text, language=resolved, ops=set(wanted)
        )
        elapsed = int((time.perf_counter() - started) * 1000)

        doc = build_document(
            text, _as_engine_results(analysis), language=resolved
        )

        # The orchestrator's own diagnostics ride along — a Lindat tier or an S-1
        # violation matters just as much on this rpc as on Analyze.
        messages.extend(analysis.messages)
        used.extend(analysis.used)

        traces = [
            Trace(
                phase=",".join(sorted(op.value for op in wanted)) or "none",
                kind=KIND_ENGINE,
                annotations_added=len(doc.annset("")),
                elapsed_ms=elapsed,
            )
        ]
        return doc, resolved, confidence, used, traces

    def _wanted_ops(
        self, ops: list[NlpOp], resolved: str, messages: list[dict]
    ) -> set[NlpOp]:
        """`ops` minus what the active lane cannot serve, saying so (NL-14)."""
        wanted = set(ops)
        for op in self.unrouted_ops(resolved, ops):
            messages.append(message(NLS_NLP_011, f"{resolved}/{op.value}"))
            wanted.discard(op)
        return wanted

    # ---- the morph front (LM, NLS-P9.1) -----------------------------------

    def _run_morph(
        self,
        text: str,
        resolved: str,
        confidence: float,
        used: list[EngineVersion],
        spec: PipelineConfig,
        messages: list[dict],
    ) -> tuple[Document, str, float, list[EngineVersion], list[Trace]]:
        """Our tokenizer, our snapshot, and whatever engine ops are left.

        `used[]` gains a row for the lexicon leg (S-1: model identity on the
        wire). The `model` is the snapshot's content hash and the `model_version`
        its declared version, which is a stronger identity claim than any
        backend on the estate can make: a caller can tell two runs apart by the
        artifact's own bytes, not by a tag somebody moved.
        """
        assert self._morph is not None and self._morph.state is not None
        state = self._morph.state
        stats = state.stats()
        traces: list[Trace] = []

        started = time.perf_counter()
        doc = morph_build_document(text, profile=resolved, language=resolved)
        traces.append(
            Trace(
                phase=STEP_MORPH_TOKENIZE,
                kind=KIND_MORPH,
                annotations_added=len(doc.annset("")),
                elapsed_ms=int((time.perf_counter() - started) * 1000),
            )
        )

        started = time.perf_counter()
        annotated = annotate_morph(
            doc,
            state,
            miss_sink=self._queue.miss_sink(),
            world=self._morph.world,
        )
        traces.append(
            Trace(
                phase=STEP_MORPH_ANNOTATE,
                kind=KIND_MORPH,
                # Features on existing tokens, not new annotations — the count
                # says how many words were looked up, which is the number a
                # reader of this trace is actually asking about.
                annotations_added=annotated,
                elapsed_ms=int((time.perf_counter() - started) * 1000),
            )
        )

        used.append(
            EngineVersion(
                op=NlpOp.LEMMATIZE.value,
                engine=MORPH_ENGINE,
                model=stats.content_hash,
                model_version=stats.version,
            )
        )

        traces.extend(self._run_remaining_ops(text, resolved, doc, spec, messages, used))
        doc.features["morph_snapshot"] = stats.version
        return doc, resolved, confidence, used, traces

    def _run_remaining_ops(
        self,
        text: str,
        resolved: str,
        doc: Document,
        spec: PipelineConfig,
        messages: list[dict],
        used: list[EngineVersion],
    ) -> list[Trace]:
        """The ops morph does not cover, merged onto the morph document.

        **Tokens are dropped and everything else is kept.** The morph tokenizer
        owns the `Token` layer (LM-9); an engine asked for `NER` still tokenizes
        on its way there, and importing those tokens would put every word in the
        document twice at two sets of offsets. Sentences and entities are exactly
        what the remaining ops were asked for, and they are merged by rebuilding
        the engine's own document and copying its non-token annotations across —
        rather than by reimplementing the label mapping, which is where the CNEC
        tag handling lives and is not worth having a second copy of.

        Returns no trace at all when there is nothing left to run, because a
        `PhaseTrace` reading `engine / none / 0ms` on an engine-free request is
        an entry that has to be explained every time somebody reads it.
        """
        leftover = set(spec.engine_ops())
        remaining = [op for op in _ops_of(spec) if op.value in leftover]
        if not remaining:
            return []

        started = time.perf_counter()
        wanted = self._wanted_ops(remaining, resolved, messages)
        if not wanted:
            return []

        analysis = self._orchestrator.analyze(
            text=text, language=resolved, ops=set(wanted)
        )
        messages.extend(analysis.messages)
        used.extend(analysis.used)

        engine_doc = build_document(
            text, _as_engine_results(analysis), language=resolved
        )
        annset = doc.annset("")
        before = len(annset)
        for ann in engine_doc.annset(""):
            if ann.type == TOKEN_TYPE:
                continue
            annset.add(ann.start, ann.end, ann.type, dict(ann.features))

        return [
            Trace(
                phase=",".join(sorted(op.value for op in wanted)),
                kind=KIND_ENGINE,
                annotations_added=len(annset) - before,
                elapsed_ms=int((time.perf_counter() - started) * 1000),
            )
        ]

    def _gazetteer(self, spec: PipelineConfig, state: LoadedState, language: str):
        """The gazetteer this run matches with.

        A morph run gets the **match-if-any** one (LM-8): the trie step reads the
        token's whole ranked `lemmas` list rather than the head of it. That is
        not a refinement. *byt* and *být* fold together and *má* is *mít* or
        *můj*, so with a single-key gazetteer the ranking — a frequency order
        with nothing behind it — silently decides which of a world's own lists
        matches, and the world loses to a frequency table.

        Built from the same `GazetteerList` objects the pack snapshot compiled,
        so the list schema, the ids and the `NLS-PACK-*` codes keep meaning
        exactly what they meant; only the trie step differs. Cached per pack
        snapshot, which is immutable, so this happens once.
        """
        if not (spec.morph and self.uses_morph(spec, language)):
            return state.gazetteer
        built = self._morph_gazetteers.get(state.state_id)
        if built is None:
            built = build_morph_gazetteer(state.gazetteer.lists)
            # ⚑ Only the current snapshot's. Keyed on `state_id` and never
            # evicted, every `ReloadPacks` pinned another whole trie for the
            # life of the process — a front reloaded on each pack edit grew
            # without bound while every entry but one was unreachable. Pack
            # snapshots are immutable and superseded whole, so the previous
            # build is dead the moment a new `state_id` arrives.
            self._morph_gazetteers = {state.state_id: built}
        return built

    def _run_gazetteer(
        self,
        doc: Document,
        spec: PipelineConfig,
        state: LoadedState,
        language: str,
    ) -> list[Trace]:
        traces: list[Trace] = []
        gazetteer = self._gazetteer(spec, state, language)
        for list_id in spec.gazetteer:
            started = time.perf_counter()
            added = gazetteer.annotate(doc, lists=[list_id])
            traces.append(
                Trace(
                    phase=list_id,
                    kind=KIND_GAZETTEER,
                    annotations_added=added,
                    elapsed_ms=int((time.perf_counter() - started) * 1000),
                )
            )
        return traces

    def _run_rules(
        self, doc: Document, spec: PipelineConfig, state: LoadedState
    ) -> list[Trace]:
        traces: list[Trace] = []
        for ref in spec.rules:
            pack = state.packs.get(ref.pack)
            if pack is None:
                # Boot validated every ref (NLS-PACK-004), so reaching this means
                # the snapshot changed under a request. Skip and say so rather
                # than raising: the phases that did run produced real annotations.
                logger.warning("pipeline references missing pack %r", ref.pack)
                continue
            started = time.perf_counter()
            before = len(doc.annset(""))
            report = run_phases(doc, [pack], phases=[ref.phase])
            traces.append(
                Trace(
                    phase=f"{ref.pack}:{ref.phase}",
                    kind=KIND_RULES,
                    annotations_added=len(doc.annset("")) - before,
                    elapsed_ms=int((time.perf_counter() - started) * 1000),
                )
            )
            logger.debug("phase %s:%s fired %d rule(s)", ref.pack, ref.phase, report.firings)
        return traces


def _as_engine_results(analysis) -> list[dict[str, Any]]:
    """The orchestrator's merged analysis in the importers' uniform shape.

    One result, not one per engine: the orchestrator has already merged tokens to
    one per span and resolved which engine's lemma/POS/parse won, and handing the
    importers the pre-merge streams would put every word in the document once per
    engine that saw it.
    """
    return [
        {
            "engine": analysis.engine_used,
            "modelVersion": next(
                (ev.model_version for ev in analysis.used if ev.model_version), ""
            ),
            "tokens": [
                {
                    "text": t.text,
                    "charStart": t.char_start,
                    "charEnd": t.char_end,
                    "lemma": t.lemma,
                    "upos": t.upos,
                    "xpos": t.xpos,
                    "feats": dict(t.feats or {}),
                    "depHead": t.dep_head,
                    "depRelation": t.dep_relation,
                }
                for t in analysis.tokens
            ],
            "sentences": [
                {"charStart": start, "charEnd": end} for start, end in analysis.sentences
            ],
            "entities": [
                {
                    "text": e.text,
                    "label": e.label,
                    "charStart": e.char_start,
                    "charEnd": e.char_end,
                    "normalizedValue": e.normalized_value,
                    "sourceEngine": e.source_engine,
                }
                for e in analysis.entities
            ],
        }
    ]


__all__ = [
    "KIND_ENGINE",
    "KIND_MORPH",
    "KIND_GAZETTEER",
    "KIND_RULES",
    "PipelineResult",
    "PipelineRunner",
    "Trace",
    "UnknownPipelineError",
]
