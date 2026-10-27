# SPDX-License-Identifier: Apache-2.0
"""Shared strict HTTP origin parsing; callers never construct links from raw input."""

import ipaddress
import re
from unicodedata import category
from urllib.parse import urlsplit


def http_origin(url: str, *, allow_path: bool) -> str:
    if any(c in "?#\\" or c.isspace() or category(c).startswith("C") for c in url):
        raise ValueError(
            "URL contains a query, fragment, backslash, whitespace or control character"
        )
    try:
        parsed = urlsplit(url)
    except ValueError as exc:
        raise ValueError("URL has an invalid host or port") from exc
    if parsed.scheme not in {"http", "https"}:
        raise ValueError("URL scheme must be HTTP or HTTPS")
    if parsed.username is not None or parsed.password is not None:
        raise ValueError("URL must not contain credentials")
    if not allow_path and parsed.path not in {"", "/"}:
        raise ValueError("URL must be an origin without a path")
    try:
        port = parsed.port
        host = parsed.hostname
        if (
            not parsed.netloc
            or not host
            or not host.isascii()
            or "%" in parsed.netloc
            or parsed.netloc.endswith(":")
            or port == 0
        ):
            raise ValueError("invalid authority")
        if ":" in host:
            # urlsplit lowercases host/scheme; reject junk after IPv6 brackets.
            suffix = parsed.netloc.partition("]")[2]
            if not parsed.netloc.startswith("[") or suffix not in {"", f":{port}"}:
                raise ValueError("invalid IPv6 authority")
            host = f"[{ipaddress.IPv6Address(host)}]"
        else:
            host = host.removesuffix(".")
            labels = host.split(".")
            if len(host) > 253 or any(
                not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label) for label in labels
            ):
                raise ValueError("invalid DNS labels")
            if all(label.isdigit() for label in labels):
                host = str(ipaddress.IPv4Address(host))
    except ValueError as exc:
        raise ValueError("URL has an invalid host or port") from exc
    default_port = 443 if parsed.scheme == "https" else 80
    authority = host if port in {None, default_port} else f"{host}:{port}"
    return f"{parsed.scheme}://{authority}"
