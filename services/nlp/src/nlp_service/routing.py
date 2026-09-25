# SPDX-License-Identifier: Apache-2.0
"""Route resolution result (RG-P1.S1.T3).

A `Route` is the fully-resolved decision for one (language, op): which engine
serves it, the explicit model id + version it will echo (S-1), the pinning
`tier`, and any diagnostics attached (RG-NLP-002 for a Lindat tier, RG-NLP-010
for a degrade-floor fallback). `route.model` is **never empty** — the floor
names its deterministic in-front producer.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Mapping, Optional, Tuple

from nlp_service.engines.base import NlpEngine, NlpOp

# The degrade floor's producer identity (S-1 holds even when no backend serves
# the op): deterministic in-front tokenize + S-2 fold + langid.
FLOOR_ENGINE = "floor"
FLOOR_MODEL = "tokenize+fold+langid"
FLOOR_MODEL_VERSION = "s2"


# ── Language tags (BCP-47) ───────────────────────────────────────────────────


def normalize_language(tag: str) -> str:
    """A BCP-47 tag reduced to the PRIMARY SUBTAG the routing table is keyed on.

    `op_routing` keys are `OP.<lang>` with a bare subtag (`LEMMATIZE.cs`), and an
    engine's `supported_languages()` is a set of bare subtags (`{"cs"}`). A caller
    that names a region — and a real one does: hartland's Shem declares
    `locale_defaults: [en-US, cs-CZ]`, and that locale reaches us verbatim — used
    to match NOTHING. Not an engine, not a routing key, not a fallback, not the
    last-resort capability scan. The request degraded to the floor with
    `RG-NLP-010 unsupported (language, op)` and returned zero tokens in **0 ms**,
    having never reached a backend, while the very same text under `en` parsed
    perfectly. "Unsupported" was true of the tag and false of the language.

    So: fold `en-US` / `en_US` / `EN` to `en` at every seam a caller's tag enters,
    and leave everything else alone. Deliberately NOT a full BCP-47 parse — we
    need the primary subtag and nothing else, and `langcodes` is a dependency the
    wheel does not carry.

    Three properties the callers rely on:

    - **Empty stays empty.** `""` means "not stated" and the orchestrator branches
      on it to decide whether to run DETECT_LANGUAGE. Mapping it to a default here
      would silently disable detection.
    - **Idempotent.** It is applied at more than one seam on purpose (the
      orchestrator's entry points AND `EngineRegistry.route`, which other callers
      reach directly), so applying it twice must equal applying it once.
    - **Region is dropped, not remembered.** Nothing downstream of routing varies
      by region today; if an engine ever serves `pt-BR` differently from `pt-PT`,
      that is a routing-key change, and this function is where it would be seen.
    """
    if not tag:
        return ""
    primary = tag.strip().replace("_", "-").partition("-")[0]
    return primary.lower()


@dataclass(frozen=True)
class Route:
    op: NlpOp
    language: str
    engine: str
    model: str
    model_version: str
    tier: str
    adapter: Optional[NlpEngine] = None
    is_floor: bool = False
    info: Tuple[str, ...] = field(default_factory=tuple)


# ── RV-P8.2: the routing config's rejection catalogue (RV-40) ────────────────


class RoutingConfigError(ValueError):
    """A routing table names something that cannot serve it.

    Raised where the registry is BUILT — at boot on both the REST and gRPC paths
    — rather than where a route is resolved. Validating inside `route()` would
    pass every test about the messages and still let a broken estate boot green,
    then answer requests from whatever engine the last-resort scan happened to
    find. A config that cannot serve must not load half-way.
    """


def validate_routing(
    *,
    routing: Mapping[str, str],
    engines: Mapping[str, NlpEngine],
    lane: str,
    declared: Mapping[str, bool],
    withheld: frozenset[str] | set[str],
    default_language: str,
) -> None:
    """Check every key of the ACTIVE LANE'S RESOLVED table, or raise.

    `declared` is every engine name this build knows about mapped to whether the
    config enabled it — which is what lets an unroutable target be diagnosed as
    the *right one of three mistakes*: a typo, an engine switched off, or an
    engine this lane does not carry. "Not registered" is true of all three and
    useful for none.

    The lane is why validation is per-lane rather than over the whole file: a
    `lane_overrides.option` block naming MorphoDiTa is not an error while running
    `default` — it is the other lane's business, and rejecting it would make the
    two lanes unable to live in one config, which is the entire point of the
    section.
    """
    for key, engine_name in sorted(routing.items()):
        op, language = _parse_key(key, default_language)
        if op is None:
            raise RoutingConfigError(
                f"routing key {key!r} does not name an NLP op — "
                f"expected `OP.lang`, `OP.lang.fallback` or `DETECT_LANGUAGE` "
                f"(ops: {', '.join(o.value for o in NlpOp)})"
            )

        # A regional tag in a KEY is dead on arrival: requests are folded to the
        # primary subtag before they are looked up here, so `LEMMATIZE.en-US`
        # could never be hit. It would otherwise fail below as "engine does not
        # serve LEMMATIZE for 'en-US'", which blames the engine for the key.
        if language and normalize_language(language) != language:
            raise RoutingConfigError(
                f"routing key {key!r} names the region-qualified language {language!r}. "
                f"Requests are normalized to their primary subtag before routing, so this "
                f"key can never match — write {op.value}.{normalize_language(language)} instead."
            )

        engine = engines.get(engine_name)
        if engine is None:
            raise RoutingConfigError(_why_absent(key, engine_name, engines, declared, withheld, lane))

        if not engine.supports(language, op):
            serves = sorted(
                f"{o.value}.{lang}"
                for lang in sorted(engine.supported_languages())
                for o in NlpOp
                if engine.supports(lang, o)
            )
            raise RoutingConfigError(
                f"routing key {key!r} sends {op.value} to {engine_name!r}, which does not "
                f"serve {op.value} for {language!r} — it serves: "
                f"{', '.join(serves) or '(nothing)'}"
            )


def _parse_key(key: str, default_language: str) -> tuple[Optional[NlpOp], str]:
    """`OP.lang[.fallback]` or the bare `DETECT_LANGUAGE`, as the registry reads it."""
    body = key[: -len(".fallback")] if key.endswith(".fallback") else key
    if body == NlpOp.DETECT_LANGUAGE.value:
        return NlpOp.DETECT_LANGUAGE, default_language
    op_name, _, language = body.partition(".")
    try:
        return NlpOp(op_name), language
    except ValueError:
        return None, language


def _why_absent(
    key: str,
    name: str,
    engines: Mapping[str, NlpEngine],
    declared: Mapping[str, bool],
    withheld: frozenset[str] | set[str],
    lane: str,
) -> str:
    registered = ", ".join(sorted(engines)) or "(none)"
    if name in withheld:
        return (
            f"routing key {key!r} names engine {name!r}, which the {lane!r} lane does not "
            f"carry — it exists only inside another lane's overlay. Registered here: {registered}"
        )
    if name in declared and not declared[name]:
        return (
            f"routing key {key!r} names engine {name!r}, which is DISABLED "
            f"(`engines.{name}.enabled: false`). Enable it or route this op elsewhere — "
            f"a config that cannot serve must not load half-way. Registered here: {registered}"
        )
    return (
        f"routing key {key!r} names engine {name!r}, which this build does not know. "
        f"Registered here: {registered}"
    )
