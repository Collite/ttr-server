# SPDX-License-Identifier: Apache-2.0
"""NLP orchestrator (RG-P1.S1).

Groups requested ops by their routed engine (via `EngineRegistry.route`), calls
each backend once, merges/dedups/sorts, and stamps the S-1 `used[]` echo — one
`EngineVersion` per served op, naming engine + explicit model + version. Ops
that route to the degrade floor are labelled `RG-NLP-010` (the floor's own
producers land in RG-P1.S3). COMPARE stays a debug fan-out, excluded from
conformance.
"""

from __future__ import annotations

import logging
import secrets
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field, replace
from typing import Dict, List, Optional, Set

from nlp_service.config import AppConfig, load_config
from nlp_service.contract import iter_s1_violations
from nlp_service.diagnostics import RG_NLP_003, RG_NLP_010, message, split_code
from nlp_service.engines import EngineRegistry
from nlp_service.engines.base import EngineResult, EngineVersion, NerEntity, NlpOp, Token
from nlp_service.floor import FloorEngine
from nlp_service.routing import normalize_language

logger = logging.getLogger(__name__)


def _engine_failure(engine_name: str, error: str) -> dict:
    """The response message for an engine that returned nothing but an error.

    An engine that named a registered diagnostic keeps it: `RV-NLP-020` (gateway
    unreachable) and `RV-NLP-021` (unusable model output) are registered codes
    with distinct meanings, and flattening both into `engine_error` left a caller
    filtering `messages[].code` unable to tell an outage from an engine that is
    answering nonsense — one is waited out, the other is a prompt or a model
    change. Anything untyped stays `engine_error`, exactly as before.
    """
    code, detail = split_code(error)
    if code:
        return message(code, f"{engine_name}: {detail}" if detail else engine_name)
    return {"severity": "ERROR", "code": "engine_error", "message": f"{engine_name} failed: {error}"}


class NlpPipelineError(Exception):
    pass


class EngineNotFoundError(NlpPipelineError):
    pass


@dataclass
class BatchLemmatizeResult:
    """Positional lemma lists + the S-1 model-identity echo."""

    results: List[List[str]] = field(default_factory=list)
    used: List[EngineVersion] = field(default_factory=list)


@dataclass
class AnalyzeResponse:
    """Response object matching the proto AnalyzeResponse shape."""

    language: str
    language_confidence: float
    engine_used: str
    tokens: List[Token] = field(default_factory=list)
    sentences: List[tuple[int, int]] = field(default_factory=list)
    paragraphs: List[tuple[int, int]] = field(default_factory=list)
    entities: List[NerEntity] = field(default_factory=list)
    by_engine: Dict[str, EngineResult] = field(default_factory=dict)
    used: List[EngineVersion] = field(default_factory=list)  # S-1 model identity
    trace_id: str = ""
    elapsed_ms: int = 0
    messages: List[dict] = field(default_factory=list)


class Orchestrator:
    def __init__(self, config: AppConfig | None = None, registry: EngineRegistry | None = None):
        self._config = config or load_config()
        self._registry = registry or EngineRegistry(self._config)
        self._floor = FloorEngine()  # RG-P1.S3: deterministic degrade floor

    def analyze(
        self,
        text: str,
        language: str,
        ops: Set[NlpOp],
        mode: str = "NORMAL",
        engine_hints: Optional[Dict[str, str]] = None,
    ) -> AnalyzeResponse:
        start_time = time.perf_counter()
        trace_id = _generate_trace_id()
        engine_hints = engine_hints or {}
        messages: List[dict] = []
        used: List[EngineVersion] = []

        # A caller's tag may name a region (hartland's Shem sends `en-US` / `cs-CZ`);
        # the routing table and every engine's `supported_languages()` are keyed on
        # the bare subtag. Fold it HERE, once, so the routes, the engines'
        # `analyze(text, language, ops)` and the echoed `language` all agree — and
        # so an empty tag stays empty for the DETECT_LANGUAGE branch below.
        language = normalize_language(language)

        detected_lang = language
        detected_confidence = 1.0

        if NlpOp.DETECT_LANGUAGE in ops:
            lang_result = self._detect_language(text)
            if lang_result.error:
                messages.append(
                    {
                        "severity": "WARNING",
                        "code": "lang_detection_failed",
                        "message": f"Language detection failed: {lang_result.error}. Defaulting to Czech.",
                    }
                )
                if not detected_lang:
                    detected_lang = "cs"
            else:
                if not detected_lang:
                    # Folded too: a detector is just another source of a tag, and
                    # the value goes straight to the engines below.
                    detected_lang = (
                        normalize_language(lang_result.detected_language)
                        or self._config.default_language
                    )
                detected_confidence = lang_result.language_confidence or 1.0
            # S-1: stamp the langid engine that produced the detection.
            langid = self._registry.route(detected_lang or self._config.default_language, NlpOp.DETECT_LANGUAGE)
            used.append(
                EngineVersion(
                    op=NlpOp.DETECT_LANGUAGE.value,
                    engine=langid.engine,
                    model=langid.model,
                    model_version=langid.model_version,
                )
            )
            ops = ops - {NlpOp.DETECT_LANGUAGE}

        if not detected_lang:
            detected_lang = self._config.default_language

        if mode == "COMPARE":
            resp = self._analyze_compare(text, detected_lang, ops, engine_hints, trace_id, start_time, messages)
        else:
            resp = self._analyze_normal(
                text, detected_lang, ops, engine_hints, trace_id, start_time, messages, used
            )
        resp.language_confidence = detected_confidence if resp.language_confidence == 1.0 else resp.language_confidence
        return resp

    def _analyze_normal(
        self,
        text: str,
        language: str,
        ops: Set[NlpOp],
        engine_hints: Dict[str, str],
        trace_id: str,
        start_time: float,
        messages: List[dict],
        used: List[EngineVersion],
    ) -> AnalyzeResponse:
        # Route every op; group the non-floor ones by engine, note the floor ones.
        ops_by_engine: Dict[str, Set[NlpOp]] = {}
        served_ops_engine: Dict[NlpOp, str] = {}
        route_by_engine: Dict[str, object] = {}
        seen_floor = False

        for op in ops:
            hint = engine_hints.get(op.value)
            route = self._registry.route(language, op, hint)
            for code in route.info:
                if code == RG_NLP_010 and seen_floor:
                    continue
                messages.append(message(code, f"{language}/{op.value}"))
                if code == RG_NLP_010:
                    seen_floor = True
            if route.is_floor and not self._floor.supports(language, op):
                # A floor op the floor can't serve (LEMMATIZE/POS/DEP/NER on an
                # unsupported language): labelled RG-NLP-010, produces no output.
                continue
            # is_floor TOKENIZE/SENTENCE_SPLIT falls through: served by the floor
            # engine (route.engine == "floor"), stamped like any other producer.
            ops_by_engine.setdefault(route.engine, set()).add(op)
            served_ops_engine[op] = route.engine
            route_by_engine[route.engine] = route

        engine_results: Dict[str, EngineResult] = {}
        primary_engine = ""
        all_tokens: List[Token] = []
        all_entities: List[NerEntity] = []
        all_sentences: List[tuple[int, int]] = []
        all_paragraphs: List[tuple[int, int]] = []

        for engine_name, engine_ops in ops_by_engine.items():
            engine = self._registry.get_engine(engine_name)
            if engine is None and engine_name == self._floor.name:
                engine = self._floor
            if not engine:
                continue
            result = engine.analyze(text, language, engine_ops)
            if result.error and not result.tokens and not result.entities:
                messages.append(_engine_failure(engine_name, result.error))
                continue
            engine_results[engine_name] = result
            if not primary_engine and result.tokens:
                primary_engine = engine_name
            all_tokens.extend(result.tokens)
            all_entities.extend(result.entities)
            all_sentences.extend(result.sentences)
            all_paragraphs.extend(result.paragraphs)

        # S-1: stamp used[] for each op an engine actually served, naming the
        # engine + explicit model + version from its resolved route.
        for op, engine_name in served_ops_engine.items():
            if engine_name not in engine_results:
                continue
            route = route_by_engine[engine_name]
            used.append(
                EngineVersion(
                    op=op.value,
                    engine=route.engine,
                    model=route.model,
                    model_version=route.model_version,
                )
            )

        for violation in iter_s1_violations(used):
            messages.append(message(RG_NLP_003, violation))

        entities_by_span: Dict[tuple[int, int], NerEntity] = {}
        for ent in all_entities:
            span = (ent.char_start, ent.char_end)
            if span not in entities_by_span:
                entities_by_span[span] = ent
            elif primary_engine and ent.source_engine == primary_engine:
                entities_by_span[span] = ent

        final_entities = list(entities_by_span.values())

        # Merge tokens to ONE per span. Each engine's analyze() returns its OWN token
        # stream, so extending them (the old behaviour) emitted every word once per
        # engine — duplicating the whole sentence and breaking the 1-based dep_head
        # indexing the resolver's anchoring relies on. Route each field to the engine
        # that SERVED its op (lemma←LEMMATIZE, upos/xpos/feats←POS_TAG); take the
        # dependency parse from whichever engine produced it (dep_relation set). Mirrors
        # the entity span-dedup above.
        def _tokens_by_span(engine_name: str) -> Dict[tuple[int, int], Token]:
            res = engine_results.get(engine_name or "")
            return {(t.char_start, t.char_end): t for t in (res.tokens if res else [])}

        lemma_src = _tokens_by_span(served_ops_engine.get(NlpOp.LEMMATIZE, ""))
        pos_src = _tokens_by_span(served_ops_engine.get(NlpOp.POS_TAG, ""))
        dep_src: Dict[tuple[int, int], Token] = {}
        for res in engine_results.values():
            for t in res.tokens:
                span = (t.char_start, t.char_end)
                if t.dep_relation and span not in dep_src:
                    dep_src[span] = t

        merged_by_span: Dict[tuple[int, int], Token] = {}
        for t in all_tokens:
            span = (t.char_start, t.char_end)
            if span in merged_by_span:
                continue
            lemma_t, pos_t, dep_t = lemma_src.get(span), pos_src.get(span), dep_src.get(span)
            merged_by_span[span] = replace(
                t,
                lemma=(lemma_t.lemma if lemma_t and lemma_t.lemma else t.lemma),
                upos=(pos_t.upos if pos_t and pos_t.upos else t.upos),
                xpos=(pos_t.xpos if pos_t and pos_t.xpos else t.xpos),
                feats=(pos_t.feats if pos_t and pos_t.feats else t.feats),
                dep_head=(dep_t.dep_head if dep_t else t.dep_head),
                dep_relation=(dep_t.dep_relation if dep_t else t.dep_relation),
            )

        final_tokens = sorted(merged_by_span.values(), key=lambda t: t.char_start)
        final_sentences = sorted(set(all_sentences), key=lambda s: s[0])
        final_paragraphs = sorted(set(all_paragraphs), key=lambda p: p[0])
        elapsed_ms = int((time.perf_counter() - start_time) * 1000)

        return AnalyzeResponse(
            language=language,
            language_confidence=1.0,
            engine_used=primary_engine,
            tokens=final_tokens,
            sentences=final_sentences,
            paragraphs=final_paragraphs,
            entities=final_entities,
            by_engine={},
            used=used,
            trace_id=trace_id,
            elapsed_ms=elapsed_ms,
            messages=messages,
        )

    def _analyze_compare(
        self,
        text: str,
        language: str,
        ops: Set[NlpOp],
        engine_hints: Dict[str, str],
        trace_id: str,
        start_time: float,
        messages: List[dict],
    ) -> AnalyzeResponse:
        engine_ops: Dict[str, Set[NlpOp]] = {}
        for op in ops:
            for engine in self._registry.get_all_engines_for_op(op, language):
                engine_ops.setdefault(engine.name, set()).add(op)

        engine_results: Dict[str, EngineResult] = {}
        primary_engine = ""

        def run_engine(engine_name: str, eops: Set[NlpOp]) -> tuple[str, EngineResult]:
            engine = self._registry.get_engine(engine_name)
            if not engine:
                return (engine_name, EngineResult(error=f"Engine {engine_name} not found"))
            try:
                return (engine_name, engine.analyze(text, language, eops))
            except Exception as e:  # noqa: BLE001
                logger.exception(f"Engine {engine_name} error: {e}")
                return (engine_name, EngineResult(error=str(e)))

        if engine_ops:
            with ThreadPoolExecutor(max_workers=len(engine_ops)) as executor:
                futures = {executor.submit(run_engine, name, o): name for name, o in engine_ops.items()}
                for future in as_completed(futures):
                    engine_name, result = future.result()
                    engine_results[engine_name] = result
                    if result.error:
                        messages.append(_engine_failure(engine_name, result.error))
                    elif not primary_engine and result.tokens:
                        primary_engine = engine_name

        primary_result = engine_results.get(primary_engine, EngineResult())
        all_entities: List[NerEntity] = []
        for eng_result in engine_results.values():
            all_entities.extend(eng_result.entities)
        entities_by_span: Dict[tuple[int, int], NerEntity] = {}
        for ent in all_entities:
            span = (ent.char_start, ent.char_end)
            if span not in entities_by_span:
                entities_by_span[span] = ent
            elif primary_engine and ent.source_engine == primary_engine:
                entities_by_span[span] = ent

        final_tokens = sorted(primary_result.tokens, key=lambda t: t.char_start) if primary_result.tokens else []
        final_sentences = sorted(set(primary_result.sentences), key=lambda s: s[0])
        final_paragraphs = sorted(set(primary_result.paragraphs), key=lambda p: p[0])
        elapsed_ms = int((time.perf_counter() - start_time) * 1000)

        return AnalyzeResponse(
            language=language,
            language_confidence=1.0,
            engine_used=primary_engine,
            tokens=final_tokens,
            sentences=final_sentences,
            paragraphs=final_paragraphs,
            entities=list(entities_by_span.values()),
            by_engine=engine_results,
            used=[],
            trace_id=trace_id,
            elapsed_ms=elapsed_ms,
            messages=messages,
        )

    def batch_lemmatize(self, texts: List[str], language: str = "") -> BatchLemmatizeResult:
        """Batched lemmatization for fuzzy's RG-P2 lemma axis (RS-6 / C4-T2).

        Fans a single batched request to the routed backend (MorphoDiTa) — the
        front batches to the backend, it does NOT loop per-string HTTP (the
        both-hops requirement, Q-10 §4). Falls back to per-text analyze only when
        the routed engine has no batch path (floor / non-batching).
        """
        language = normalize_language(language) or self._config.default_language
        if not texts:
            return BatchLemmatizeResult(results=[], used=[])

        route = self._registry.route(language, NlpOp.LEMMATIZE)
        engine = self._registry.get_engine(route.engine)
        batched = getattr(engine, "batch_lemmatize", None) if engine else None

        if batched is not None and not route.is_floor:
            groups = batched(list(texts), language)
        else:
            groups = [
                [t.lemma or t.text for t in self.analyze(text, language, {NlpOp.LEMMATIZE}).tokens]
                for text in texts
            ]

        used = [
            EngineVersion(
                op=NlpOp.LEMMATIZE.value,
                engine=route.engine,
                model=route.model,
                model_version=route.model_version,
            )
        ]
        return BatchLemmatizeResult(results=groups, used=used)

    def _detect_language(self, text: str) -> EngineResult:
        langid_engine = self._registry.get_engine("langid")
        if not langid_engine:
            return EngineResult(error="Language detection engine not available")
        return langid_engine.analyze(text, "", {NlpOp.DETECT_LANGUAGE})


def _generate_trace_id() -> str:
    return secrets.token_hex(16)
