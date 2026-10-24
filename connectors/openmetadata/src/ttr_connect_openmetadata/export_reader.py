# SPDX-License-Identifier: Apache-2.0
"""Read one consistent projection snapshot without touching OpenMetadata."""

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol
from urllib.parse import urlsplit, urlunsplit

import requests
from rdflib import Graph as RdfGraph

from .model import Definitions, Export, Graph
from .state import State


class ExportError(Exception):
    """Unreachable, malformed or inconsistent export (C-14 exit 2)."""


@dataclass(frozen=True)
class NotModified:
    snapshot: str


class ExportSource(Protocol):
    def read(self, state: State | None = None) -> Export | NotModified: ...


def _export(graph: str, defs: str, ttl: str, snapshot: str) -> Export:
    try:
        result = Export(
            graph=Graph.model_validate_json(graph),
            defs=Definitions.model_validate_json(defs),
            ttl=ttl,
            snapshot=snapshot,
        )
        # Turtle parsing is local; unlike JSON-LD it does not fetch contexts.
        RdfGraph().parse(data=ttl, format="turtle")
        return result
    except Exception as exc:
        raise ExportError(f"invalid export: {exc}") from exc


@dataclass(frozen=True)
class DirSource:
    path: Path

    def read(self, state: State | None = None) -> Export:
        try:
            before = (self.path / ".snapshot").read_text(encoding="utf-8").strip()
            result = _export(
                (self.path / "graph.json").read_text(encoding="utf-8"),
                (self.path / "defs.json").read_text(encoding="utf-8"),
                (self.path / "cn.ttl").read_text(encoding="utf-8"),
                before,
            )
            if (self.path / ".snapshot").read_text(encoding="utf-8").strip() != before:
                raise ExportError("export directory changed while reading")
            return result
        except (OSError, UnicodeError) as exc:
            raise ExportError(f"cannot read export directory: {exc}") from exc


def _etag(value: str | None) -> str:
    if not value:
        raise ExportError("export response has no ETag")
    if value.startswith("W/"):
        value = value[2:]
    return value.strip('"')


@dataclass(frozen=True)
class UrlSource:
    # Full route URL, e.g. https://metadata.example/model/export.
    base_url: str
    timeout: float = 30

    def read(self, state: State | None = None) -> Export | NotModified:
        parts = urlsplit(self.base_url)
        if (
            parts.scheme not in {"http", "https"}
            or not parts.netloc
            or parts.query
            or parts.fragment
        ):
            raise ExportError("export URL must be an HTTP(S) route without query or fragment")
        route = urlunsplit(parts)
        projections = [
            {"model": "world", "format": "graph.json"},
            {"model": "world", "format": "defs.json"},
            {"model": "cn", "format": "ttl", "profile": "owl"},
        ]
        bodies: list[str] = []
        versions: list[str] = []
        try:
            for params in projections:
                headers = {"If-None-Match": state.snapshot} if state else {}
                response = requests.get(route, params=params, headers=headers, timeout=self.timeout)
                if response.status_code == 304:
                    if state is None or bodies:
                        raise ExportError("304 without usable state or during a changed export")
                    if (
                        response.headers.get("ETag")
                        and _etag(response.headers["ETag"]) != state.snapshot
                    ):
                        raise ExportError("304 ETag differs from saved state")
                    return NotModified(state.snapshot)
                response.raise_for_status()
                versions.append(_etag(response.headers.get("ETag")))
                response.encoding = "utf-8"
                bodies.append(response.text)
        except requests.RequestException as exc:
            raise ExportError(f"cannot fetch export: {exc}") from exc
        if len(set(versions)) != 1:
            raise ExportError("export changed between projection requests; retry")
        return _export(bodies[0], bodies[1], bodies[2], versions[0])
