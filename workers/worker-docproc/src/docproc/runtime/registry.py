# SPDX-License-Identifier: Apache-2.0
"""Kind dispatch and schema compilation from the shared registry, never Python copies."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from importlib.resources import files
from typing import Any, Protocol

import yaml
from jsonschema import Draft202012Validator

from docproc.sdk.models import Annotation, Processor, Unit, Usage, WorkItem
from docproc.sdk.pipeline import Manager


@dataclass(frozen=True)
class RunContext:
    item: WorkItem
    processor: Processor
    manifest: dict[str, Any]
    units: dict[int, Unit]
    annotations: list[Annotation]


@dataclass
class KindResult:
    annotations: list[Annotation] = field(default_factory=list)
    child_unit_ids: list[int] = field(default_factory=list)
    verdicts: list[Annotation] = field(default_factory=list)
    usage: Usage = field(default_factory=Usage)
    sections: dict[str, Any] = field(default_factory=dict)
    abstained: bool = False
    chunk_params: dict[int, dict[str, Any]] = field(default_factory=dict)


class Kind(Protocol):
    def run(self, ctx: RunContext) -> KindResult: ...


class UnsupportedKind(ValueError):
    pass


class KindRegistry:
    def __init__(self) -> None:
        self.kinds: dict[str, Kind] = {}
        schema = json.loads(files("docproc").joinpath("_data/schemas/processor-manifest.schema.json").read_text())
        self._manifest = Draft202012Validator(schema)

    def register(self, name: str, kind: Kind) -> None:
        if name in self.kinds:
            raise ValueError(f"kind already registered: {name}")
        self.kinds[name] = kind

    def manifest(self, processor: Processor, hosted: bool = True) -> dict[str, Any]:
        manifest: dict[str, Any] = yaml.safe_load(processor.manifest_yaml)
        if (
            isinstance(manifest, dict)
            and manifest.get("kind") == "chunk"
            and isinstance(manifest.get("engine"), dict)
            and manifest["engine"].get("strategy") in ("semantic", "summary-prefix")
        ):
            raise UnsupportedKind("chunk/strategy-not-in-this-release")
        self._manifest.validate(manifest)
        if (manifest["family"], str(manifest["version"]), manifest["kind"], manifest["host"]) != (
            processor.family,
            processor.version,
            processor.kind,
            processor.host,
        ):
            raise ValueError("manifest identity differs from processor")
        if hosted and (manifest["host"] != "docproc" or manifest["kind"] not in self.kinds):
            raise UnsupportedKind(f"kind/unsupported: {manifest['host']}/{manifest['kind']}")
        return manifest


class TypeRegistryClient:
    def __init__(self, manager: Manager) -> None:
        self.manager = manager
        self._schemas: dict[str, Draft202012Validator] = {}
        self.loaded = False

    def load(self, version: str) -> None:
        registry = self.manager.registry(version)
        compiled: dict[str, Draft202012Validator] = {}
        for entry in registry["types"]:
            schema = entry.get("schemaJson", entry.get("schema"))
            if isinstance(schema, str):
                schema = json.loads(schema)
            Draft202012Validator.check_schema(schema)
            compiled[entry["key"]] = Draft202012Validator(schema)
        if not compiled:
            raise ValueError("empty type registry")
        self._schemas = compiled
        self.loaded = True

    def schema_for(self, key: str) -> Draft202012Validator:
        # Exact model keys such as Statement[review] precede selector/base resolution.
        if key in self._schemas:
            return self._schemas[key]
        base = re.sub(r"\[.*\]$", "", key)
        if base.startswith("Import."):
            base = "Import"
        return self._schemas[base]

    def selector(self, key: str) -> tuple[str, str | None]:
        if key in self._schemas:
            return key, None
        match = re.fullmatch(r"(.+)\[([^]]+)\]", key)
        return (match[1], match[2]) if match else (key, None)
