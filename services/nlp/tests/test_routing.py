# SPDX-License-Identifier: Apache-2.0
"""RG-P1.S1.T3 — per-op-per-language routing (test-first).

`EngineRegistry.route(language, op)` resolves each (language, op) to a `Route`
that names an engine **and** a non-empty model + version (S-1 at the route
level). Unsupported (language, op) resolves to the degrade floor with an
`RG-NLP-010` info marker (never a 500, never an empty model). Routing is
config-driven — the assertions build a config, not the live `config.yaml`.
"""

from __future__ import annotations

import pytest

from nlp_service.config import (
    AppConfig,
    BackendConfig,
    EnginesConfig,
    LangidEngineConfig,
)
from nlp_service.diagnostics import RG_NLP_002, RG_NLP_010
from nlp_service.engines.base import NlpOp
from nlp_service.engines import EngineRegistry


def _config() -> AppConfig:
    """A self-hosted-pinned cs/en config mirroring the RG-P1 routing table."""
    return AppConfig(
        engines=EnginesConfig(
            morphodita=BackendConfig(
                url="http://morphodita:8080",
                model="czech-morfflex2.0-pdtc1.0-220710",
                model_version="220710",
                tier="SELF_HOSTED_PINNED",
            ),
            nametag3=BackendConfig(
                url="http://nametag3:8001",
                model="nametag3-czech-cnec2.0-240830",
                model_version="240830",
                tier="SELF_HOSTED_PINNED",
            ),
            stanza=BackendConfig(
                url="http://stanza:8090",
                model="stanza-cs-en",
                model_version="1.10.0",
                tier="SELF_HOSTED_PINNED",
            ),
            spacy=BackendConfig(
                url="http://spacy:8091",
                model="en_core_web_md",
                model_version="3.8.0",
                tier="SELF_HOSTED_PINNED",
            ),
            langid=LangidEngineConfig(model_version="lingua-2.0"),
        ),
        op_routing={
            "TOKENIZE.cs": "morphodita",
            "SENTENCE_SPLIT.cs": "morphodita",
            "LEMMATIZE.cs": "morphodita",
            "POS_TAG.cs": "morphodita",
            "DEP_PARSE.cs": "stanza",
            "NER.cs": "nametag3",
            "TOKENIZE.en": "stanza",
            "LEMMATIZE.en": "stanza",
            "POS_TAG.en": "stanza",
            "DEP_PARSE.en": "stanza",
            "NER.en": "stanza",
            "NER.en.fallback": "spacy",
            "DETECT_LANGUAGE": "langid",
        },
        default_language="cs",
    )


@pytest.fixture
def registry() -> EngineRegistry:
    return EngineRegistry(_config())


class TestCzechRouting:
    @pytest.mark.parametrize(
        "op,engine",
        [
            (NlpOp.TOKENIZE, "morphodita"),
            (NlpOp.SENTENCE_SPLIT, "morphodita"),
            (NlpOp.LEMMATIZE, "morphodita"),
            (NlpOp.POS_TAG, "morphodita"),
            (NlpOp.DEP_PARSE, "stanza"),
            (NlpOp.NER, "nametag3"),
        ],
    )
    def test_cs_routes(self, registry, op, engine):
        route = registry.route("cs", op)
        assert route.engine == engine
        assert not route.is_floor


class TestEnglishRouting:
    @pytest.mark.parametrize(
        "op,engine",
        [
            (NlpOp.TOKENIZE, "stanza"),
            (NlpOp.LEMMATIZE, "stanza"),
            (NlpOp.NER, "stanza"),
        ],
    )
    def test_en_routes(self, registry, op, engine):
        route = registry.route("en", op)
        assert route.engine == engine
        assert not route.is_floor


class TestDetectLanguage:
    def test_detect_language_routes_to_langid(self, registry):
        route = registry.route("cs", NlpOp.DETECT_LANGUAGE)
        assert route.engine == "langid"


class TestS1AtRouteLevel:
    def test_no_configured_route_has_an_empty_model(self, registry):
        """Every (lang, op) in the routing table names engine + model + version."""
        for lang in ("cs", "en"):
            for op in NlpOp:
                route = registry.route(lang, op)
                assert route.engine, f"{lang}/{op} resolved to no engine"
                assert route.model, f"{lang}/{op} → engine {route.engine!r} has empty model (S-1)"
                assert route.model_version, f"{lang}/{op} → {route.engine!r} has empty version"


class TestDegradeFloor:
    def test_unsupported_language_falls_to_floor_with_rg_nlp_010(self, registry):
        route = registry.route("de", NlpOp.LEMMATIZE)
        assert route.is_floor
        assert RG_NLP_010 in route.info
        # S-1 holds even on the floor: it names its deterministic producer.
        assert route.model

    def test_floor_tokenize_is_deterministic_producer(self, registry):
        route = registry.route("de", NlpOp.TOKENIZE)
        assert route.is_floor
        assert route.model  # e.g. the fold/segmentation producer, never blank


class TestRemoteUnpinnedTier:
    def test_lindat_route_is_flagged_remote_unpinned(self):
        """A Lindat-pointed backend reports REMOTE_UNPINNED + RG-NLP-002."""
        cfg = _config()
        cfg.engines.morphodita.tier = "REMOTE_UNPINNED"
        cfg.engines.morphodita.url = "https://lindat.mff.cuni.cz/services/morphodita/api/tag"
        registry = EngineRegistry(cfg)
        route = registry.route("cs", NlpOp.LEMMATIZE)
        assert route.tier == "REMOTE_UNPINNED"
        assert RG_NLP_002 in route.info


class TestLanguageTagNormalization:
    """A caller's BCP-47 tag folds to the primary subtag before routing.

    The bug this pins: hartland's Shem declares `locale_defaults: [en-US, cs-CZ]`
    and that locale reached the front verbatim. `en-US` matched no routing key, no
    engine's `supported_languages()`, no fallback and nothing in the last-resort
    capability scan, so every request degraded to the floor with RG-NLP-010 and
    returned zero tokens — while the identical text under `en` parsed fine.
    """

    @pytest.mark.parametrize("tag", ["en-US", "en_US", "EN", "en-GB", "  en-US  ", "en"])
    def test_regional_en_routes_exactly_like_bare_en(self, registry, tag):
        assert registry.route(tag, NlpOp.LEMMATIZE).engine == "stanza"

    @pytest.mark.parametrize("tag", ["cs-CZ", "cs_CZ", "CS", "cs"])
    def test_regional_cs_routes_exactly_like_bare_cs(self, registry, tag):
        assert registry.route(tag, NlpOp.LEMMATIZE).engine == "morphodita"

    def test_region_is_not_the_floor(self, registry):
        """The symptom itself, stated as an assertion."""
        route = registry.route("en-US", NlpOp.LEMMATIZE)
        assert not route.is_floor
        assert RG_NLP_010 not in route.info

    def test_route_reports_the_normalized_tag(self, registry):
        """A route describes what SERVED — and `en` is what serves `en-US`."""
        assert registry.route("en-US", NlpOp.LEMMATIZE).language == "en"

    def test_a_genuinely_unsupported_language_still_floors(self, registry):
        """Normalization must not turn every tag into a match."""
        route = registry.route("de-DE", NlpOp.LEMMATIZE)
        assert route.is_floor
        assert RG_NLP_010 in route.info
        assert route.language == "de"

    def test_fallback_still_reachable_through_a_regional_tag(self, registry):
        """`NER.en.fallback: spacy` is keyed on the bare subtag too."""
        registry._engines.pop("stanza")
        assert registry.route("en-US", NlpOp.NER).engine == "spacy"


class TestNormalizeLanguage:
    @pytest.mark.parametrize(
        "raw,expected",
        [
            ("en-US", "en"),
            ("en_US", "en"),
            ("EN", "en"),
            ("cs-CZ", "cs"),
            ("zh-Hant-TW", "zh"),
            ("  pt-BR  ", "pt"),
            ("en", "en"),
        ],
    )
    def test_folds_to_primary_subtag(self, raw, expected):
        from nlp_service.routing import normalize_language

        assert normalize_language(raw) == expected

    def test_empty_stays_empty(self):
        """`""` means "not stated" — the orchestrator branches on it to decide
        whether to run DETECT_LANGUAGE, so a default here would disable detection."""
        from nlp_service.routing import normalize_language

        assert normalize_language("") == ""

    @pytest.mark.parametrize("raw", ["en-US", "en", "", "cs_CZ", "ZH-hant"])
    def test_is_idempotent(self, raw):
        """Applied at both the orchestrator entry and `route()` — twice must equal once."""
        from nlp_service.routing import normalize_language

        assert normalize_language(normalize_language(raw)) == normalize_language(raw)
