# SPDX-License-Identifier: Apache-2.0
"""OL W1c·S1·T7 — the lane's settings and the `Deps` it adds (OL-C11…C14)."""

from __future__ import annotations

import pytest

from golem_py.deps import Deps
from golem_py.errors import ConfigInvalid
from golem_py.settings import GolemSettings
from tests.helpers import deps as make_deps


def test_with_nothing_configured_the_lane_has_nothing_to_run_on() -> None:
    settings = GolemSettings()
    assert settings.llm_lane_enabled is True  # OL-C11: the switch defaults on …
    assert settings.query_target == settings.llm_target == settings.meta_target == ""  # … idle
    assert settings.llm_model_class == "capable"
    assert settings.llm_model_slice_entities == 40


def test_the_env_names_the_three_services(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("GOLEM_QUERY_TARGET", "query:7280")
    monkeypatch.setenv("GOLEM_QUERY_WORLD", "hartland_cz")
    monkeypatch.setenv("GOLEM_QUERY_TIMEOUT_S", "12.5")
    monkeypatch.setenv("GOLEM_LLM_TARGET", "llm-gateway:7300")
    monkeypatch.setenv("GOLEM_LLM_MODEL", "m-1")
    monkeypatch.setenv("GOLEM_LLM_LANE_ENABLED", "false")
    monkeypatch.setenv("GOLEM_LLM_MODEL_SLICE_ENTITIES", "25")
    monkeypatch.setenv("GOLEM_META_TARGET", "metadata:7270")
    monkeypatch.setenv("GOLEM_META_PACKAGES", "hartland, returns ,")
    monkeypatch.setenv("GOLEM_META_TIMEOUT_S", "4")

    s = GolemSettings.from_env()

    assert (s.query_target, s.query_world, s.query_timeout_s) == ("query:7280", "hartland_cz", 12.5)
    assert (s.llm_target, s.llm_model, s.llm_lane_enabled) == ("llm-gateway:7300", "m-1", False)
    assert s.llm_model_slice_entities == 25
    assert (s.meta_target, s.meta_packages, s.meta_timeout_s) == (
        "metadata:7270",
        ["hartland", "returns"],
        4.0,
    )


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("GOLEM_LLM_MODEL_SLICE_ENTITIES", "0"),
        ("GOLEM_LLM_LANE_ENABLED", "maybe"),
        ("GOLEM_QUERY_TIMEOUT_S", "-1"),
    ],
)
def test_a_bad_lane_setting_refuses_to_start(
    monkeypatch: pytest.MonkeyPatch, name: str, value: str
) -> None:
    monkeypatch.setenv(name, value)
    with pytest.raises(ConfigInvalid):
        GolemSettings.from_env()


def test_the_lane_ports_default_to_none() -> None:
    deps: Deps = make_deps(core=object())
    assert deps.door is None and deps.llm is None and deps.model_slice is None
