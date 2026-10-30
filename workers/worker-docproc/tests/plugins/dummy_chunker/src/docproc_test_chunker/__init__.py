# SPDX-License-Identifier: Apache-2.0
"""Installed entry-point fixtures, imported only by plugin tests."""
from typing import Any


class Whole:
    name = 'whole'
    def chunk(self, text: str, unit: Any, params: dict[str, Any]) -> Any:
        from docproc.kinds.chunk.types import ChunkSpan
        return [ChunkSpan(0, len(text))] if text.strip() else []


class Raises(Whole):
    def chunk(self, text: str, unit: Any, params: dict[str, Any]) -> Any:
        raise RuntimeError('broken fixture')


class Overlap(Whole):
    def chunk(self, text: str, unit: Any, params: dict[str, Any]) -> Any:
        from docproc.kinds.chunk.types import ChunkSpan
        return [ChunkSpan(0, len(text)), ChunkSpan(0, len(text))]


class Outside(Whole):
    def chunk(self, text: str, unit: Any, params: dict[str, Any]) -> Any:
        from docproc.kinds.chunk.types import ChunkSpan
        return [ChunkSpan(-1, len(text) + 1)]
