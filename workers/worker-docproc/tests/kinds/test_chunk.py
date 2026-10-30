# SPDX-License-Identifier: Apache-2.0
import pytest

from docproc.kinds.chunk.kind import Chunk
from tests.fakes import FakeWarehouse
from tests.fakes_nlp import recorded_nlp
from tests.pack import DOCS, assert_golden, context, units_of


@pytest.mark.parametrize(
    "strategy", ["paragraph", "fixed-tokens", "sentence-window", "recursive", "structural", "page", "table-aware"]
)
@pytest.mark.parametrize("doc", DOCS)
def test_strategy_golden(strategy: str, doc: str) -> None:
    ctx = context(doc, "chunk-recursive")
    ctx.manifest["engine"]["strategy"] = strategy
    if strategy in ("sentence-window", "paragraph", "page"):
        ctx.manifest["engine"]["params"] = {}
    if strategy == "sentence-window":
        ctx.manifest["engine"]["params"].update(window=3, overlap=1)
    with recorded_nlp() as nlp:
        result = Chunk(nlp, FakeWarehouse(units_of(doc))).run(ctx)
    assert not result.verdicts
    assert_golden(f"chunk/{strategy}", doc, result.annotations, ctx.units)
