# SPDX-License-Identifier: Apache-2.0
from typing import Any

from docproc.kinds.chunk.strategies.recursive import trim
from docproc.kinds.chunk.types import ChunkSpan, UnitMeta
from docproc.kinds.common import meta_list


def chunk(text: str, unit: UnitMeta, params: dict[str, Any]) -> list[ChunkSpan]:
    cuts = [0, *[int(value) for value in meta_list(unit, "pageBreaks")], len(text)]
    if cuts != sorted(cuts) or any(not 0 <= n <= len(text) for n in cuts):
        raise ValueError("chunk/page-invalid-offsets")
    ranges = [trim(text, lo, hi) for lo, hi in zip(cuts, cuts[1:], strict=False)]
    return [ChunkSpan(lo, hi) for lo, hi in ranges if lo < hi]
