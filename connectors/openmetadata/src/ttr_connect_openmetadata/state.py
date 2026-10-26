# SPDX-License-Identifier: Apache-2.0
"""Successful-sync state. Reading/plan never advances it."""

import hashlib
import json
import os
import tempfile
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field


class State(BaseModel):
    model_config = ConfigDict(extra="forbid")
    snapshot: str = Field(min_length=1)
    ids: list[str]
    om_version: str
    world: str | None = None
    options: str | None = None


def options_fingerprint(
    world: str | None,
    studio_url: str | None,
    cn_path: str,
    cn_name_mode: str,
    deprecate_missing: bool,
    connector_version: str,
    om_version: str,
    studio_workspace: str | None = None,
) -> str:
    """Invalidate the export cache when any projection input changes."""
    values = [
        world,
        studio_url,
        cn_path,
        cn_name_mode,
        deprecate_missing,
        connector_version,
        om_version,
        studio_workspace,
    ]
    return hashlib.sha256(json.dumps(values, separators=(",", ":")).encode()).hexdigest()


def read_state(path: Path) -> State | None:
    if not path.exists():
        return None
    return State.model_validate_json(path.read_text(encoding="utf-8"))


def write_state(path: Path, state: State) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary: str | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=path.parent, prefix=f".{path.name}.", delete=False
        ) as stream:
            temporary = stream.name
            canonical = state.model_copy(update={"ids": sorted(set(state.ids))})
            stream.write(canonical.model_dump_json() + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            Path(temporary).unlink(missing_ok=True)
