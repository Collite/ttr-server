# SPDX-License-Identifier: Apache-2.0
"""HTTP origin parsing with an explicit policy for clickable-link hosts."""

import ipaddress
import re
from unicodedata import category
from urllib.parse import urlsplit


def http_origin(url: str, *, allow_path: bool, link_host: bool) -> str:
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
        if parsed.netloc.startswith("["):
            # urlsplit lowercases host/scheme; reject junk after IPv6 brackets.
            suffix = parsed.netloc.partition("]")[2]
            if suffix not in {"", f":{port}"}:
                raise ValueError("invalid IPv6 authority")
            host = f"[{ipaddress.IPv6Address(host)}]"
        else:
            host = host.removesuffix(".")
            labels = host.split(".")
            if link_host and (
                len(host) > 253
                or any(
                    not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label)
                    for label in labels
                )
            ):
                raise ValueError("invalid DNS labels")
            if link_host and (labels[-1].isdigit() or re.fullmatch(r"0x[0-9a-f]*", labels[-1])):
                host = str(ipaddress.IPv4Address(host))
    except ValueError as exc:
        raise ValueError("URL has an invalid host or port") from exc
    default_port = 443 if parsed.scheme == "https" else 80
    authority = host if port in {None, default_port} else f"{host}:{port}"
    return f"{parsed.scheme}://{authority}"


STUDIO_WORKSPACE = re.compile(r"[A-Za-z0-9._~-]+")


def validate_studio_workspace(value: str) -> str:
    if not STUDIO_WORKSPACE.fullmatch(value) or value in {".", "..", "c", "_designer"}:
        raise ValueError("invalid Studio workspace (use modeler.toml project.name)")
    return value


def validate_studio_base(value: str) -> str:
    """Require a canonical link origin and exactly one valid model workspace."""
    match = re.fullmatch(r"(https?://[^/]+)/m/([^/]+)/s/", value)
    if match is None:
        raise ValueError("invalid prepared Studio base: expected <origin>/m/<workspace>/s/")
    origin, workspace = match.groups()
    if http_origin(origin, allow_path=False, link_host=True) != origin:
        raise ValueError("invalid prepared Studio base: origin must be canonical")
    validate_studio_workspace(workspace)
    return value
