# SPDX-License-Identifier: Apache-2.0
"""Shared strict HTTP origin parsing; callers never construct links from raw input."""

from unicodedata import category
from urllib.parse import urlsplit


def http_origin(url: str, *, allow_path: bool) -> str:
    if any(c in "?#\\" or c.isspace() or category(c).startswith("C") for c in url):
        raise ValueError(
            "URL contains a query, fragment, backslash, whitespace or control character"
        )
    parsed = urlsplit(url)
    # Accessing port also validates non-numeric and out-of-range port values.
    port = parsed.port
    host = parsed.hostname
    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.netloc
        or not host
        or not host.isascii()
        or "%" in parsed.netloc
        or parsed.netloc.endswith(":")
        or port == 0
        or parsed.username is not None
        or parsed.password is not None
        or (not allow_path and parsed.path not in {"", "/"})
    ):
        raise ValueError("URL must be an ASCII HTTP(S) origin with a valid host and port")
    host = host.lower()
    if ":" in host:  # Preserve IPv6 brackets when rebuilding the authority.
        host = f"[{host}]"
    default_port = 443 if parsed.scheme == "https" else 80
    authority = host if port in {None, default_port} else f"{host}:{port}"
    return f"{parsed.scheme.lower()}://{authority}"
