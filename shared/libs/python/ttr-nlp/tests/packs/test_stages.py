# SPDX-License-Identifier: Apache-2.0
"""OL W1b·S1 T1 — the `ttrnlp.stages` group under the loader (OL-C6…C9).

The service half of the specs — `stages:` against the `rules:` alias in a run,
and a refused reload keeping the previous snapshot — is in `services/nlp`.
"""

from __future__ import annotations

import sys
from importlib.metadata import EntryPoint

import pytest

from ttrnlp.packs.diag import NLS_PACK_004, NLS_PACK_006, SEVERITY_INFO
from ttrnlp.packs.loader import LoadError, load_sources, state_id_for
from ttrnlp.packs.validate import validate_sources
from ttrnlp.rules.stage import RulesStage
from ttrnlp.stages import GROUP, StageError, installed_stages

from .test_loader import good_list, good_pack, three_good, tree

RULES_ENTRY = "ttrnlp.rules.stage:RulesStage"


def entry(name: str, value: str = RULES_ENTRY) -> EntryPoint:
    return EntryPoint(name=name, value=value, group=GROUP)


class OtherStage(RulesStage):
    """A second stage, for the clash specs: same machinery, another name."""

    name = "other"
    source_suffixes = (".other.yaml",)


# ── the group (OL-C6) ────────────────────────────────────────────────────────


def test_this_wheel_registers_the_rule_engine_through_the_group():
    stages = installed_stages()
    assert list(stages) == ["rules"]
    assert isinstance(stages["rules"], RulesStage)
    assert stages["rules"].source_suffixes == (".pack.yaml",)


def test_b_two_entries_with_one_name_are_a_boot_error():
    with pytest.raises(StageError, match="two entries name stage 'rules'"):
        installed_stages([entry("rules"), entry("rules-again")])


def test_two_stages_owning_one_suffix_are_a_boot_error(monkeypatch):
    monkeypatch.setattr(OtherStage, "source_suffixes", (".pack.yaml",))
    with pytest.raises(StageError, match="both own '.pack.yaml'"):
        installed_stages([entry("rules"), entry("other", f"{__name__}:OtherStage")])


def test_a_stage_may_not_claim_the_list_suffix(monkeypatch):
    monkeypatch.setattr(OtherStage, "source_suffixes", (".list.yaml",))
    with pytest.raises(StageError, match="reserved suffix"):
        installed_stages([entry("other", f"{__name__}:OtherStage")])


def test_a_factory_without_the_shape_is_a_boot_error():
    with pytest.raises(StageError, match="no string `name`"):
        installed_stages([entry("bad", "builtins:object")])


# ── the snapshot (OL-C9) ─────────────────────────────────────────────────────


def test_the_snapshot_holds_each_stages_state_and_status(tmp_path):
    state = load_sources([str(three_good(tmp_path))])

    assert list(state.stages) == ["rules"]
    assert sorted(state.stages["rules"]) == ["alpha", "beta", "gamma"]
    assert dict(state.stage_status["rules"]) == {"packs_loaded": 3}
    assert state.packs_loaded == 3


def test_a_empty_group_loads_lists_and_the_gazetteer_as_today(tmp_path):
    root = tree(tmp_path, {"vocab.list.yaml": good_list("vocab")})

    state = load_sources([str(root)], stages={})

    assert dict(state.stages) == {}
    assert state.packs_loaded == 0
    assert dict(state.packs) == {}
    assert sorted(state.lists) == ["vocab"]
    assert state.gazetteer.list_ids == ("vocab",)
    assert state.diagnostics == ()
    assert state.state_id == load_sources([str(root)]).state_id


# ── a stage that is not installed (OL-C8) ────────────────────────────────────


def test_d_a_pipeline_naming_a_stage_not_installed_fails_all(tmp_path):
    pipelines = {
        "q": {"stages": [{"stage": "nope", "x": 1}, {"stage": "nope", "x": 2}]}
    }

    with pytest.raises(LoadError) as raised:
        load_sources([str(three_good(tmp_path))], pipelines=pipelines)

    (diagnostic,) = raised.value.diagnostics  # one per pipeline and stage
    assert diagnostic.code == NLS_PACK_006
    assert diagnostic.message == (
        "pipeline `q` names stage `nope`, which is not installed in this image"
    )


def test_d_the_rules_alias_without_the_rules_stage_is_the_same_error(tmp_path):
    with pytest.raises(LoadError) as raised:
        load_sources(
            [str(tree(tmp_path, {"vocab.list.yaml": good_list("vocab")}))],
            pipelines={"q": {"rules": [{"pack": "alpha", "phase": "p"}]}},
            stages={},
        )
    assert raised.value.codes == [NLS_PACK_006]


def test_e_a_file_no_installed_stage_owns_is_one_info_and_not_read(tmp_path):
    root = tree(tmp_path, {"alpha.pack.yaml": good_pack("alpha"), "notes.md": "x"})

    state = load_sources([str(root)], stages={})

    (diagnostic,) = state.diagnostics
    assert diagnostic.severity == SEVERITY_INFO
    assert diagnostic.code == NLS_PACK_006
    assert "alpha.pack.yaml" in diagnostic.message
    assert state.files == ()
    assert state.state_id == state_id_for([])


def test_e_a_single_unowned_file_is_one_info_too(tmp_path):
    path = tree(tmp_path, {"alpha.pack.yaml": good_pack("alpha")}) / "alpha.pack.yaml"

    diagnostics = validate_sources([str(path)], stages={})

    assert [(d.severity, d.code) for d in diagnostics] == [
        (SEVERITY_INFO, NLS_PACK_006)
    ]


def test_e_an_unowned_url_needs_no_http_extra(monkeypatch):
    """review-335 B1: ownership is settled before the optional reader is
    imported — an unowned URL is never fetched, so it cannot need `httpx`."""
    monkeypatch.setitem(sys.modules, "httpx", None)

    state = load_sources(["https://packs.example/unused.pack.yaml"], stages={})

    assert [(d.severity, d.code) for d in state.diagnostics] == [
        (SEVERITY_INFO, NLS_PACK_006)
    ]
    assert state.files == ()


# ── the pipeline table (OL-C7) ───────────────────────────────────────────────


def test_stages_and_the_rules_alias_are_checked_alike(tmp_path):
    root = str(three_good(tmp_path))
    good = [{"pack": "alpha", "phase": "p"}]
    bad = [{"pack": "alpha", "phase": "nope"}]

    load_sources([root], pipelines={"q": {"stages": [{"stage": "rules", **good[0]}]}})
    load_sources([root], pipelines={"q": {"rules": good}})

    as_stages = validate_sources(
        [root], pipelines={"q": {"stages": [{"stage": "rules", **bad[0]}]}}
    )
    as_alias = validate_sources([root], pipelines={"q": {"rules": bad}})

    assert [d.code for d in as_stages] == [d.code for d in as_alias] == [NLS_PACK_004]
    assert as_stages[0].message.removeprefix("pipelines.q.stages[0]: ") == (
        as_alias[0].message.removeprefix("pipelines.q.rules[0]: ")
    )


def test_a_stage_entry_without_a_stage_name_is_rejected(tmp_path):
    with pytest.raises(LoadError) as raised:
        load_sources(
            [str(three_good(tmp_path))], pipelines={"q": {"stages": [{"pack": "a"}]}}
        )
    assert raised.value.codes == [NLS_PACK_004]
