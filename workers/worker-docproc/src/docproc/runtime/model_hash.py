# SPDX-License-Identifier: Apache-2.0
"""ModelContentHash's byte-level twin and the generated-registry integrity gate."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path


def model_content_hash(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(
        (p for p in root.rglob("*") if p.is_file() and p.suffix in (".ttr", ".ttrm")),
        key=lambda p: p.relative_to(root).as_posix(),
    ):
        if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
            raise ValueError("registry/model-outside-pack")
        digest.update(path.relative_to(root).as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return "sha256:" + digest.hexdigest()


def verify_pack(pack: Path) -> None:
    root = pack / "types/generated"
    index = json.loads((root / "index.json").read_text())
    if index["snapshotHash"] != model_content_hash(pack / "model"):
        raise ValueError("registry/model-drift")
    listed = set()
    keys = set()
    for entry in index["types"]:
        path = root / entry["file"]
        if path.parent != root or path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
            raise ValueError("registry/generated-outside-pack")
        if entry["file"] in listed or entry["key"] in keys:
            raise ValueError("registry/generated-duplicate")
        listed.add(entry["file"])
        keys.add(entry["key"])
        if hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
            raise ValueError("registry/generated-drift")
    if listed != {p.name for p in root.glob("*.schema.json")}:
        raise ValueError("registry/generated-unlisted")
