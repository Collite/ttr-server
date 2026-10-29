# SPDX-License-Identifier: Apache-2.0
"""Immutable processor and registry reads (no Kantheon proto dependency)."""

from __future__ import annotations

from typing import Any, Protocol
from urllib.parse import quote

from docproc.sdk.models import Annotation, Processor
from docproc.sdk.store import StoreClient


class Manager(Protocol):
    def processor(self, family: str, version_hash: str) -> Processor: ...
    def registry(self, version: str) -> dict[str, Any]: ...
    def hosted_processors(self) -> list[Processor]: ...
    def bundle(self, bundle_ref: str) -> list[Annotation]: ...


class PipelineClient(StoreClient):
    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self._processors: dict[tuple[str, str], Processor] = {}
        self._registries: dict[str, dict[str, Any]] = {}

    def processor(self, family: str, version_hash: str) -> Processor:
        key = (family, version_hash)
        if key not in self._processors:
            result = Processor.model_validate(
                self._ok(
                    self._http.get(f"/v1/processors/{quote(family, safe='')}", params={"versionHash": version_hash})
                )
            )
            if (result.family, result.version_hash) != key:
                raise ValueError("processor identity does not match the requested immutable hash")
            self._processors[key] = result
        return self._processors[key].model_copy(deep=True)

    def registry(self, version: str) -> dict[str, Any]:
        import copy

        if version not in self._registries:
            self._registries[version] = self._ok(self._http.get(f"/v1/registry/{quote(version, safe='')}"))
        return copy.deepcopy(self._registries[version])

    def hosted_processors(self) -> list[Processor]:
        body = self._ok(self._http.get("/v1/processors"))
        return [Processor.model_validate(p) for p in body["processors"] if p.get("host") == "docproc"]

    def bundle(self, bundle_ref: str) -> list[Annotation]:
        body = self._ok(self._http.get(f"/v1/bundles/{quote(bundle_ref, safe='')}"))
        return [Annotation.model_validate(a) for a in body["annotations"]]
