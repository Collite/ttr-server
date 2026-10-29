# SPDX-License-Identifier: Apache-2.0
import pytest

from docproc.runtime.registry import TypeRegistryClient
from docproc.runtime.validators import Validators, span_within_unit
from docproc.sdk.models import Annotation, Span, Unit, payload_text
from tests.fakes import PackManager


def test_unicode_spans() -> None:
    unit = Unit(id=1, content_text="a🙂č")
    annotation = Annotation(unit_id=1, spans=[Span(unit_id=1, start=1, end=2)])
    span_within_unit(annotation, {1: unit})
    assert unit.slice(annotation.spans[0]) == "🙂"
    annotation.spans[0].end = 4
    with pytest.raises(ValueError, match="outside unit"):
        span_within_unit(annotation, {1: unit})


def test_schema_rejection_drops_output_and_produces_verdict() -> None:
    registry = TypeRegistryClient(PackManager())
    registry.load("1")
    invalid = Annotation(unit_id=1, type="Statement[review]", payload_json=payload_text({"typo": True}))
    output, verdicts = Validators(registry).validate([invalid], {1: Unit(id=1)}, [])
    assert output == [] and len(verdicts) == 1
    assert verdicts[0].type == "Verdict" and verdicts[0].derived_from == []
    assert (
        verdicts[0].payload()["rule"] == "schema_valid"
        and "output #0 Statement[review]" in verdicts[0].payload()["message"]
    )


def test_unconfigured_vocabulary_is_verdict() -> None:
    manager = PackManager()
    manager.extra_types = [{"key": "Mention", "schema": {"type": "object"}}]
    registry = TypeRegistryClient(manager)
    registry.load("1")
    out, verdicts = Validators(registry).validate(
        [Annotation(unit_id=1, type="Mention", payload_json="{}")], {1: Unit(id=1)}, ["code_exists_in(items)"]
    )
    assert not out and "not yet configured" in verdicts[0].payload()["message"]
