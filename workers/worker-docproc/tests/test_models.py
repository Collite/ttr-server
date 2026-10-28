# SPDX-License-Identifier: Apache-2.0
"""DX-P0 S0.2 T4 — the message models against the shared fixtures (contracts §2.4/§10)."""

from __future__ import annotations

import json

import pytest
from pydantic import ValidationError

from docproc.sdk.models import (
    Completion,
    CompletionStatus,
    RunVisibility,
    WorkItem,
    canonical,
    unspecified_fields,
)
from tests.conftest import CONTRACTS, kantheon_dir


@pytest.mark.parametrize(("name", "model"), [("workItem.json", WorkItem), ("completion.json", Completion)])
def test_fixture_round_trips_byte_equal(name: str, model: type[WorkItem] | type[Completion]) -> None:
    text = (CONTRACTS / name).read_text(encoding="utf-8")
    parsed = model.strict(text)
    assert canonical(parsed.dump()) == text


def test_completion_defaults_are_absent() -> None:
    c = Completion.strict((CONTRACTS / "completion.json").read_text(encoding="utf-8"))
    assert c.status is CompletionStatus.SUCCEEDED
    assert c.child_unit_ids == [] and c.usage.tokens_in == 0
    assert "childUnitIds" not in c.dump() and "tokensIn" not in c.dump()["usage"]


def test_work_item_is_pointers_only_under_4kb() -> None:
    raw = (CONTRACTS / "workItem.json").read_bytes()
    assert len(raw) < 4096
    item = WorkItem.strict(raw)
    assert len(json.dumps(item.dump(), separators=(",", ":")).encode()) < 1024
    assert item.bundle_ref is None


def test_strict_twin_refuses_an_unknown_key_and_a_wrong_run_id() -> None:
    with pytest.raises(ValidationError):
        Completion.strict({"runId": "s/1", "stepId": "s", "status": "SUCCEEDED", "extra": 1})
    text = (
        (CONTRACTS / "workItem.json").read_text(encoding="utf-8").replace('"runId": "step-1/1"', '"runId": "step-1/2"')
    )
    with pytest.raises(ValidationError):
        WorkItem.model_validate_json(text)  # the key rule holds for the tolerant reader too


def test_reader_ignores_a_key_a_newer_producer_added() -> None:
    """review-158 F5 — tolerant readers, strict fixtures."""
    text = (CONTRACTS / "workItem.json").read_text(encoding="utf-8")
    newer = text.replace("{\n", '{\n  "futureField": 1,\n', 1)
    assert WorkItem.model_validate_json(newer) == WorkItem.strict(text)
    with pytest.raises(ValidationError):
        WorkItem.strict(newer)
    with pytest.raises(ValidationError):  # nested models are strict under the twin as well
        WorkItem.strict(text.replace('"types": [', '"typos": 1, "types": [', 1))


def test_unknown_enum_value_reads_as_unspecified() -> None:
    c = Completion.model_validate({"runId": "s/1", "stepId": "s", "status": "PAUSED"})
    assert c.status is CompletionStatus.COMPLETION_STATUS_UNSPECIFIED
    assert unspecified_fields(c) == ["status"]
    with pytest.raises(ValidationError):
        Completion.strict({"runId": "s/1", "stepId": "s", "status": "PAUSED"})
    text = (CONTRACTS / "workItem.json").read_text(encoding="utf-8").replace('"CANDIDATE"', '"ARCHIVED"')
    item = WorkItem.model_validate_json(text)
    assert item.visibility is RunVisibility.RUN_VISIBILITY_UNSPECIFIED
    assert unspecified_fields(item) == ["visibility"]
    assert unspecified_fields(WorkItem.strict((CONTRACTS / "workItem.json").read_text(encoding="utf-8"))) == []


def test_shared_canonical_sample_prints_byte_equal() -> None:
    """The one sample kantheon's CanonicalJson and ttr-core's SchemaJson are pinned to as well (review-158)."""
    text = (CONTRACTS / "canonical.sample.json").read_text(encoding="utf-8")
    assert canonical(json.loads(text)) == text


def test_fixtures_identical_to_kantheon_side_by_side() -> None:
    """⚑DX-P-5: the fixture set is the only artefact crossing the repo boundary — the two copies must be identical."""
    k = kantheon_dir()
    if k is None:
        pytest.skip("no kantheon checkout beside this one (set KANTHEON_DIR)")
    theirs = k / "shared" / "proto" / "fixtures" / "dx"
    for ours in sorted(CONTRACTS.glob("*.json")):
        assert (theirs / ours.name).read_bytes() == ours.read_bytes(), ours.name
