# SPDX-License-Identifier: Apache-2.0
"""Engine registry + routing (RG-P1.S1).

Builds the engine-free front's adapters from config and resolves each
(language, op) to a `Route` that names an engine + explicit model + version +
pinning tier (S-1). Unsupported (language, op) resolves to the degrade floor
(RG-NLP-010). Also builds the capability matrix that `GetStatus` returns (RS-7).

NLS-P3.2: routing is **lane-resolved** (NL-4). The lane supplies both the routing
overlay and the set of engines that exist at all — see
`AppConfig.lane_gated_engines` for why the second half is necessary rather than
tidy. Every lane decision is read from config here, so the capability matrix and
the per-request degrade agree by construction instead of by two lookups that
happen to match.
"""

from __future__ import annotations

from typing import Dict, List, Optional, Tuple

from nlp_service.config import (
    AppConfig,
    BackendConfig,
    LangidEngineConfig,
    load_config,
    validate_llm_emulated,
)
from nlp_service.diagnostics import RG_NLP_002, RG_NLP_003, RG_NLP_010, RV_NLP_022
from nlp_service.engines.base import NlpEngine, NlpOp
from nlp_service.engines.langid_engine import LangidEngine
from nlp_service.engines.llm_emulated_engine import (
    EMULATED_ENGINE_NAME,
    LlmEmulatedEngine,
)
from nlp_service.engines.morphodita_engine import MorphoditaEngine
from nlp_service.engines.nametag_engine import Nametag3Engine
from nlp_service.engines.spacy_engine import SpacyEngine
from nlp_service.engines.stanza_engine import StanzaEngine
from nlp_service.routing import (
    FLOOR_ENGINE,
    FLOOR_MODEL,
    FLOOR_MODEL_VERSION,
    Route,
    normalize_language,
    validate_routing,
)

# Engines that never carry a routable model op but participate in the floor.
_MODEL_BEARING = ("morphodita", "nametag3", "stanza", "spacy")


class EngineRegistry:
    """Registry of front-side engine adapters with per-op-per-language routing."""

    def __init__(self, config: AppConfig | None = None):
        self._config = config or load_config()
        self._engines: Dict[str, NlpEngine] = {}
        self._backends: Dict[str, BackendConfig] = {}
        # Lane-resolved (NL-4): base table, then the active lane's overlay.
        self._op_routing: Dict[str, str] = self._config.resolved_op_routing()
        self._withheld: set[str] = self._config.withheld_engines()
        self._register_engines()
        # RV-P8.2: the table is checked here because this is the first moment
        # BOTH halves exist — the resolved routing and the engines whose
        # `supports()` it must be checked against. The registry is built once at
        # boot on both paths (`create_app()` at import; `NlpServicer.__init__`),
        # so an estate whose routing cannot serve finds out then rather than one
        # request at a time.
        validate_routing(
            routing=self._op_routing,
            engines=self._engines,
            lane=self._config.lane,
            declared=self._declared_engines(),
            withheld=self._withheld,
            default_language=self._config.default_language,
        )

    @property
    def lane(self) -> str:
        return self._config.lane

    # ---- construction -----------------------------------------------------

    def _register_engines(self) -> None:
        e = self._config.engines
        for name, backend, factory in (
            ("morphodita", e.morphodita, MorphoditaEngine),
            ("nametag3", e.nametag3, Nametag3Engine),
            ("stanza", e.stanza, StanzaEngine),
            ("spacy", e.spacy, SpacyEngine),
        ):
            # `enabled: false` says the backend is not deployed; the lane says it
            # is not in THIS deployment's stack. Both mean "not registered", and
            # not registering is what makes an op genuinely unrouted rather than
            # merely unpreferred — routing's last-resort scan only sees what is
            # here.
            if backend.enabled and name not in self._withheld:
                self._engines[name] = factory(backend)
                self._backends[name] = backend
        if e.langid.enabled and "langid" not in self._withheld:
            self._engines["langid"] = LangidEngine(e.langid)
        # RV-P8.1: `LLM_EMULATED` registers on exactly the same terms as the
        # four above — `enabled` (off by default, RV-6) and the lane. Nothing
        # about it is special-cased here, which is the point: an estate routes
        # to it, withholds it or degrades without it using the machinery that
        # was already there.
        if e.llm_emulated.enabled and EMULATED_ENGINE_NAME not in self._withheld:
            # Checked here rather than at parse time because the enable switch is
            # an env var: the config object is mutated after construction, and
            # this is the first moment the *effective* one is known. Checked only
            # when it will actually register, for the reason `validate_routing`
            # is per-lane — another lane's engine config is that lane's business.
            validate_llm_emulated(e.llm_emulated)
            self._engines[EMULATED_ENGINE_NAME] = LlmEmulatedEngine(e.llm_emulated)

    def withheld_engines(self) -> set[str]:
        """Engines this lane does not admit — reported, not just applied."""
        return set(self._withheld)

    def _declared_engines(self) -> Dict[str, bool]:
        """Every engine name this build knows, mapped to whether config enabled it.

        The registry only holds what it registered, so on its own it cannot tell
        a typo from a switched-off engine — and those have different fixes.
        """
        e = self._config.engines
        return {
            "morphodita": e.morphodita.enabled,
            "nametag3": e.nametag3.enabled,
            "stanza": e.stanza.enabled,
            "spacy": e.spacy.enabled,
            "langid": e.langid.enabled,
            EMULATED_ENGINE_NAME: e.llm_emulated.enabled,
        }

    # ---- lookup -----------------------------------------------------------

    def get_engine(self, name: str) -> Optional[NlpEngine]:
        return self._engines.get(name)

    def list_engines(self) -> List[str]:
        return list(self._engines.keys())

    def _engine_model(self, name: str) -> Tuple[str, str, str]:
        """(model, model_version, tier) for an engine, from its config."""
        if name == "langid":
            cfg: LangidEngineConfig = self._config.engines.langid
            return cfg.model, cfg.model_version, "SELF_HOSTED_PINNED"
        if name == EMULATED_ENGINE_NAME:
            # The version is DERIVED (model + prompt-template revision), not
            # authored — see the engine. And the tier is `REMOTE_UNPINNED`
            # because that is what it is: a provider can change what a model
            # name serves, which is precisely the property that tier already
            # names. It costs nothing and buys the `RG-NLP-002` caution
            # ("non-conformant for parity/determinism") on every emulated route,
            # for every caller, without inventing a second way to say it.
            emulated = self._config.engines.llm_emulated
            engine = self._engines.get(name)
            version = engine.model_version if engine is not None else emulated.model
            return emulated.model, version, "REMOTE_UNPINNED"
        backend = self._backends.get(name)
        if backend is None:
            return "", "", "SELF_HOSTED_PINNED"
        return backend.model, backend.model_version, backend.tier

    # ---- routing ----------------------------------------------------------

    def _resolve_engine(self, op: NlpOp, lang: str, engine_hint: str | None) -> Optional[str]:
        """The engine *name* for (op, lang), honoring hint → config → capability."""
        if engine_hint and engine_hint in self._engines:
            if self._engines[engine_hint].supports(lang, op):
                return engine_hint

        if op == NlpOp.DETECT_LANGUAGE:
            key = "DETECT_LANGUAGE"
        else:
            key = f"{op.value}.{lang}"
        name = self._op_routing.get(key)
        if name and name in self._engines and self._engines[name].supports(lang, op):
            return name

        # Configured fallback (e.g. NER.en.fallback: spacy).
        fb = self._op_routing.get(f"{key}.fallback")
        if fb and fb in self._engines and self._engines[fb].supports(lang, op):
            return fb

        # Last resort: any engine that supports it.
        for cand, engine in self._engines.items():
            if engine.supports(lang, op):
                return cand
        return None

    def route(self, language: str, op: NlpOp, engine_hint: str | None = None) -> Route:
        """Resolve (language, op) → a fully-described `Route` (never None).

        Falls back to the degrade floor (RG-NLP-010) when no engine serves the
        (language, op). Attaches RG-NLP-002 for a REMOTE_UNPINNED tier and
        RG-NLP-003 when a resolved model-bearing engine has an empty model id.

        `language` is normalized to its primary subtag first, and the resolved
        `Route` carries the NORMALIZED tag: a route describes what actually
        served, and `en` is what served a request that said `en-US`. Normalizing
        here as well as at the orchestrator's entry points is deliberate — the
        pipeline runner and the capability matrix call this directly — and
        `normalize_language` is idempotent so the double application is free.
        """
        language = normalize_language(language)
        name = self._resolve_engine(op, language, engine_hint)
        if name is None:
            return Route(
                op=op, language=language, engine=FLOOR_ENGINE,
                model=FLOOR_MODEL, model_version=FLOOR_MODEL_VERSION,
                tier="SELF_HOSTED_PINNED", adapter=None, is_floor=True,
                info=(RG_NLP_010,),
            )

        model, version, tier = self._engine_model(name)
        info: list[str] = []
        if tier == "REMOTE_UNPINNED":
            info.append(RG_NLP_002)
        if name == EMULATED_ENGINE_NAME:
            # Beside RG-NLP-002, not instead of it: that code says the route is
            # unpinned, this one says what did the serving. Until RV-P8 there was
            # only one unpinned thing (Lindat) and the distinction did not exist.
            info.append(RV_NLP_022)
        if name in _MODEL_BEARING and not model:
            info.append(RG_NLP_003)
        return Route(
            op=op, language=language, engine=name,
            model=model, model_version=version, tier=tier,
            adapter=self._engines.get(name), is_floor=False, info=tuple(info),
        )

    # ---- capability matrix (RS-7) ----------------------------------------

    def capability_matrix(self) -> List[dict]:
        """One row per (language, op), built from config + capabilities (not
        hardcoded). Rows: {language, op, engine, model_version, tier, is_floor,
        info}.

        This is the FULL internal view and keeps the rows for ops nothing can
        serve, flagged `is_floor` with `RG-NLP-010` — an operator reading logs
        wants the gap named, not omitted. `served_capabilities()` is the subset
        that goes on the wire.
        """
        rows: List[dict] = []
        for lang in sorted(self._configured_languages()):
            for op in NlpOp:
                route = self.route(lang, op)
                rows.append(
                    {
                        "language": lang,
                        "op": op,
                        "engine": route.engine,
                        "model_version": route.model_version,
                        "tier": route.tier,
                        "is_floor": route.is_floor,
                        "info": route.info,
                    }
                )
        return rows

    def served_capabilities(self) -> List[dict]:
        """The matrix rows `GetStatus` reports — contracts §2.4.

        An op that nothing in the active lane can actually produce gets **no
        row**. `NER.cs` in the default lane is the case the design turns on: its
        absence here, plus `NLS-NLP-011` on the response, IS the NL-14 degrade. A
        row naming the degrade floor would say the same thing less clearly, and a
        consumer reading the matrix to decide whether to ask for cs NER would read
        it as a yes.

        The rows are the same lane-resolved routes every request uses, so the row
        disappears because the route did — there is no lane logic here, only the
        wire's own rule about what counts as a capability.
        """
        return [
            row
            for row in self.capability_matrix()
            if not row["is_floor"] or self.floor_serves(row["language"], row["op"])
        ]

    @staticmethod
    def floor_serves(lang: str, op: NlpOp) -> bool:
        """Whether the degrade floor can actually produce this op.

        The floor tokenizes and splits sentences deterministically, in the front,
        for any language — those routes are real capabilities and keep their row.
        It cannot lemmatize, tag, parse or recognise entities, and a row for one
        of those would advertise a capability nobody has.
        """
        from nlp_service.floor import FloorEngine

        return FloorEngine().supports(lang, op)

    def _configured_languages(self) -> set[str]:
        langs: set[str] = set()
        for key in self._op_routing:
            # keys are "OP.lang" or "OP.lang.fallback" or bare "DETECT_LANGUAGE"
            parts = key.split(".")
            if len(parts) >= 2 and parts[1] not in ("fallback",):
                langs.add(parts[1])
        langs.discard("")
        if not langs:
            langs.add(self._config.default_language)
        return langs

    def get_all_engines_for_op(self, op: NlpOp, lang: str) -> List[NlpEngine]:
        """All engines that support (op, lang) — used by COMPARE mode (debug)."""
        return [e for e in self._engines.values() if e.supports(lang, op)]

    def has_engine_for_language(self, lang: str) -> bool:
        return any(engine.supports(lang, NlpOp.TOKENIZE) for engine in self._engines.values())

    def is_ready(self) -> bool:
        return self.has_engine_for_language(self._config.default_language)
