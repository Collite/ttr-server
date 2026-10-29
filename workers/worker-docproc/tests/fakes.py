# SPDX-License-Identifier: Apache-2.0
"""Contract fakes; fixture manifests/schemas are the only production-shaped data."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import yaml

from docproc.runtime.validators import span_within_unit
from docproc.sdk.models import (
    Annotation,
    ListAnnotationsPage,
    ListAnnotationsRequest,
    LoadAnnotationsRequest,
    LoadAnnotationsResponse,
    Processor,
    Unit,
)
from tests.conftest import WORKER

PACK = WORKER / "tests/fixtures/packs/hartland-mini"


class FakeWarehouse:
    def __init__(self, units: list[Unit]) -> None:
        self.units = {u.id: u for u in units}
        self.annotations: list[Annotation] = []
        self.lemmas: dict[int, dict[str, Any]] = {}
        self.loads = 0
        self.next_id = 1000
        self.visibility: dict[str, Any] = {}
        self.generations: dict[str, str] = {}

    def get_unit(self, unit_id: int, notebook_id: str = "") -> Unit:
        return self.units[unit_id].model_copy(deep=True)

    def units_of_source(self, source_id: int, text: bool = True) -> list[Unit]:
        out = [u for u in self.units.values() if u.source_id == source_id]
        return sorted(out, key=lambda u: (u.parent_id is not None, u.idx))

    def iter_annotations(self, req: ListAnnotationsRequest) -> Iterator[Annotation]:
        for a in self.annotations:
            if req.unit_id is not None and a.unit_id != req.unit_id:
                continue
            if req.run_id is not None and a.run_id != req.run_id:
                continue
            if req.types and a.type not in req.types:
                continue
            if req.active_only is not False and self.visibility.get(a.run_id, "ACTIVE") != "ACTIVE":
                if not req.generation or self.generations.get(a.run_id) != req.generation:
                    continue
            yield a.model_copy(deep=True)

    def list_annotations(self, req: ListAnnotationsRequest) -> ListAnnotationsPage:
        return ListAnnotationsPage(annotations=list(self.iter_annotations(req)))

    def load_annotations(self, req: LoadAnnotationsRequest) -> LoadAnnotationsResponse:
        if any(a.run_id != req.run_id for a in req.annotations):
            raise ValueError("one run per batch")
        for annotation in req.annotations:
            span_within_unit(annotation, self.units)
        if any(a.run_id == req.run_id for a in self.annotations):
            raise ValueError("run already loaded")
        self.loads += 1
        self.visibility[req.run_id] = req.visibility
        if req.generation:
            self.generations[req.run_id] = req.generation
        ids = []
        for a in req.annotations:
            self.next_id += 1
            ids.append(self.next_id)
            self.annotations.append(a.model_copy(update={"id": self.next_id}, deep=True))
        return LoadAnnotationsResponse(ids=ids)

    def load_lemmas(self, run_id: str, lemmas: list[dict[str, Any]]) -> None:
        self.lemmas.update({entry["unitId"]: entry for entry in lemmas})

    def find_similar(self, request: dict[str, Any]) -> dict[str, Any]:
        return {"hits": []}

    def sources_of_document(self, document_id: str) -> list[dict[str, Any]]:
        return []


class PackManager:
    def __init__(self, pack: Path = PACK) -> None:
        self.pack = pack
        self.processors: dict[tuple[str, str], Processor] = {}
        for path in sorted((pack / "processors").glob("*.yaml")):
            text = path.read_text()
            manifest = yaml.safe_load(text)
            family, version = manifest["family"], str(manifest["version"])
            digest = "sha256:" + hashlib.sha256(f"{family}\0{version}".encode()).hexdigest()
            p = Processor(
                family=family,
                version=version,
                version_hash=digest,
                kind=manifest["kind"],
                host=manifest["host"],
                role="ROLE_" + manifest["role"].upper(),
                manifest_yaml=text,
            )
            self.processors[(family, digest)] = p
        self.extra_types: list[dict[str, Any]] = []  # test-only schemas, pending P1's engine fixtures

    def processor(self, family: str, version_hash: str) -> Processor:
        return self.processors[(family, version_hash)]

    def hosted_processors(self) -> list[Processor]:
        return [p for p in self.processors.values() if p.host == "docproc"]

    def registry(self, version: str) -> dict[str, Any]:
        entries = []
        for path in sorted((self.pack / "types/generated").glob("*.schema.json")):
            schema = json.loads(path.read_text())
            stem = path.name.removesuffix(".schema.json")
            key = "Statement[" + stem.split(".", 1)[1] + "]" if stem.startswith("Statement.") else stem
            entries.append({"key": key, "schemaJson": json.dumps(schema)})
        engine_root = WORKER / "tests/fixtures/types"
        if (engine_root / "engine.yaml").exists():
            engine = yaml.safe_load((engine_root / "engine.yaml").read_text())
            for entry in engine["types"]:
                entries.append({"key": entry["key"], "schemaJson": (engine_root / entry["schema"]).read_text()})
        return {"version": version, "types": entries + self.extra_types}
