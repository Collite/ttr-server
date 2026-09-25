# SPDX-License-Identifier: Apache-2.0
"""RV-P8.2 T1 — a routing config that cannot serve must not load half-way.

RV-40 made per-op backend binding contractual, and the table it binds is NLS
contracts §7's — `op_routing` plus the lane overlay, one schema for one file
(RV contracts §4 addendum). What RV owes on top is the failure catalogue: an
estate that routes an op at an engine which is absent, disabled, or simply cannot
serve that op should be told **at boot**, by name, rather than discovering it one
request at a time.

The distinction the messages have to keep is between *unknown*, *disabled* and
*not in this lane*. They are three different mistakes with three different fixes,
and an operator reading "engine not registered" learns none of them.

Validation lives in `EngineRegistry.__init__` because that is the first moment
both halves exist — the resolved table AND the engines whose `supports()` it must
be checked against. The registry is built once at boot on both the REST and gRPC
paths (`create_app()` at import, `NlpServicer.__init__`), so "at boot, not at
first request" is what this buys.
"""

from __future__ import annotations

import pytest

from nlp_service.config import (
    AppConfig,
    BackendConfig,
    EnginesConfig,
    LangidEngineConfig,
    LlmEmulatedConfig,
    load_config,
)
from nlp_service.engines import EngineRegistry
from nlp_service.engines.llm_emulated_engine import EMULATED_ENGINE_NAME
from nlp_service.routing import RoutingConfigError


def a_config(*, routing: dict, emulation: bool = True, stanza: bool = True) -> AppConfig:
    return AppConfig(
        engines=EnginesConfig(
            morphodita=BackendConfig(enabled=False),
            nametag3=BackendConfig(enabled=False),
            stanza=BackendConfig(
                enabled=stanza, url="http://stanza:8090", model="stanza-cs-en", model_version="1.10.0"
            ),
            spacy=BackendConfig(url="http://spacy:8091", model="en_core_web_md", model_version="3.8.0"),
            langid=LangidEngineConfig(model_version="lingua-2.0"),
            llm_emulated=LlmEmulatedConfig(
                enabled=emulation, url="http://llm-gateway:8080", model="claude-haiku-4-5"
            ),
        ),
        op_routing=routing,
    )


class TestTheGoldenConfig:
    def test_a_mixed_table_binding_emulated_ner_loads(self):
        """The plan's own gate case, as a config: real engine for morphology,
        emulated for the op this lane has no head for."""
        registry = EngineRegistry(
            a_config(
                routing={
                    "TOKENIZE.cs": "stanza",
                    "LEMMATIZE.cs": "stanza",
                    "NER.cs": EMULATED_ENGINE_NAME,
                    "DETECT_LANGUAGE": "langid",
                }
            )
        )
        assert EMULATED_ENGINE_NAME in registry.list_engines()

    def test_the_shipped_config_validates_in_both_lanes(self):
        """The regression that keeps the validator honest: it must never be the
        reason a deployed config stops booting."""
        for lane in ("default", "option"):
            config = load_config()
            config.lane = lane
            EngineRegistry(config)  # must not raise


class TestTheRejectionCatalogue:
    def test_an_unknown_engine_names_the_registered_set(self):
        with pytest.raises(RoutingConfigError) as exc:
            EngineRegistry(a_config(routing={"TOKENIZE.cs": "morphodyta"}))
        message = str(exc.value)
        assert "morphodyta" in message
        assert "stanza" in message and "spacy" in message  # the set, so the typo is visible

    def test_an_op_the_engine_cannot_serve_is_refused_at_boot(self):
        """spaCy has no Czech. Today this routes anyway and quietly falls
        through to the last-resort scan, so the estate gets *an* answer from *an*
        engine and no indication its table was ignored."""
        with pytest.raises(RoutingConfigError) as exc:
            EngineRegistry(a_config(routing={"NER.cs": "spacy"}))
        assert "spacy" in str(exc.value)
        assert "NER" in str(exc.value) and "cs" in str(exc.value)

    def test_a_region_qualified_key_is_refused_and_names_the_fix(self):
        """`LEMMATIZE.en-US` is dead on arrival: requests fold to the primary
        subtag before they reach the table, so the key can never be hit. Without
        this case it still fails, but as "stanza does not serve LEMMATIZE for
        'en-US'" — which blames the engine for a mistake in the key."""
        with pytest.raises(RoutingConfigError) as exc:
            EngineRegistry(a_config(routing={"LEMMATIZE.en-US": "stanza"}))
        message = str(exc.value)
        assert "en-US" in message
        assert "LEMMATIZE.en" in message  # the correction, spelled out

    def test_a_disabled_engine_says_disabled_not_unknown(self):
        """Three mistakes, three fixes: a typo, an engine switched off, and an
        engine this lane does not carry. An operator who reads "not registered"
        for all three learns nothing about which one they made."""
        with pytest.raises(RoutingConfigError) as exc:
            EngineRegistry(a_config(routing={"TOKENIZE.cs": "stanza"}, stanza=False))
        assert "disabled" in str(exc.value).lower()

    def test_routing_to_the_emulated_engine_while_it_is_disabled_is_a_load_error(self):
        """The case RV-6 cares about most: a config that says "emulate cs NER"
        while emulation is off must NOT quietly serve cs NER from somewhere else,
        and must not wait until a request to say so."""
        with pytest.raises(RoutingConfigError) as exc:
            EngineRegistry(
                a_config(routing={"NER.cs": EMULATED_ENGINE_NAME}, emulation=False)
            )
        assert EMULATED_ENGINE_NAME in str(exc.value)
        assert "disabled" in str(exc.value).lower()

    def test_a_fallback_key_is_validated_like_any_other(self):
        """`NER.en.fallback` is a route. A typo there is invisible until the
        primary engine fails — which is the worst possible moment to find out."""
        with pytest.raises(RoutingConfigError) as exc:
            EngineRegistry(a_config(routing={"NER.en": "spacy", "NER.en.fallback": "nonesuch"}))
        assert "nonesuch" in str(exc.value)

    def test_the_bare_detect_language_key_is_validated(self):
        with pytest.raises(RoutingConfigError):
            EngineRegistry(a_config(routing={"DETECT_LANGUAGE": "stanza"}))

    def test_an_unknown_op_is_refused(self):
        with pytest.raises(RoutingConfigError) as exc:
            EngineRegistry(a_config(routing={"LEMATIZE.cs": "stanza"}))
        assert "LEMATIZE" in str(exc.value)

    def test_the_failure_is_at_construction_not_at_the_first_request(self):
        """Stated as its own assertion because it is the whole point of the
        task: the alternative design — validating inside `route()` — passes every
        test above and still lets a broken estate boot green."""
        config = a_config(routing={"NER.cs": "spacy"})
        with pytest.raises(RoutingConfigError):
            EngineRegistry(config)


class TestAbsentConfigChangesNothing:
    def test_the_matrix_is_identical_with_and_without_the_emulated_block(self):
        """The additive discipline, asserted against the ACTIVE LANE'S RESOLVED
        TABLE rather than a frozen file — the lane is what a deployment runs, and
        a byte-comparison against config.yaml would pass while the thing that
        serves requests had changed."""
        for lane in ("default", "option"):
            with_block = load_config()
            with_block.lane = lane
            without = load_config()
            without.lane = lane
            without.engines.llm_emulated = LlmEmulatedConfig()  # bare defaults

            assert _matrix(with_block) == _matrix(without)

    def test_the_shipped_default_lane_still_has_no_cs_ner_row(self):
        """The NL-14 degrade is NLS's, and RV-P8 must not have quietly filled the
        hole it depends on by merely declaring an engine that could."""
        config = load_config()
        rows = EngineRegistry(config).served_capabilities()
        assert not [r for r in rows if r["language"] == "cs" and r["op"].value == "NER"]


def _matrix(config: AppConfig) -> list[tuple]:
    return [
        (r["language"], r["op"].value, r["engine"], r["model_version"], r["tier"], r["is_floor"])
        for r in EngineRegistry(config).capability_matrix()
    ]
