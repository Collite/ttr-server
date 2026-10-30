# SPDX-License-Identifier: Apache-2.0
from typing import Any

from docproc.kinds.chunk.types import ChunkSpan, UnitMeta


def chunk(text: str, unit: UnitMeta, params: dict[str, Any]) -> list[ChunkSpan]:
    size, overlap = int(params.get("n", params.get("window", 3))), int(params.get("overlap", 0))
    if not size > overlap >= 0:
        raise ValueError("chunk/sentence-invalid-window")
    sentences: list[tuple[int, int]] = params["_sentences"]
    out = []
    start = 0
    while start < len(sentences):
        group = sentences[start : start + size]
        out.append(ChunkSpan(group[0][0], group[-1][1]))
        if start + size >= len(sentences):
            break
        start += size - overlap
    return out
