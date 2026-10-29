# SPDX-License-Identifier: Apache-2.0
"""Batch postconditions shared by the kind and runtime; a failed unit is atomic."""

from __future__ import annotations

from typing import Any

from docproc.kinds.chunk.tokens import Tokenizer
from docproc.kinds.chunk.types import ChunkSpan


def failures(text: str, chunks: list[ChunkSpan], params: dict[str, Any], strategy: str) -> list[str]:
    bad: list[str] = []
    if any(not isinstance(s, ChunkSpan) for s in chunks):
        return ["span_within_unit"]
    ranges = [pair for s in chunks for pair in s.ranges()]
    if any(
        not isinstance(lo, int) or not isinstance(hi, int) or not 0 <= lo < hi <= len(text) for lo, hi in ranges
    ) or [s.start for s in chunks] != sorted(s.start for s in chunks):
        return ["span_within_unit"]
    for chunk in chunks:
        ordered = sorted(chunk.ranges())
        if any(right[0] < left[1] for left, right in zip(ordered, ordered[1:], strict=False)):
            bad.append("overlap_within_declared")
            break
    if not params.get("partial", False):
        merged: list[tuple[int, int]] = []
        for lo, hi in sorted(ranges):
            if merged and lo <= merged[-1][1]:
                merged[-1] = (merged[-1][0], max(hi, merged[-1][1]))
            else:
                merged.append((lo, hi))
        cursor = 0
        for lo, hi in [*merged, (len(text), len(text))]:
            if text[cursor:lo].strip():
                bad.append("coverage")
                break
            cursor = hi
    tokenizer = Tokenizer(params)
    for left, right in zip(chunks, chunks[1:], strict=False):
        pairs = [(max(a, c), min(b, d)) for a, b in left.ranges() for c, d in right.ranges() if max(a, c) < min(b, d)]
        if strategy == "table-aware":
            # Repeated header spans are allowed for the trusted built-in; only
            # its primary row groups are windows. Plugins get no such exemption.
            pairs = [(right.start, min(left.end, right.end))] if right.start < min(left.end, right.end) else []
        if not pairs:
            continue
        merged_overlap: list[tuple[int, int]] = []
        for lo, hi in sorted(pairs):
            if merged_overlap and lo <= merged_overlap[-1][1]:
                merged_overlap[-1] = (merged_overlap[-1][0], max(hi, merged_overlap[-1][1]))
            else:
                merged_overlap.append((lo, hi))
        if strategy == "sentence-window":
            count = sum(
                1 for a, b in params.get("_sentences", []) if any(lo <= a < b <= hi for lo, hi in merged_overlap)
            )
        else:
            count = sum(tokenizer.count(text[lo:hi]) for lo, hi in merged_overlap)
        if count > int(params.get("overlap", 0)):
            if "overlap_within_declared" not in bad:
                bad.append("overlap_within_declared")
            break
    if "max_tokens" in params:
        for s in chunks:
            count = tokenizer.count("\n".join(text[lo:hi] for lo, hi in s.ranges()))
            tolerance = (
                int(params.get("token_tolerance", 0)) if strategy in ("paragraph", "page", "sentence-window") else 0
            )
            if count > int(params["max_tokens"]) + tolerance and not (
                strategy == "table-aware" and s.payload.get("oversize") is True
            ):
                bad.append("max_tokens")
                break
    return bad
