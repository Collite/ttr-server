# SPDX-License-Identifier: Apache-2.0
from typing import Any

from docproc.kinds.chunk.strategies.recursive import windows
from docproc.kinds.chunk.types import ChunkSpan, UnitMeta


def chunk(text: str, unit: UnitMeta, params: dict[str, Any]) -> list[ChunkSpan]:
    return windows(text, params, [])
