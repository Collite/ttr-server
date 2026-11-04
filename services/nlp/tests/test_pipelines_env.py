# SPDX-License-Identifier: Apache-2.0
"""DX-P3 T8f — `NLP_PIPELINES`: a world's pipelines come with the deployment that mounts its lists and packs, as
the lane and the morph sources do (config.yaml ships `pipelines: {}`). What a value that cannot be read does: the
pipelines stay config.yaml's, and the front says so."""

from __future__ import annotations

import pytest

from nlp_service.config import AppConfig, PipelineConfig, apply_env_overrides


def test_pipelines_come_from_the_env_merged_by_name(monkeypatch):
    monkeypatch.setenv(
        "NLP_PIPELINES",
        '{"hartland-mini-mentions": {"ops": ["TOKENIZE", "LEMMATIZE"],'
        ' "gazetteer": ["items", "item-lemmas", "dates", "modifiers"]}}',
    )
    config = AppConfig(pipelines={"from-file": PipelineConfig(ops=["NER"])})
    config = apply_env_overrides(config)
    assert sorted(config.pipelines) == ["from-file", "hartland-mini-mentions"]
    assert config.pipelines["hartland-mini-mentions"].ops == ["TOKENIZE", "LEMMATIZE"]
    assert config.pipelines["hartland-mini-mentions"].gazetteer == [
        "items",
        "item-lemmas",
        "dates",
        "modifiers",
    ]
    assert config.pipelines["from-file"].ops == ["NER"]


def test_an_env_pipeline_replaces_the_files_of_the_same_name(monkeypatch):
    monkeypatch.setenv("NLP_PIPELINES", '{"query-patterns": {"ops": ["TOKENIZE"]}}')
    config = apply_env_overrides(
        AppConfig(pipelines={"query-patterns": PipelineConfig(ops=["NER"])})
    )
    assert config.pipelines["query-patterns"].ops == ["TOKENIZE"]


@pytest.mark.parametrize(
    "raw",
    [
        '{"p": {"ops": ["TOKENIZE"], "gazeteer": ["items"]}}',
        "[1, 2]",
        "{not json",
        '{"p": {"ops": "TOKENIZE"}}',
    ],
)
def test_an_unreadable_value_is_logged_and_dropped_whole(monkeypatch, caplog, raw):
    monkeypatch.setenv("NLP_PIPELINES", raw)
    config = apply_env_overrides(
        AppConfig(pipelines={"from-file": PipelineConfig(ops=["NER"])})
    )
    assert list(config.pipelines) == ["from-file"]
    assert "NLP_PIPELINES ignored" in caplog.text
