# SPDX-License-Identifier: Apache-2.0
from dataclasses import dataclass, field
from typing import Any

from docproc.sdk.models import Unit

UnitMeta = Unit


@dataclass(frozen=True)
class ChunkSpan:
    start: int
    end: int
    extra_spans: tuple[tuple[int, int], ...] = ()
    payload: dict[str, Any] = field(default_factory=dict)

    def ranges(self) -> tuple[tuple[int, int], ...]:
        return ((self.start, self.end), *self.extra_spans)
