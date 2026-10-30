# SPDX-License-Identifier: Apache-2.0
"""BPE windows must satisfy the postconditions after trimming and separator cuts."""

from __future__ import annotations

import random

import pytest

from docproc.kinds.chunk.checks import failures
from docproc.kinds.chunk.strategies.recursive import windows
from tests.fakes import PACK

SEPARATORS = ["\n\n", "\n", ". ", " "]


@pytest.mark.parametrize(
    ("text", "size", "overlap"),
    [
        ("**R0 (CZK, frozen 2026-07-18):**", 8, 0),
        ('## 0. The meta-quirk — golem masks query failures as "0 rows"', 16, 4),
    ],
    ids=["R1-emitted-span", "R2-trimmed-overlap"],
)
def test_token_window_repros(text: str, size: int, overlap: int) -> None:
    params = {"max_tokens": size, "overlap": overlap}
    chunks = windows(text, params, [])
    assert chunks
    assert failures(text, chunks, params, "fixed-tokens") == []


@pytest.fixture(scope="module")
def window_corpus() -> list[tuple[str, str]]:
    texts = [(p.name, p.read_text()) for p in sorted((PACK / "documents").iterdir()) if p.is_file()]
    assert len(texts) == 6
    rng = random.Random(3)
    alphabet = list("abcdefghij klmnop ěščřžýáíé 0123456789 .,;:!?\n\n") + [
        "—",
        "**",
        "2026-07-18T12:34:56+02:00",
        "👍",
        "🇨🇿",
        "日本",
        "  ",
        "\u00a0",
    ]
    texts.extend(
        (f"unicode-{i}", "".join(rng.choice(alphabet) for _ in range(rng.randint(200, 3000)))) for i in range(60)
    )
    return texts


@pytest.mark.parametrize("strategy", ["fixed-tokens", "recursive"])
@pytest.mark.parametrize("size,overlap", [(400, 0), (200, 0), (16, 0), (8, 0), (400, 50), (200, 20), (16, 4), (4, 1)])
def test_token_window_corpus(window_corpus: list[tuple[str, str]], strategy: str, size: int, overlap: int) -> None:
    params = {"max_tokens": size, "overlap": overlap}
    for name, text in window_corpus:
        chunks = windows(text, params, SEPARATORS if strategy == "recursive" else [])
        assert chunks, name
        assert failures(text, chunks, params, strategy) == [], name
