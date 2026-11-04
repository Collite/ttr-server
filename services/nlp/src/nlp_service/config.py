# SPDX-License-Identifier: Apache-2.0
"""nlp front configuration.

RG-P1.S1: the front is **engine-free** — every model-bearing engine is an
HTTP-adapter client to a separate backend image (`url`), launched with an
**explicit model id** (`model`, S-1). Only `langid` (lingua) runs in-process.
Each backend declares its pinning `tier`: `SELF_HOSTED_PINNED` (in-cluster,
conformant) or `REMOTE_UNPINNED` (Lindat dev/eval — `RG-NLP-002`).

NLS-P3.2 adds the **lane** switch (NL-4) and the pack/list/pipeline sections
(contracts §7).

**What a lane is.** `default` is the lane a deployment can run anywhere: Stanza
and spaCy, permissively licensed, no UFAL images. `option` adds the UFAL stack
(MorphoDiTa, NameTag 3), whose licence is a per-deployment decision (NL-5). So
the lane is not a preference between engines that are all present — it decides
*which engines are there at all*, and that is why `base op_routing` IS the
default lane rather than a lane-neutral table with the lane picking favourites.

**`NER.cs` is the case the whole design turns on.** Stanza's Czech bundle has no
NER head, so in the default lane nothing can serve it. It is left out of the base
table on purpose, the request still runs every other phase, and the response
carries `NLS-NLP-011` saying so (NL-14: honest degrade, never a silent one).
"""

from __future__ import annotations

import json
import logging
import os
import re
from pathlib import Path
from typing import Dict, List, Literal, Optional

import yaml
from pydantic import BaseModel, ConfigDict, Field, ValidationError

logger = logging.getLogger(__name__)


class ServiceConfig(BaseModel):
    host: str = Field(default="0.0.0.0", validation_alias="NLP_SERVICE_HOST")
    port: int = Field(default=7270, validation_alias="NLP_SERVICE_PORT")
    grpc_port: int = Field(default=7271, validation_alias="NLP_SERVICE_GRPC_PORT")


class BackendConfig(BaseModel):
    """A model-bearing engine served by its own backend image (or Lindat).

    `model` is the explicit model id sent to the backend and echoed on every
    response (S-1) — never empty for an enabled model-bearing engine.
    """

    enabled: bool = True
    url: str = ""              # backend base URL (in-cluster) or Lindat endpoint
    model: str = ""            # explicit model id (S-1)
    model_version: str = ""    # backend-reported version / handle
    tier: str = "SELF_HOSTED_PINNED"  # or REMOTE_UNPINNED (Lindat dev/eval)
    timeout_seconds: int = 30
    max_retries: int = 3
    # >0 only for the remote (Lindat) dev tier; self-hosted backends are unthrottled.
    rate_limit_per_minute: int = 0


class LangidEngineConfig(BaseModel):
    """The one engine that stays in the front — lingua, tiny, no model files."""

    enabled: bool = True
    model: str = "lingua"
    model_version: str = "lingua-2.0"


class LlmEmulatedConfig(BaseModel):
    """`LLM_EMULATED` — a language model standing in for an engine (RV-6).

    **`enabled: False` is the contract, not a default anyone should flip
    casually.** An estate turning this on trades a pinned model for a hosted one:
    the analysis is no longer reproducible across providers, and the conformance
    suite excludes the ops it touches (RV-P8.3). Off is what every deployment
    that has a real engine wants, which is all of them but the licence-blocked
    ones this exists for.

    `model_version` is not authored here — it is derived from the model AND the
    prompt-template version, because two deployments running the same model on
    different templates are not the same engine (S-1).
    """

    enabled: bool = False
    url: str = ""            # llm-gateway base URL (this repo's LG 2.0 service)
    model: str = ""          # the model class the emulation runs on (S-1)
    api_key: str = ""        # `ttrk-` virtual key; the gateway validates it itself
    timeout_seconds: int = 15  # ⚑RV-3's emulated-rung budget
    max_retries: int = 2
    #: The ops this engine is allowed to emulate. LEMMATIZE/POS_TAG/NER is the
    #: v1 set (⚑ p8-1 T1); anything else is rejected at construction rather than
    #: quietly ignored.
    ops: List[str] = Field(default_factory=lambda: ["LEMMATIZE", "POS_TAG", "NER"])
    languages: List[str] = Field(default_factory=lambda: ["cs", "en"])
    #: Deployment determinism is a cache, and an unbounded one in a long-running
    #: front is a leak. LRU.
    cache_max_entries: int = 1024


class EnginesConfig(BaseModel):
    morphodita: BackendConfig = Field(default_factory=BackendConfig)
    nametag3: BackendConfig = Field(default_factory=BackendConfig)
    stanza: BackendConfig = Field(default_factory=BackendConfig)
    spacy: BackendConfig = Field(default_factory=BackendConfig)
    langid: LangidEngineConfig = Field(default_factory=LangidEngineConfig)
    llm_emulated: LlmEmulatedConfig = Field(default_factory=LlmEmulatedConfig)


LANE_DEFAULT = "default"
LANE_OPTION = "option"


class SourcesConfig(BaseModel):
    """Where packs or lists come from (NL-15). Dirs and/or `http(s)` URLs."""

    model_config = ConfigDict(extra="forbid")

    sources: List[str] = Field(default_factory=list)


class RuleRef(BaseModel):
    """One `(pack, phase)` step of a pipeline's rule stage."""

    model_config = ConfigDict(extra="forbid")

    pack: str
    phase: str


#: The two in-process steps a `morph: true` pipeline runs instead of calling a
#: backend (LM contracts §5). They are step NAMES, not `NlpOp`s, and that is the
#: point: an `NlpOp` is a thing routing resolves to an engine, and these resolve
#: to a function call inside this process. Putting them in the enum would make
#: them routable, and there is nothing to route them to.
STEP_MORPH_TOKENIZE = "MORPH_TOKENIZE"
STEP_MORPH_ANNOTATE = "MORPH_ANNOTATE"

#: Engine ops the morph front actually replaces. Deliberately just these two.
#:
#: `SENTENCE_SPLIT` is **not** here — the morph tokenizer does not split
#: sentences and says so in its own docstring (the rule engine matches within
#: the document, and a second, worse splitter is a second opinion nobody asked
#: for). Neither is `POS_TAG`: the annotator writes `analyses` carrying `upos`
#: per reading, but it does not write the flat `upos` feature a pack matches on
#: with `upos: NOUN`, so claiming coverage would silently drop a feature rules
#: are written against. `DEP_PARSE` and `NER` are not morphology at all.
MORPH_COVERED_OPS = frozenset({"TOKENIZE", "LEMMATIZE"})


class PipelineConfig(BaseModel):
    """A named pipeline (contracts §7): engine ops, then gazetteer lists, then
    rule phases — in that order, and in the order written.

    `morph: true` (LM, NLS-P9.1) swaps the front of that for the in-process
    Czech morphology: our own tokenizer and the snapshot annotator, replacing
    the ops in `MORPH_COVERED_OPS`. It is a per-pipeline switch rather than a
    global one because a deployment serves more than one pipeline and only the
    Czech ones have a snapshot to run against — and it degrades to the engine
    path, silently and correctly, for any language the snapshot is not in.
    """

    model_config = ConfigDict(extra="forbid")

    ops: List[str] = Field(default_factory=list)
    gazetteer: List[str] = Field(default_factory=list)
    rules: List[RuleRef] = Field(default_factory=list)
    morph: bool = False

    def engine_ops(self) -> List[str]:
        """The ops that still go to a backend when the morph front runs."""
        return [op for op in self.ops if op not in MORPH_COVERED_OPS]

    def steps(self, *, morph: bool | None = None) -> List[str]:
        """The step names `GetStatus` reports (contracts §2.4 `PipelineInfo`).

        `morph` overrides the pipeline's own flag so a caller can ask what the
        steps would be either way; `GetStatus` passes nothing and gets the
        configured answer.
        """
        use_morph = self.morph if morph is None else morph
        ops = (
            [STEP_MORPH_TOKENIZE, STEP_MORPH_ANNOTATE, *self.engine_ops()]
            if use_morph
            else list(self.ops)
        )
        return [
            *ops,
            *self.gazetteer,
            *(f"{ref.pack}:{ref.phase}" for ref in self.rules),
        ]


#: A world id has to be safe to use as a filename: the `dir:` sink spools one
#: JSONL per world, because retention is per-world config and a single mixed
#: file cannot be swept per policy. Worlds are authored in config, never taken
#: from a request — but `ReportToken.world` IS taken from a request and is
#: matched against these keys, so a `../../etc/cron.d/x` in the table would turn
#: a config typo into a path traversal with a helpful error message.
_WORLD_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")

SINK_NONE = "none"
SINK_DIR_PREFIX = "dir:"
SINK_URL_PREFIX = "url:"


class MorphQueueConfig(BaseModel):
    """Where token reports go (LM contracts §6).

    `none` — the sink is off and `ReportToken` answers `accepted=false` with
    `LM-MORPH-007`, which is the honest answer rather than an error.
    `dir:<path>` — a JSONL spool per world, deduped on (world, token).
    `url:<addr>` — POSTed to morph-studio's `/ingest`, spooling to
    `spool_dir` whenever the POST cannot be delivered. Never lose a miss.
    """

    model_config = ConfigDict(extra="forbid")

    sink: str = SINK_NONE
    #: Fallback spool for a `url:` sink. Empty means the reports live in memory
    #: until a POST succeeds — fine for a dev box, wrong for a cluster, so a
    #: `url:` sink without one is a WARNING at boot rather than silence.
    spool_dir: str = ""
    #: How often the retention sweep runs (contracts §6: retention is world
    #: config). Also runs once at boot, which is what catches a service that was
    #: down for longer than the retention window.
    sweep_interval_seconds: int = 24 * 60 * 60


class MorphWorldConfig(BaseModel):
    """One world's queue policy (LM contracts §6, S-4).

    `spans: false` is the default and the whole of the S-4 posture: a
    lemmatizer queue that collected the surrounding text by default would be
    collecting user data by default. Turning it on is a world's own decision,
    recorded in that world's config, and the front DROPS the span before it is
    ever written when the answer is no.
    """

    model_config = ConfigDict(extra="forbid")

    spans: bool = False
    retention_days: int = 90


class MorphConfig(BaseModel):
    """The morph snapshot and its queue (LM contracts §5–§6, NLS contracts §7).

    `sources` follows the pack loader's fail-all posture (NL-15): the core
    snapshot first, then its separable member files, then world overlays — and
    one unreadable source loads nothing at all, reported as `LM-MORPH-001` on
    `GetStatus.morph_state` rather than crashing the front. `Analyze` and
    `BatchLemmatize` need no snapshot and keep serving either way.
    """

    model_config = ConfigDict(extra="forbid")

    sources: List[str] = Field(default_factory=list)
    #: THIS deployment's world — the one a pipeline's misses belong to.
    #:
    #: It is deployment config and not a request field because `RunPipeline` has
    #: no `world` on the wire and adding one would be a second proto window
    #: (⚑LMP-D3) to carry something a front already knows: each world mounts its
    #: own packs (NL-17), so a front IS a world's front. `ReportToken` does name
    #: a world, because its caller is a consumer that saw a wrong answer and may
    #: not be the same deployment.
    world: str = ""
    queue: MorphQueueConfig = Field(default_factory=MorphQueueConfig)
    worlds: Dict[str, MorphWorldConfig] = Field(default_factory=dict)

    def world_config(self, world: str) -> Optional[MorphWorldConfig]:
        return self.worlds.get(world)


class AppConfig(BaseModel):
    service: ServiceConfig = Field(default_factory=ServiceConfig)
    engines: EnginesConfig = Field(default_factory=EnginesConfig)
    # Base routing IS the default-lane routing — see the module docstring.
    op_routing: Dict[str, str] = Field(default_factory=dict)
    lane: Literal["default", "option"] = LANE_DEFAULT
    #: lane name -> routing overlay, applied over `op_routing` when active.
    lane_overrides: Dict[str, Dict[str, str]] = Field(default_factory=dict)
    packs: SourcesConfig = Field(default_factory=SourcesConfig)
    lists: SourcesConfig = Field(default_factory=SourcesConfig)
    morph: MorphConfig = Field(default_factory=MorphConfig)
    pipelines: Dict[str, PipelineConfig] = Field(default_factory=dict)
    default_language: str = "cs"
    log_level: str = "INFO"

    # `extra` is left permissive on THIS model, deliberately. The new nested
    # sections above forbid it — their whole shape is ours, and a typo'd
    # `gazeteer:` that silently loaded no lists is the exact failure NL-15 exists
    # to prevent. Tightening the top level too would mean any deployed config
    # carrying a key this build does not know about stops the service from
    # booting, and there are configmaps out there this checkout cannot see.

    def resolved_op_routing(self) -> Dict[str, str]:
        """The routing table for the ACTIVE lane: base, then its overlay.

        The active lane's overlay applies whatever the lane is called, `default`
        included. Skipping it for `default` looked harmless — no shipped config
        writes a `lane_overrides.default` block, and base routing IS the default
        lane (module docstring) — but it disagreed with `withheld_engines`, which
        admits the active lane's overlay engines unconditionally. One that did
        write that block therefore got the engines *registered* and their routing
        *ignored*, leaving them reachable only through routing's last-resort "any
        engine that supports this op" scan: availability gated on one half of the
        mechanism, routing on the other, which is precisely the silent fallback
        `lane_gated_engines` exists to prevent.

        With no `default` key — every config in the tree — this is `dict(op_routing)`,
        exactly as before.
        """
        routing = dict(self.op_routing)
        routing.update(self.lane_overrides.get(self.lane, {}))
        return routing

    def lane_gated_engines(self) -> set[str]:
        """Engines that exist only inside a lane overlay.

        These are the ones the default lane does not have: an engine named
        *exclusively* by an overlay is, by construction, part of that lane's
        stack. Engines the base table names are always available, so a config
        with no `lane_overrides` at all behaves exactly as it did before this
        section existed — which is why every pre-NLS config and test is
        unaffected.

        This matters more than it looks. Routing has a last-resort scan ("any
        engine that supports this op"), so simply *omitting* `NER.cs` from the
        base table would not make it unrouted — the scan would find NameTag 3
        anyway and the NL-14 degrade would never happen. Gating availability is
        what makes "unrouted" real.
        """
        overlaid: set[str] = set()
        for overlay in self.lane_overrides.values():
            overlaid.update(overlay.values())
        return overlaid - set(self.op_routing.values())

    def withheld_engines(self) -> set[str]:
        """Engines the ACTIVE lane does not admit. Empty means "all of them".

        A gated engine is admitted when the active lane's own overlay names it —
        so with two overlays (`option`, say, and something experimental), running
        `option` admits option's engines and still withholds the other's.
        """
        gated = self.lane_gated_engines()
        if not gated:
            return set()
        admitted = set(self.lane_overrides.get(self.lane, {}).values())
        return gated - admitted


class EngineConfigError(ValueError):
    """An engine is switched on with a configuration that cannot serve.

    The sibling of `RoutingConfigError`, and raised at the same moment for the
    same reason: a config that cannot serve must not load half-way. Routing is
    only half the question — an engine can be routed correctly, register, pass
    `validate_routing`, and still be unable to answer because it has no address.
    """


def validate_llm_emulated(config: LlmEmulatedConfig) -> None:
    """An ENABLED emulated engine must be able to reach a model, or refuse to boot.

    Helm's `required` covers the chart path and nothing else, so
    `NLP_LLM_EMULATED_ENABLED=true` from compose, a dev shell or a bare env left
    `url` empty, the engine registered, `supports()` answered true (the templates
    are on disk, which is all it reads), routing validated — and every routed
    request then spent its transport failures and its backoff discovering what the
    config already knew. That is precisely the "boots green, cannot serve" state
    RV-P8.2 made a boot error everywhere else.

    `api_key` is a warning, not an error, and the asymmetry is deliberate: a
    gateway with no address cannot be called at all, whereas a keyless one is an
    ordinary local setup. Failing on it would make a working dev deployment
    unbootable to guard against a 401 the gateway itself reports.
    """
    if not config.enabled:
        return
    missing = [
        name
        for name, value in (("url", config.url), ("model", config.model))
        if not value.strip()
    ]
    if missing:
        env = ", ".join(f"NLP_LLM_EMULATED_{name.upper()}" for name in missing)
        raise EngineConfigError(
            f"`engines.llm_emulated` is ENABLED but has no {' and no '.join(missing)} — "
            f"set {env} (or the matching `engines.llm_emulated` keys), or turn the "
            "engine off. An engine that cannot reach a model must not boot green."
        )
    if not config.api_key.strip():
        logger.warning(
            "engines.llm_emulated is enabled with no api_key — the gateway will "
            "reject every call unless it is deployed without key validation"
        )


class MorphConfigError(ValueError):
    """The morph block cannot serve as written. Boot refuses.

    The sibling of `EngineConfigError`, and the line between this and a
    `GetStatus` diagnostic is the line between a *typo* and a *mount*. A
    pipeline that says `morph: true` while nothing declares a snapshot source
    can never run, on any cluster, and no amount of waiting fixes it — so it is
    a boot error. A source that is declared and unreadable is nearly always a
    volume that did not mount: the front comes up, says `LM-MORPH-001` on
    `GetStatus.morph_state`, keeps serving `Analyze`, and starts working the
    moment the volume appears (`morph_state.py`).
    """


def validate_morph(config: AppConfig) -> None:
    """Check the morph block at boot. Raises `MorphConfigError`."""
    morph = config.morph

    wants_morph = sorted(
        name for name, spec in config.pipelines.items() if spec.morph
    )
    if wants_morph and not morph.sources:
        raise MorphConfigError(
            f"pipeline(s) {', '.join(wants_morph)} declare `morph: true` but "
            "`morph.sources` is empty — there is no snapshot for them to run "
            "against, on any cluster. Point `morph.sources` at a "
            "`cs.morph.snap` (plus any world overlay), or drop the flag."
        )

    for world in sorted(morph.worlds):
        if not _WORLD_ID.match(world):
            raise MorphConfigError(
                f"morph.worlds key {world!r} is not a usable world id — the "
                "`dir:` sink spools one file per world, so an id has to be "
                "safe as a filename (letters, digits, '.', '_', '-')"
            )

    if morph.world and morph.world not in morph.worlds:
        known = ", ".join(sorted(morph.worlds)) or "none declared"
        raise MorphConfigError(
            f"morph.world is {morph.world!r} but `morph.worlds` has no block "
            f"for it (declared: {known}). Retention and the span opt-in are "
            "world config (LM contracts §6); a world with none would spool "
            "reports no policy ever sweeps."
        )

    sink = morph.queue.sink.strip()

    if wants_morph and not morph.world and sink and sink != SINK_NONE:
        # ⚑ The unset world, which the check above cannot see: it asks whether
        # a *named* world was declared, and `""` names nothing. Left to boot,
        # every leg of the enrichment loop is wired and the loop is dead —
        # `SpoolSink.report("")` finds no policy, returns False, and `miss_sink`
        # discards it, so a cluster that set `NLP_MORPH_SOURCES` and the sink
        # but forgot `NLP_MORPH_WORLD` comes up green, answers queries, and
        # silently learns nothing. That is the failure this whole block exists
        # to make impossible, and it was the one shape that got through.
        known = ", ".join(sorted(morph.worlds)) or "none declared"
        raise MorphConfigError(
            f"pipeline(s) {', '.join(wants_morph)} declare `morph: true` and "
            f"`morph.queue.sink` is {sink!r}, but `morph.world` is empty "
            f"(declared worlds: {known}). Queues are world-scoped (LM-5/S-4), "
            "so a report with no world belongs to nobody and is dropped — the "
            "enrichment loop would be fully wired and collect nothing. Set "
            "`morph.world` (NLP_MORPH_WORLD) to one of the declared worlds, or "
            f"set the sink to {SINK_NONE!r} if this front is not meant to feed "
            "a studio."
        )

    if sink and sink != SINK_NONE:
        if sink.startswith(SINK_DIR_PREFIX):
            if not sink[len(SINK_DIR_PREFIX) :].strip():
                raise MorphConfigError("morph.queue.sink `dir:` has no path")
        elif sink.startswith(SINK_URL_PREFIX):
            if not sink[len(SINK_URL_PREFIX) :].strip():
                raise MorphConfigError("morph.queue.sink `url:` has no address")
            if not morph.queue.spool_dir.strip():
                # Not fatal: a dev box with no volume is a legitimate setup, and
                # the sink still holds reports in memory and retries. On a
                # cluster it means a morph-studio outage costs every miss
                # collected during it, which is worth saying out loud.
                logger.warning(
                    "morph.queue.sink is %r with no `spool_dir` — reports that "
                    "cannot be POSTed are held in memory only and are lost on "
                    "restart",
                    sink,
                )
        else:
            raise MorphConfigError(
                f"morph.queue.sink {sink!r} is not one of `none`, "
                "`dir:<path>`, `url:<address>`"
            )


# Lindat dev/eval endpoints (the REMOTE_UNPINNED tier — RG-NLP-002). Selected by
# NLP_UFAL_ENDPOINT_MODE=lindat; the pinned model ids stay explicit (S-1).
_LINDAT_MORPHODITA = "https://lindat.mff.cuni.cz/services/morphodita/api/tag"
_LINDAT_NAMETAG = "https://lindat.mff.cuni.cz/services/nametag/api/recognize"
_LINDAT_RATE_LIMIT = 5


def apply_env_overrides(config: AppConfig) -> AppConfig:
    """Apply the S2.T4 endpoint repoint from the environment (config-only swap).

    - `NLP_UFAL_ENDPOINT_MODE=lindat` flips MorphoDiTa + NameTag 3 to Lindat as a
      `REMOTE_UNPINNED` dev/eval tier (endpoint + tier + rate-limit change; the
      model id stays pinned — S-1).
    - `NLP_MORPHODITA_URL` / `NLP_NAMETAG3_URL` override just the endpoint.
    - `NLP_LANE=default|option` selects the engine stack (NL-4, NLS-P3.2). An env
      var rather than config-only because the lane is a *deployment* decision —
      the helm chart sets it per environment, and the alternative is a per-cluster
      copy of `config.yaml` differing in one line.
    """
    if lane := os.getenv("NLP_LANE"):
        lane = lane.strip().lower()
        if lane in (LANE_DEFAULT, LANE_OPTION):
            config.lane = lane  # type: ignore[assignment]
        else:
            # Not fatal, and deliberately so: a typo'd lane must not take the
            # front down, and `default` is the lane that needs no licence
            # decision — the safe one to be wrong towards.
            logger.warning(
                "NLP_LANE=%r is not a known lane — keeping %r", lane, config.lane
            )

    if os.getenv("NLP_UFAL_ENDPOINT_MODE", "self_hosted").lower() == "lindat":
        m = config.engines.morphodita
        m.url = _LINDAT_MORPHODITA
        m.tier = "REMOTE_UNPINNED"
        m.rate_limit_per_minute = _LINDAT_RATE_LIMIT
        n = config.engines.nametag3
        n.url = _LINDAT_NAMETAG
        n.tier = "REMOTE_UNPINNED"
        n.rate_limit_per_minute = _LINDAT_RATE_LIMIT

    if url := os.getenv("NLP_MORPHODITA_URL"):
        config.engines.morphodita.url = url
    if url := os.getenv("NLP_NAMETAG3_URL"):
        config.engines.nametag3.url = url

    # RV-P8.1 — `LLM_EMULATED`. The enable switch is an env var for the same
    # reason the lane is: it is a deployment decision (and, unlike the others,
    # one with a licence and a cost attached), and the alternative is a
    # per-cluster copy of config.yaml differing in one line. The key never goes
    # in config.yaml at all — it comes from a secret, like every other credential.
    emulated = config.engines.llm_emulated
    if flag := os.getenv("NLP_LLM_EMULATED_ENABLED"):
        emulated.enabled = flag.strip().lower() in ("1", "true", "yes", "on")
    if url := os.getenv("NLP_LLM_EMULATED_URL"):
        emulated.url = url
    if model := os.getenv("NLP_LLM_EMULATED_MODEL"):
        emulated.model = model
    if key := os.getenv("NLP_LLM_EMULATED_API_KEY"):
        emulated.api_key = key

    # LM (NLS-P9.1). Deployment decisions, exactly like the lane: the snapshot
    # is a mounted volume whose path differs per cluster, the world IS the
    # deployment, and the sink points at that estate's morph-studio. The
    # alternative is a per-cluster copy of config.yaml differing in three lines.
    if sources := os.getenv("NLP_MORPH_SOURCES"):
        config.morph.sources = [s.strip() for s in sources.split(",") if s.strip()]
    if world := os.getenv("NLP_MORPH_WORLD"):
        config.morph.world = world.strip()
    if sink := os.getenv("NLP_MORPH_QUEUE_SINK"):
        config.morph.queue.sink = sink.strip()
    if spool := os.getenv("NLP_MORPH_QUEUE_SPOOL_DIR"):
        config.morph.queue.spool_dir = spool.strip()

    # DX-P3 T8f. A world's pipelines are a deployment decision too: their ids name the
    # packs and lists that deployment mounts (`packs.configMapName`, `lists.configMapName`),
    # which is why config.yaml ships `pipelines: {}`. A JSON object of name → pipeline,
    # merged over config.yaml's by name. A value that does not parse, or names a field a
    # pipeline does not have, is logged and dropped whole, not fatal, like a typo'd lane:
    # the pipelines stay config.yaml's, and RunPipeline names the one it does not know.
    # A reference to a list or pack that is not loaded is the pack loader's to report
    # (NLS-PACK-004, at boot and on every reload), as for config.yaml's own pipelines.
    if raw := os.getenv("NLP_PIPELINES"):
        try:
            extra = json.loads(raw)
            if not isinstance(extra, dict):
                raise ValueError("not a JSON object")
            pipelines = {str(name): PipelineConfig.model_validate(spec) for name, spec in extra.items()}
        except (ValueError, ValidationError) as exc:
            logger.error("NLP_PIPELINES ignored, config.yaml's pipelines kept: %s", exc)
        else:
            config.pipelines = {**config.pipelines, **pipelines}

    return config


def load_config(config_path: Optional[str] = None) -> AppConfig:
    """Load configuration from YAML with optional `CONFIG_FILE` override, then
    apply the environment endpoint overrides (S2.T4)."""
    env_cfg = os.getenv("CONFIG_FILE")
    if config_path:
        cfg_path = Path(config_path)
    elif env_cfg:
        cfg_path = Path(env_cfg)
    else:
        # config.yaml lives at the SERVICE ROOT (services/nlp/config.yaml),
        # three levels up from this file (src/nlp_service/config.py) — NOT src/.
        # (The container sets CONFIG_FILE explicitly; this default matters for
        # local/non-container boots, where a wrong path silently dropped the
        # pinned models to empty AppConfig() defaults.)
        cfg_path = Path(__file__).resolve().parent.parent.parent / "config.yaml"

    data = _read_yaml_config(cfg_path)
    config = AppConfig(**data) if data else AppConfig()
    return apply_env_overrides(config)


def _read_yaml_config(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        content = path.read_text(encoding="utf-8")
    except Exception:
        return {}
    try:
        data = yaml.safe_load(content) or {}
        if not isinstance(data, dict):
            return {}
        return data
    except Exception:
        return {}
