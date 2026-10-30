# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import json
from typing import Any

import pytest

from docproc.kinds.chunk.checks import failures
from docproc.kinds.chunk.types import ChunkSpan
from docproc.runtime.registry import TypeRegistryClient
from docproc.runtime.validators import Validators
from docproc.sdk.models import Annotation, Span, Unit, payload_text
from tests.conftest import CONTRACTS
from tests.fakes import PackManager


@pytest.mark.parametrize("case", json.loads((CONTRACTS / "chunk-validators.json").read_text()))
def test_shared_chunk_validator_table(case: dict[str, Any]) -> None:
    spans = [
        ChunkSpan(
            s["start"], s["end"], tuple((e["start"], e["end"]) for e in s.get("extraSpans", [])), s.get("payload", {})
        )
        for s in case["spans"]
    ]
    assert (
        failures(case["text"], spans, case["params"], case["params"].get("strategy", "recursive")) == case["expected"]
    )


def test_runtime_checks_are_mandatory_atomic_and_check_empty_outputs() -> None:
    registry = TypeRegistryClient(PackManager())
    registry.load("1")
    validators = Validators(registry)
    units = {1: Unit(id=1, content_text="a b c"), 2: Unit(id=2, content_text="okay")}
    outputs = [
        Annotation(
            unit_id=1,
            spans=[Span(unit_id=1, start=0, end=1)],
            type="Chunk",
            payload_json=payload_text({"strategy": "recursive", "ordinal": 0}),
        ),
        Annotation(
            unit_id=1,
            spans=[Span(unit_id=1, start=4, end=5)],
            type="Chunk",
            payload_json=payload_text({"strategy": "recursive", "ordinal": 1}),
        ),
        Annotation(
            unit_id=2,
            spans=[Span(unit_id=2, start=0, end=4)],
            type="Chunk",
            payload_json=payload_text({"strategy": "recursive", "ordinal": 0}),
        ),
    ]
    engine = {"strategy": "recursive", "params": {"tokenizer": "whitespace", "max_tokens": 2}}
    good, verdicts = validators.validate_chunks(outputs, units, engine, {})
    assert [a.unit_id for a in good] == [2]
    assert [v.payload()["rule"] for v in verdicts] == ["coverage"]
    good, verdicts = validators.validate_chunks([], units, engine, {})
    assert not good and len(verdicts) == 2
    assert all(v.payload()["rule"] == "coverage" for v in verdicts)
    outputs[0].payload_json = '{"strategy":"recursive","ordinal":0,"unexpected":true}'
    good, verdicts = validators.validate_chunks(outputs, units, engine, {})
    assert [a.unit_id for a in good] == [2]
    assert {v.payload()["rule"] for v in verdicts} == {"coverage", "schema_valid"}
