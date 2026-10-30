# SPDX-License-Identifier: Apache-2.0
from typing import Any

from docproc.kinds.chunk.types import ChunkSpan, UnitMeta


class Halves:
    name = 'halves'
    def chunk(self, text: str, unit: UnitMeta, params: dict[str, Any]) -> list[ChunkSpan]:
        middle = len(text) // 2
        return [ChunkSpan(0, middle), ChunkSpan(middle, len(text))]
