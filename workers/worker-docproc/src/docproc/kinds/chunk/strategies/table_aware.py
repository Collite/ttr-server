# SPDX-License-Identifier: Apache-2.0
"""Whole rows; repeated header is a second span, never duplicated text."""

from typing import Any

from docproc.kinds.chunk.strategies.recursive import chunk as recursive
from docproc.kinds.chunk.tokens import Tokenizer
from docproc.kinds.chunk.types import ChunkSpan, UnitMeta
from docproc.kinds.common import meta_list


def chunk(text: str, unit: UnitMeta, params: dict[str, Any]) -> list[ChunkSpan]:
    tokenizer, limit = Tokenizer(params), int(params.get("max_tokens", 400))
    if limit < 1:
        raise ValueError("chunk/invalid-size-or-overlap")
    out: list[ChunkSpan] = []
    cursor = 0
    for table in meta_list(unit, "tables"):
        extent, row_text = table.split("|")
        lo, hi = map(int, extent.split("-"))
        starts = [int(value) for value in row_text.split(",") if value]
        if not (
            cursor <= lo < hi <= len(text)
            and starts
            and starts[0] == lo
            and starts == sorted(set(starts))
            and all(lo <= s < hi for s in starts)
        ):
            raise ValueError("chunk/table-invalid-offsets")
        out.extend(ChunkSpan(s.start + cursor, s.end + cursor) for s in recursive(text[cursor:lo], unit, params))
        if tokenizer.count(text[lo:hi]) <= limit:
            out.append(ChunkSpan(lo, hi))
        else:
            ends = [*starts[1:], hi]
            header = (lo, ends[0])
            first = True
            index = 0
            while index < len(starts):
                begin = index
                end = ends[index]
                extra = () if first else (header,)
                group_start = starts[begin]

                def count(stop: int, group_start: int = group_start, extra: tuple[tuple[int, int], ...] = extra) -> int:
                    return tokenizer.count("\n".join([text[group_start:stop], *[text[a:b] for a, b in extra]]))

                while index + 1 < len(starts) and count(ends[index + 1]) <= limit:
                    index += 1
                    end = ends[index]
                oversize = count(end) > limit
                out.append(ChunkSpan(starts[begin], end, extra, {"oversize": True} if oversize else {}))
                first = False
                index += 1
        cursor = hi
    out.extend(ChunkSpan(s.start + cursor, s.end + cursor) for s in recursive(text[cursor:], unit, params))
    return out
