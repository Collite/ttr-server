# SPDX-License-Identifier: Apache-2.0
"""DX-P0 S0.2 T4 — the message models against the shared fixtures (contracts §2.4/§10)."""

from __future__ import annotations

import json

import pytest
from pydantic import ValidationError

from aristarchos.sdk.models import Completion, CompletionStatus, WorkItem, canonical
from tests.conftest import CONTRACTS, kantheon_dir


@pytest.mark.parametrize(("name", "model"), [("workItem.json", WorkItem), ("completion.json", Completion)])
def test_fixture_round_trips_byte_equal(name: str, model: type[WorkItem] | type[Completion]) -> None:
    text = (CONTRACTS / name).read_text(encoding="utf-8")
    parsed = model.model_validate_json(text)
    assert canonical(parsed.dump()) == text


def test_completion_defaults_are_absent() -> None:
    c = Completion.model_validate_json((CONTRACTS / "completion.json").read_text(encoding="utf-8"))
    assert c.status is CompletionStatus.SUCCEEDED
    assert c.child_unit_ids == [] and c.usage.tokens_in == 0
    assert "childUnitIds" not in c.dump() and "tokensIn" not in c.dump()["usage"]


def test_work_item_is_pointers_only_under_4kb() -> None:
    raw = (CONTRACTS / "workItem.json").read_bytes()
    assert len(raw) < 4096
    item = WorkItem.model_validate_json(raw)
    assert len(json.dumps(item.dump(), separators=(",", ":")).encode()) < 1024
    assert item.bundle_ref is None


def test_unknown_key_and_wrong_run_id_are_refused() -> None:
    with pytest.raises(ValidationError):
        Completion.model_validate({"runId": "s/1", "stepId": "s", "status": "SUCCEEDED", "extra": 1})
    text = (
        (CONTRACTS / "workItem.json").read_text(encoding="utf-8").replace('"runId": "step-1/1"', '"runId": "step-1/2"')
    )
    with pytest.raises(ValidationError):
        WorkItem.model_validate_json(text)


def test_fixtures_identical_to_kantheon_side_by_side() -> None:
    """⚑DX-P-5: the fixture set is the only artefact crossing the repo boundary — the two copies must be identical."""
    k = kantheon_dir()
    if k is None:
        pytest.skip("no kantheon checkout beside this one (set KANTHEON_DIR)")
    theirs = k / "shared" / "proto" / "fixtures" / "dx"
    for ours in sorted(CONTRACTS.glob("*.json")):
        assert (theirs / ours.name).read_bytes() == ours.read_bytes(), ours.name
