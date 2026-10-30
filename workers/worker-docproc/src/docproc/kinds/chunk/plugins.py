# SPDX-License-Identifier: Apache-2.0
"""Reviewed pack code and installed chunker entry points; both run in process."""

from __future__ import annotations

import hashlib
import importlib.util
from importlib.metadata import entry_points
from pathlib import Path
from typing import Any, Protocol, cast

from docproc.kinds.chunk.types import ChunkSpan, UnitMeta


class Chunker(Protocol):
    name: str

    def chunk(self, text: str, unit: UnitMeta, params: dict[str, Any]) -> list[ChunkSpan]: ...


def load(strategy: str, pack_path: str) -> Chunker:
    if strategy.startswith("plugin:"):
        name = strategy.removeprefix("plugin:")
        matches = list(entry_points(group="docproc.chunkers", name=name))
        if len(matches) != 1:
            raise ValueError(f"chunk/plugin-not-unique: {name}")
        factory = matches[0].load()
    elif strategy.startswith("pack:"):
        if not pack_path:
            raise ValueError("chunk/pack-path-required")
        filename, class_name = strategy.removeprefix("pack:").split("#", 1)
        root = Path(pack_path).resolve()
        path = (root / filename).resolve()
        if not filename.startswith("chunkers/") or not path.is_relative_to(root) or path.suffix != ".py":
            raise ValueError("chunk/plugin-outside-pack")
        name = "docproc_pack_" + hashlib.sha256(path.read_bytes()).hexdigest()
        spec = importlib.util.spec_from_file_location(name, path)
        if spec is None or spec.loader is None:
            raise ValueError("chunk/plugin-unloadable")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        factory = getattr(module, class_name)
    else:
        raise ValueError(f"chunk/unknown-strategy: {strategy}")
    instance = factory()
    if not isinstance(instance.name, str) or not callable(instance.chunk):
        raise ValueError("chunk/plugin-invalid-protocol")
    return cast(Chunker, instance)
