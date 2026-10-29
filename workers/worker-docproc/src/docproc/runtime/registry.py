# SPDX-License-Identifier: Apache-2.0
"""Kind dispatch and schema compilation from the shared registry, never Python copies."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from importlib.resources import files
from pathlib import Path
from typing import Any, Protocol

import yaml
from jsonschema import Draft202012Validator, FormatChecker

from docproc.sdk.models import Annotation, Processor, Unit, Usage, WorkItem
from docproc.sdk.pipeline import Manager


@dataclass(frozen=True)
class RunContext:
    item: WorkItem
    processor: Processor | None
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


class KindRefused(ValueError):
    """A kind refused all writes; diagnostics contain only annotation ids and rule codes."""

    def __init__(self, code: str, diagnostics: list[dict[str, Any]]) -> None:
        super().__init__(f"{code}: {len(diagnostics)} records")
        self.diagnostics = diagnostics


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
        if hosted and not {"timeout_s", "retries"} <= manifest.get("budget", {}).keys():
            raise ValueError("manifest/budget-required")
        if manifest["kind"] == "link" and manifest["engine"].get("pick") != "rule" and hosted:
            raise UnsupportedKind("link/pick-not-in-this-release")
        if manifest["kind"] == "rule" and "function" in manifest["engine"] and hosted:
            from docproc.kinds.rule_fns import registered

            if not registered(manifest["engine"]["function"]):
                raise UnsupportedKind("rule/function-unregistered")
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


def schema_formats(value: Any) -> set[str]:
    if isinstance(value, dict):
        return ({value["format"]} if isinstance(value.get("format"), str) else set()) | set().union(
            *(schema_formats(v) for v in value.values())
        )
    if isinstance(value, list):
        return set().union(*(schema_formats(v) for v in value))
    return set()


class TypeRegistryClient:
    def __init__(self, manager: Manager, pack_path: str = "") -> None:
        self.manager = manager
        self.pack_path = Path(pack_path) if pack_path else None
        self.version = ""
        self.entries: dict[str, dict[str, Any]] = {}
        self._schemas: dict[str, Draft202012Validator] = {}
        self.loaded = False
        self.pack_verified = False

    def verify_pack(self) -> None:
        if self.pack_path and not self.pack_verified:
            from docproc.runtime.model_hash import verify_pack

            verify_pack(self.pack_path)
            self.pack_verified = True

    def load(self, version: str) -> None:
        if version == self.version and self.loaded:
            return
        registry = self.manager.registry(version)
        if str(registry.get("version", "")) != version:
            raise ValueError("registry/version-mismatch")
        self.verify_pack()
        expected: dict[str, Any] = {}
        if self.pack_path:
            index = json.loads((self.pack_path / "types/generated/index.json").read_text())
            expected = {
                e["key"]: json.loads((self.pack_path / "types/generated" / e["file"]).read_text())
                for e in index["types"]
            }
        compiled: dict[str, Draft202012Validator] = {}
        for entry in registry["types"]:
            schema = entry.get("schemaJson", entry.get("schema"))
            if isinstance(schema, str):
                schema = json.loads(schema)
            if entry["key"] in expected and schema != expected[entry["key"]]:
                raise ValueError("registry/manager-schema-drift")
            Draft202012Validator.check_schema(schema)
            checker = FormatChecker()
            for format_ in schema_formats(schema):
                if format_ not in checker.checkers:
                    raise ValueError(f"registry/format-unchecked: {format_}")
            compiled[entry["key"]] = Draft202012Validator(schema, format_checker=checker)
        if expected.keys() - compiled.keys():
            raise ValueError("registry/model-types-missing")
        if not compiled:
            raise ValueError("empty type registry")
        self._schemas = compiled
        self.entries = {e["key"]: e for e in registry["types"]}
        self.version = version
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
