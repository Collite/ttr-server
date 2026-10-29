# SPDX-License-Identifier: Apache-2.0
"""Code-point-safe token windows, including BPE tokens that split UTF-8 characters."""

from __future__ import annotations

import hashlib
import os
import re
from functools import lru_cache
from importlib.resources import files
from pathlib import Path
from typing import Any

import tiktoken

ENCODING_FILE = "9b5ad71b2ce5302211f9c61530b329a4922fc6a4"
ENCODING_SHA256 = "223921b76ee99bde995b7ff738513eef100fb51d18c93597a113bcffe865b2a7"


@lru_cache(maxsize=1)
def encoding() -> tiktoken.Encoding:
    cache = Path(os.environ.get("TIKTOKEN_CACHE_DIR") or str(files("docproc").joinpath("_data/tiktoken")))
    path = cache / ENCODING_FILE
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != ENCODING_SHA256:
        raise ValueError("chunk/tokenizer-data-missing-or-corrupt")
    # Validate the complete local cache before allowing tiktoken's loader to run;
    # the loader finds the same verified file and has no reason to fetch a URL.
    os.environ["TIKTOKEN_CACHE_DIR"] = str(cache)
    return tiktoken.get_encoding("cl100k_base")


class Tokenizer:
    def __init__(self, params: dict[str, Any]) -> None:
        self.name = str(params.get("tokenizer", "tiktoken:cl100k_base"))
        if self.name not in ("whitespace", "cl100k_base", "tiktoken:cl100k_base"):
            raise ValueError(f"chunk/unknown-tokenizer: {self.name}")
        self.encoder = None if self.name == "whitespace" else encoding()

    def count(self, text: str) -> int:
        return (
            len(re.findall(r"\S+", text))
            if self.encoder is None
            else len(self.encoder.encode(text, disallowed_special=()))
        )

    def end(self, text: str, start: int, limit: int) -> int:
        tail = text[start:]
        if self.encoder is None:
            words = list(re.finditer(r"\S+", tail))
            return len(text) if len(words) <= limit else start + words[limit - 1].end()
        tokens = self.encoder.encode(tail, disallowed_special=())
        if len(tokens) <= limit:
            return len(text)
        raw = b"".join(self.encoder.decode_single_token_bytes(t) for t in tokens[:limit])
        end = start + len(raw.decode("utf-8", errors="ignore"))
        while end > start and self.count(text[start:end]) > limit:
            end -= 1
        if end == start:
            raise ValueError("chunk/max-tokens-too-small-for-codepoint")
        return end

    def overlap_start(self, text: str, start: int, end: int, limit: int) -> int:
        if limit <= 0:
            return end
        if self.encoder is None:
            words = list(re.finditer(r"\S+", text[start:end]))
            return start + words[max(0, len(words) - limit)].start() if words else end
        # Select a suffix that starts at a complete code point, with at most
        # the requested tokens even when the BPE token starts inside an emoji.
        tokens = self.encoder.encode(text[start:end], disallowed_special=())
        raw = b"".join(self.encoder.decode_single_token_bytes(t) for t in tokens[-limit:])
        offset = end - len(raw.decode("utf-8", errors="ignore"))
        while offset < end and self.count(text[offset:end]) > limit:
            offset += 1
        return max(start, offset)


class TokenOffsets:
    """One encoding per unit; token byte boundaries mapped to Unicode code points.

    Window lookups are logarithmic and never encode the remaining document.
    """

    def __init__(self, tokenizer: Tokenizer, text: str) -> None:
        from bisect import bisect_right

        self.text = text
        self.tokenizer = tokenizer
        self.words = list(re.finditer(r"\S+", text)) if tokenizer.encoder is None else []
        self.ends: list[int] = []
        self.starts: list[int] = []
        self.byte_ends: list[int] = []
        self.char_bytes: list[int] = []
        if tokenizer.encoder is not None:
            # Byte/code-point boundaries are monotone, including split UTF-8 tokens.
            char_bytes = [0]
            for char in text:
                char_bytes.append(char_bytes[-1] + len(char.encode("utf-8")))
            self.char_bytes = char_bytes
            offset = 0
            for token in tokenizer.encoder.encode(text, disallowed_special=()):
                self.starts.append(bisect_right(char_bytes, offset) - 1)
                offset += len(tokenizer.encoder.decode_single_token_bytes(token))
                self.byte_ends.append(offset)
                self.ends.append(bisect_right(char_bytes, offset) - 1)
        else:
            self.starts = [w.start() for w in self.words]
            self.ends = [w.end() for w in self.words]

    def end(self, start: int, limit: int) -> int:
        from bisect import bisect_right

        first = (
            bisect_right(self.byte_ends, self.char_bytes[start]) if self.byte_ends else bisect_right(self.ends, start)
        )
        if first + limit >= len(self.ends):
            return len(self.text)
        end = self.ends[first + limit - 1]
        if end <= start:
            raise ValueError("chunk/max-tokens-too-small-for-codepoint")
        return end

    def overlap_start(self, start: int, end: int, limit: int) -> int:
        from bisect import bisect_left

        if limit <= 0:
            return end
        last = bisect_left(self.starts, end)
        offset = max(start, self.starts[max(0, last - limit)])
        # A token may start inside a code point. Pick the next complete token start
        # rather than carrying an extra token into the overlap.
        if self.tokenizer.encoder is not None:
            while offset < end and self.tokenizer.count(self.text[offset:end]) > limit:
                offset += 1
        return offset
