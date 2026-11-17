# SPDX-License-Identifier: Apache-2.0
"""The rule engine as the stage ``rules`` (OL-C10).

Registered through the ``ttrnlp.stages`` group in this wheel's own
``pyproject.toml`` until W4, when it leaves with the rest of ``ttrnlp.rules``.
It is the code that used to sit in the loader (``_gather_pack``) and the
runner (``_run_rules``), behind the ``Stage`` shape and nothing else: the same
``build_pack``, the same duplicate-id check, the same ``run_phases``, the same
messages.

The state is a read-only mapping ``pack id -> CompiledPack``.
"""

from __future__ import annotations

import time
from collections.abc import Mapping, Sequence
from types import MappingProxyType
from typing import TYPE_CHECKING, Any

from ttrnlp.packs.diag import NLS_PACK_001, NLS_PACK_004, Diagnostic, PackError, error
from ttrnlp.rules import build_pack
from ttrnlp.rules.compiler import CompiledPack
from ttrnlp.rules.pipeline import run_phases
from ttrnlp.stages import StageTrace, elapsed_ms

if TYPE_CHECKING:  # pragma: no cover
    from ttrnlp.doc import Document
    from ttrnlp.packs.loader import SourceFile

PACK_SUFFIX = ".pack.yaml"


class RulesStage:
    name = "rules"
    source_suffixes = (PACK_SUFFIX,)

    def load(
        self, files: Sequence[SourceFile]
    ) -> tuple[Mapping[str, CompiledPack], list[Diagnostic]]:
        packs: dict[str, CompiledPack] = {}
        origins: dict[str, str] = {}
        diagnostics: list[Diagnostic] = []
        for file in files:
            try:
                compiled = build_pack(file.text, source=file.path)
            except PackError as exc:
                diagnostics.extend(exc.diagnostics)
                continue
            if compiled.pack in packs:
                # Two packs claiming one id: pipelines name packs by id, so which
                # of the two a `rules: [{pack: …}]` ref meant is unanswerable —
                # and the second would have silently replaced the first.
                diagnostics.append(
                    error(
                        NLS_PACK_001,
                        f"duplicate pack id {compiled.pack!r}: also declared by "
                        f"{origins[compiled.pack]} — pipelines reference packs by id",
                        source=file.path,
                        pack=compiled.pack,
                    )
                )
                continue
            packs[compiled.pack] = compiled
            origins[compiled.pack] = file.path
        return MappingProxyType(packs), diagnostics

    def check_refs(
        self, state: object, refs: Sequence[Mapping[str, Any]]
    ) -> list[Diagnostic]:
        packs = _packs(state)
        diagnostics: list[Diagnostic] = []
        for ref in refs:
            pack_id = ref.get("pack")
            phase = ref.get("phase")
            compiled = packs.get(pack_id)  # type: ignore[arg-type]
            if compiled is None:
                known = ", ".join(sorted(packs)) or "none loaded"
                diagnostics.append(
                    error(NLS_PACK_004, f"no pack {pack_id!r} (loaded: {known})")
                )
                continue
            phases = [p.phase for p in compiled.phases]
            if phase not in phases:
                diagnostics.append(
                    error(
                        NLS_PACK_004,
                        f"pack {pack_id!r} has no phase {phase!r} "
                        f"(it has: {', '.join(phases)})",
                        pack=pack_id,  # type: ignore[arg-type]
                    )
                )
        return diagnostics

    def run(self, doc: Document, state: object, ref: Mapping[str, Any]) -> StageTrace:
        pack_id, phase = ref.get("pack"), ref.get("phase")
        pack = _packs(state).get(pack_id)  # type: ignore[arg-type]
        if pack is None:
            raise LookupError(f"pipeline references missing pack {pack_id!r}")
        started = time.perf_counter()
        before = len(doc.annset(""))
        run_phases(doc, [pack], phases=[phase])
        return StageTrace(
            label=f"{pack_id}:{phase}",
            annotations_added=len(doc.annset("")) - before,
            elapsed_ms=elapsed_ms(started),
        )

    def status(self, state: object) -> Mapping[str, int]:
        return {"packs_loaded": len(_packs(state))}


def _packs(state: object) -> Mapping[str, CompiledPack]:
    return state if isinstance(state, Mapping) else MappingProxyType({})


__all__ = ["PACK_SUFFIX", "RulesStage"]
