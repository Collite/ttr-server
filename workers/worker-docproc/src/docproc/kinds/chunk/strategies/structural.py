# SPDX-License-Identifier: Apache-2.0
from typing import Any

from docproc.kinds.chunk.strategies.recursive import chunk as recursive
from docproc.kinds.chunk.types import ChunkSpan, UnitMeta
from docproc.kinds.common import meta_list


def chunk(text: str, unit: UnitMeta, params: dict[str, Any]) -> list[ChunkSpan]:
    starts = []
    for heading in meta_list(unit, "headings"):
        span, level = heading.split(":")
        lo, hi = map(int, span.split("-"))
        if not 0 <= lo < hi <= len(text) or int(level) < 1:
            raise ValueError("chunk/structural-invalid-heading")
        starts.append(lo)
    cuts = sorted(set([0, *starts, len(text)]))
    return [
        ChunkSpan(s.start + lo, s.end + lo)
        for lo, hi in zip(cuts, cuts[1:], strict=False)
        for s in recursive(text[lo:hi], unit, params)
    ]
