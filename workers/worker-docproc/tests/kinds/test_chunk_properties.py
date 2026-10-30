# SPDX-License-Identifier: Apache-2.0
"""Independent invariants for every strategy over deterministic random NFC text."""

from __future__ import annotations

import re
import unicodedata
from dataclasses import asdict
from typing import Any

import pytest
from hypothesis import example, given, settings
from hypothesis import strategies as st

from docproc.kinds.chunk.kind import BUILTINS
from docproc.kinds.chunk.tokens import Tokenizer
from docproc.sdk.models import Unit, canonical
from tests.pack import metadata


@pytest.mark.parametrize("strategy", list(BUILTINS))
@settings(max_examples=200, derandomize=True, deadline=None)
@example(raw="😤é é\n\n" * 60, tokenizer_name="cl100k_base", limit=8)
@given(
    raw=st.text(alphabet=st.characters(exclude_categories=["Cs"]), max_size=250),
    tokenizer_name=st.sampled_from(["whitespace", "cl100k_base"]),
    limit=st.integers(min_value=8, max_value=40),
)
def test_offsets_coverage_budget_and_determinism(strategy: str, raw: str, tokenizer_name: str, limit: int) -> None:
    text = unicodedata.normalize("NFC", raw)
    unit = Unit(id=5, content_text=text, metadata=metadata(headings=[], tables=[], pageBreaks=[]))
    if len(text) >= 2:
        middle = len(text) // 2
        unit.metadata = metadata(
            headings=[f"{middle}-{middle + 1}:1"], pageBreaks=[str(middle)], tables=[f"0-{len(text)}|0,{middle}"]
        )
    params: dict[str, Any] = {"tokenizer": tokenizer_name, "max_tokens": limit, "overlap": 2}
    if strategy in ("page", "paragraph", "sentence-window"):
        # These strategies have structural size units; no token cap was declared.
        params.pop("max_tokens")
        params["overlap"] = 0
    if strategy == "sentence-window":
        # The boundary algorithm consumes offsets, not NLP. Golden/component
        # tests separately use real recorded SENTENCE_SPLIT over gRPC.
        params["_sentences"] = [(m.start(), m.end()) for m in re.finditer(r"\S+(?:\s+\S+){0,2}", text)]
        params["window"] = 3
    chunks = BUILTINS[strategy](text, unit, params)
    again = BUILTINS[strategy](text, unit, params)
    assert canonical([asdict(s) for s in chunks]) == canonical([asdict(s) for s in again])
    covered: set[int] = set()
    for chunk in chunks:
        for lo, hi in chunk.ranges():
            assert 0 <= lo < hi <= len(text)
            covered.update(range(lo, hi))
        if "max_tokens" in params:
            assert Tokenizer(params).count("\n".join(text[a:b] for a, b in chunk.ranges())) <= limit or (
                strategy == "table-aware" and chunk.payload.get("oversize") is True
            )
    assert [s.start for s in chunks] == sorted(s.start for s in chunks)
    assert {n for n, c in enumerate(text) if not c.isspace()} <= covered
    if "max_tokens" in params:
        for left, right in zip(chunks, chunks[1:], strict=False):
            assert Tokenizer(params).count(text[right.start : min(left.end, right.end)]) <= 2
