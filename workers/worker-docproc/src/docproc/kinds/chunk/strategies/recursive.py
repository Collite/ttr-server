# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

from typing import Any

from docproc.kinds.chunk.tokens import Tokenizer, TokenOffsets
from docproc.kinds.chunk.types import ChunkSpan, UnitMeta


def trim(text: str, start: int, end: int) -> tuple[int, int]:
    while start < end and text[start].isspace():
        start += 1
    while end > start and text[end - 1].isspace():
        end -= 1
    return start, end


def windows(text: str, params: dict[str, Any], separators: list[str]) -> list[ChunkSpan]:
    tokenizer = Tokenizer(params)
    size, overlap = int(params.get("max_tokens", 400)), int(params.get("overlap", 0))
    if size < 1 or not 0 <= overlap < size:
        raise ValueError("chunk/invalid-size-or-overlap")
    offsets = TokenOffsets(tokenizer, text)
    start, stop = trim(text, 0, len(text))
    out: list[ChunkSpan] = []
    while start < stop:
        end = min(stop, offsets.end(start, size))
        if end < stop:
            for sep in separators:
                if not sep:
                    continue
                at = text.rfind(sep, start + 1, end)
                if at >= start + 1:
                    end = at + len(sep)
                    break
        lo, hi = trim(text, start, end)
        if hi > lo:
            out.append(ChunkSpan(lo, hi))
        if end >= stop:
            break
        next_start = offsets.overlap_start(lo, hi, overlap)
        # A separator can make a short chunk <= overlap; always make progress.
        start = next_start if next_start > start else end
        while start < stop and text[start].isspace():
            start += 1
    return out


def chunk(text: str, unit: UnitMeta, params: dict[str, Any]) -> list[ChunkSpan]:
    return windows(text, params, list(params.get("separators", ["\n\n", "\n", ". ", " "])))
