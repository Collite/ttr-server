# SPDX-License-Identifier: Apache-2.0
"""The Kotlin ParagraphSplitter's blank-line/word groups, projected to original offsets."""

import re
from typing import Any

from docproc.kinds.chunk.types import ChunkSpan, UnitMeta


def chunk(text: str, unit: UnitMeta, params: dict[str, Any]) -> list[ChunkSpan]:
    min_words, max_words = int(params.get("min_words", 30)), int(params.get("max_words", 200))
    if not 1 <= min_words <= max_words:
        raise ValueError("chunk/paragraph-invalid-word-limits")
    groups = []
    offset = 0
    for part in re.split(r"\n{2,}", text):
        # locate the original paragraph, including repeated equal text safely
        start = text.find(part, offset)
        offset = start + len(part)
        words = [(start + m.start(), start + m.end()) for m in re.finditer(r"\S+", part)]
        if words:
            groups.append(words)
    merged = []
    i = 0
    while i < len(groups):
        words = list(groups[i])
        i += 1
        while len(words) < min_words and i < len(groups):
            words.extend(groups[i])
            i += 1
        for start in range(0, len(words), max_words):
            group = words[start : start + max_words]
            merged.append(ChunkSpan(group[0][0], group[-1][1]))
    return merged
