# SPDX-License-Identifier: Apache-2.0
"""Shared strict HTTP origin parsing; callers never construct links from raw input."""

from unicodedata import category
from urllib.parse import urlsplit


def http_origin(url: str, *, allow_path: bool) -> str:
    if any(c in "?#\\" or c.isspace() or category(c) == "Cc" for c in url):
        raise ValueError(
            "URL contains a query, fragment, backslash, whitespace or control character"
        )
    parsed = urlsplit(url)
    # Accessing port also validates non-numeric and out-of-range port values.
    _ = parsed.port
    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.netloc
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or (not allow_path and parsed.path not in {"", "/"})
    ):
        raise ValueError("URL must be an HTTP(S) origin without credentials or an unsupported path")
    return f"{parsed.scheme}://{parsed.netloc}"
