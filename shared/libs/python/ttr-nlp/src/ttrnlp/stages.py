# SPDX-License-Identifier: Apache-2.0
"""Pipeline stages — the entry-point group ``ttrnlp.stages`` (OL-C6).

A pipeline runs engines → morphology → gazetteer → its ``stages``, in the
listed order (OL-C7). What a stage *is* — the rule engine today — is not known
to the loader or the runner: both reach a stage through this group only, so a
distribution that is not installed is simply a stage the image does not have,
and a pipeline naming it fails validation with ``NLS-PACK-006`` (OL-C8).

**What a distribution provides.** One entry in the group, whose value is a
zero-argument factory (a class is the usual one) returning an object with the
``Stage`` shape below::

    [project.entry-points."ttrnlp.stages"]
    rules = "ttrnlp.rules.stage:RulesStage"

``name`` is the id pipelines use (``stages: [{stage: rules, …}]``) and the trace
``kind``; ``source_suffixes`` are the source files the stage owns — the loader
hands it every file with one of those suffixes and nothing else. The state
``load`` returns is opaque to everything but the stage: the loader keeps it in
``LoadedState.stages[name]`` and passes it back to ``check_refs``, ``run`` and
``status``. It must be immutable — a snapshot is swapped wholesale on reload
while in-flight requests finish against the one they started on.

**A ref that no longer resolves at run time** (the snapshot changed under a
request) raises ``LookupError`` from ``run``; the runner logs it and skips the
step, as it always has for a missing pack.

**Boot errors.** Two entries yielding one ``name``, two stages owning one
suffix, a stage claiming the list suffix, or a factory whose product lacks the
shape: each raises ``StageError`` from ``installed_stages`` — at boot, before
anything loads, because which of two same-named stages a pipeline meant is
unanswerable.
"""

from __future__ import annotations

import time
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from importlib.metadata import EntryPoint, entry_points
from types import MappingProxyType
from typing import TYPE_CHECKING, Any, Protocol, runtime_checkable

from ttrnlp.packs.diag import Diagnostic

if TYPE_CHECKING:  # pragma: no cover — the loader imports this module
    from ttrnlp.doc import Document
    from ttrnlp.packs.loader import SourceFile

#: The entry-point group (OL-C6).
GROUP = "ttrnlp.stages"

#: The suffix gazetteer lists keep; no stage may claim it — lists load in the
#: open loader for every image.
RESERVED_SUFFIXES = (".list.yaml",)


@dataclass(frozen=True)
class StageTrace:
    """One executed stage step. The runner adds the ``kind`` (the stage's name)."""

    label: str
    annotations_added: int
    elapsed_ms: int


@runtime_checkable
class Stage(Protocol):
    name: str
    source_suffixes: tuple[str, ...]

    def load(self, files: Sequence[SourceFile]) -> tuple[object, list[Diagnostic]]: ...

    def check_refs(
        self, state: object, refs: Sequence[Mapping[str, Any]]
    ) -> list[Diagnostic]: ...

    def run(
        self, doc: Document, state: object, ref: Mapping[str, Any]
    ) -> StageTrace: ...

    def status(self, state: object) -> Mapping[str, int]: ...


class StageError(RuntimeError):
    """The installed stages cannot be used — a boot error, never a diagnostic."""


def _check_shape(stage: object, origin: str) -> Stage:
    name = getattr(stage, "name", None)
    suffixes = getattr(stage, "source_suffixes", None)
    if not isinstance(name, str) or not name:
        raise StageError(f"{GROUP}: {origin} has no string `name`")
    if not isinstance(suffixes, tuple) or not all(isinstance(s, str) for s in suffixes):
        raise StageError(
            f"{GROUP}: stage {name!r} ({origin}): "
            "`source_suffixes` is not a tuple of str"
        )
    for method in ("load", "check_refs", "run", "status"):
        if not callable(getattr(stage, method, None)):
            raise StageError(f"{GROUP}: stage {name!r} ({origin}) has no `{method}`")
    return stage  # type: ignore[return-value]


def installed_stages(
    entries: Iterable[EntryPoint] | None = None,
) -> Mapping[str, Stage]:
    """Every stage installed in this environment, by name.

    Args:
        entries: The group's entry points; defaults to the installed ones. A
            parameter so a spec can install a fake group.

    Raises:
        StageError: see the module docstring.
    """
    found = list(entry_points(group=GROUP) if entries is None else entries)
    stages: dict[str, Stage] = {}
    origins: dict[str, str] = {}
    owners: dict[str, str] = {}
    for entry in found:
        origin = f"entry {entry.name} = {entry.value}"
        stage = _check_shape(entry.load()(), origin)
        if stage.name in stages:
            raise StageError(
                f"{GROUP}: two entries name stage {stage.name!r} — "
                f"{origins[stage.name]} and {origin}"
            )
        for suffix in stage.source_suffixes:
            if suffix in RESERVED_SUFFIXES:
                raise StageError(
                    f"{GROUP}: stage {stage.name!r} claims the reserved "
                    f"suffix {suffix!r}"
                )
            if suffix in owners:
                raise StageError(
                    f"{GROUP}: stages {owners[suffix]!r} and {stage.name!r} "
                    f"both own {suffix!r}"
                )
            owners[suffix] = stage.name
        stages[stage.name] = stage
        origins[stage.name] = origin
    return MappingProxyType(dict(sorted(stages.items())))


def elapsed_ms(started: float) -> int:
    """Milliseconds since ``time.perf_counter()`` read ``started``."""
    return int((time.perf_counter() - started) * 1000)


__all__ = [
    "GROUP",
    "RESERVED_SUFFIXES",
    "Stage",
    "StageError",
    "StageTrace",
    "elapsed_ms",
    "installed_stages",
]
