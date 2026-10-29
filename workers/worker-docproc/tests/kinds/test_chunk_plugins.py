# SPDX-License-Identifier: Apache-2.0
from typing import Any

import pytest

from docproc.kinds.chunk.kind import Chunk
from docproc.kinds.chunk.types import ChunkSpan
from docproc.kinds.noop import Noop
from docproc.sdk.models import Unit
from tests.conftest import WORKER
from tests.fakes import FakeWarehouse
from tests.fakes_nlp import recorded_nlp
from tests.pack import context, units_of


@pytest.mark.parametrize(
    "strategy,rule",
    [
        ("plugin:whole", None),
        ("pack:chunkers/halves.py#Halves", None),
        ("plugin:raises", "chunk/plugin-error"),
        ("plugin:overlap", "overlap_within_declared"),
        ("plugin:outside", "span_within_unit"),
    ],
)
def test_real_installed_and_pack_plugins(strategy: str, rule: str | None) -> None:
    doc = "review-002.cs.json"
    ctx = context(doc, "chunk-recursive")
    ctx.manifest["engine"].update(
        strategy=strategy, params={"tokenizer": "whitespace", "max_tokens": 500, "overlap": 0}
    )
    with recorded_nlp() as nlp:
        result = Chunk(nlp, FakeWarehouse(units_of(doc)), str(WORKER / "tests/fixtures/plugin-pack")).run(ctx)
    if rule:
        assert not result.annotations and [v.payload()["rule"] for v in result.verdicts] == [rule] * 2
    else:
        assert result.annotations and not result.verdicts


def test_missing_pack_path_and_path_escape_are_diagnostics() -> None:
    ctx = context("review-002.cs.json", "chunk-recursive")
    ctx.manifest["engine"].update(strategy="pack:../escape.py#Oops", params={})
    with recorded_nlp() as nlp:
        result = Chunk(nlp, FakeWarehouse(units_of("review-002.cs.json"))).run(ctx)
    assert not result.annotations and all(v.payload()["rule"] == "chunk/plugin-error" for v in result.verdicts)


def test_partial_manifest_and_plugin_isolation(monkeypatch: pytest.MonkeyPatch) -> None:
    from dataclasses import replace

    from docproc.runtime.registry import KindRegistry
    from tests.pack import new_context

    class Partial:
        def chunk(self, text: str, unit: Unit, params: dict[str, Any]) -> list[ChunkSpan]:
            return [ChunkSpan(0, 1)]

    unit = Unit(id=9, content_text="x long rest")
    ctx = new_context(
        context("review-002.cs.json", "chunk-recursive"),
        kind="chunk",
        engine={"strategy": "plugin:test", "partial": True, "params": {"tokenizer": "whitespace", "max_tokens": 200}},
    )
    kinds = KindRegistry()
    kinds.register("chunk", Noop())
    assert ctx.processor
    ctx = replace(ctx, manifest=kinds.manifest(ctx.processor), units={9: unit})
    monkeypatch.setattr("docproc.kinds.chunk.plugins.load", lambda *args: Partial())
    with recorded_nlp() as nlp:
        result = Chunk(nlp, FakeWarehouse([unit])).run(ctx)
    assert result.annotations and not result.verdicts

    class Mutating:
        def chunk(self, text: str, unit: Unit, params: dict[str, Any]) -> list[ChunkSpan]:
            unit.content_text = unit.content_text.strip()
            return [ChunkSpan(0, len(unit.content_text))]

    unit.content_text = "    x"
    ctx.manifest["engine"]["partial"] = False
    monkeypatch.setattr("docproc.kinds.chunk.plugins.load", lambda *args: Mutating())
    with recorded_nlp() as nlp:
        result = Chunk(nlp, FakeWarehouse([unit])).run(ctx)
    assert unit.content_text == "    x" and not result.annotations
    assert [v.payload()["rule"] for v in result.verdicts] == ["coverage"]

    class Exiting:
        def chunk(self, text: str, unit: Unit, params: dict[str, Any]) -> list[ChunkSpan]:
            raise SystemExit(2)

    monkeypatch.setattr("docproc.kinds.chunk.plugins.load", lambda *args: Exiting())
    with recorded_nlp() as nlp:
        result = Chunk(nlp, FakeWarehouse([unit])).run(ctx)
    assert result.verdicts[0].payload()["rule"] == "chunk/plugin-error"
