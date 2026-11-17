# SPDX-License-Identifier: Apache-2.0
"""The current pack snapshot, and how it is replaced (NL-15).

One object owns the loaded state; everything else reads it. Two properties make
that worth a module of its own.

**A failed load does not stop the service.** `Analyze` and `BatchLemmatize` need
no packs at all, and they are what Themis, Echo and kantheon actually call. So a
boot with a broken pack tree serves those normally, reports `ready=false`, and
puts the diagnostics where an operator will find them —
`GetStatus.pack_state.diagnostics`. `RunPipeline` is the only thing that fails,
with `FAILED_PRECONDITION`, which is the truth: the request is fine and the server
is not ready for it. Crashing instead would take the working half of the service
down over a YAML typo and put the reason in logs nobody is reading yet.

**A reload either replaces everything or changes nothing.** `reload()` validates
into a *new* snapshot and only then swaps the reference. There is no window in
which half the packs are new: a request that started against the old snapshot
finishes against it, because it holds the object rather than looking it up step by
step. The lock serialises concurrent reloads; readers never take it, since
replacing one reference is atomic and the snapshots are immutable.

A refused reload is therefore not an error *state*. `applied=false` plus
`NLS-PACK-010` and the diagnostics is the whole outcome, and the service is
exactly as healthy as it was a moment before. `pack_state.diagnostics` stays
empty — contracts §2.4 reserves it for a failed *boot*, and rightly: it answers
"is the snapshot now serving sound?", which after a refusal is still yes. The
refusal goes to whoever asked for it, synchronously, and to the log at ERROR.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Mapping
from datetime import UTC, datetime

from ttrnlp.packs.diag import NLS_PACK_001, Diagnostic
from ttrnlp.packs.diag import error as diagnostic
from ttrnlp.packs.loader import LoadedState, LoadError, load_sources
from ttrnlp.stages import Stage, installed_stages

from nlp_service.config import AppConfig
from nlp_service.diagnostics import NLS_PACK_010, meaning

logger = logging.getLogger(__name__)


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds")


class PackState:
    """Owns the current `LoadedState`. Built at boot, replaced by `reload()`."""

    def __init__(
        self,
        config: AppConfig,
        *,
        clock=_now,
        stages: Mapping[str, Stage] | None = None,
    ):
        self._config = config
        self._clock = clock
        # Resolved once, here: a clash in the `ttrnlp.stages` group is a boot
        # error (`StageError`), not a diagnostic a reload could clear (OL-C6).
        self._stages = installed_stages() if stages is None else stages
        self._lock = asyncio.Lock()
        self._state: LoadedState | None = None
        self._boot_diagnostics: list[Diagnostic] = []
        self.load()

    # ---- reading ----------------------------------------------------------

    @property
    def state(self) -> LoadedState | None:
        """The serving snapshot, or `None` if no load has ever succeeded."""
        return self._state

    @property
    def stages(self) -> Mapping[str, Stage]:
        """The installed stages every load and reload goes through."""
        return self._stages

    @property
    def diagnostics(self) -> list[Diagnostic]:
        """Why boot could not load. Empty once anything is serving."""
        return list(self._boot_diagnostics)

    @property
    def ready(self) -> bool:
        """Whether `RunPipeline` can serve.

        A deployment with no pack sources configured at all is ready: it has no
        pipelines, and `RunPipeline` rejects an unknown pipeline name with
        `INVALID_ARGUMENT`, which is the accurate complaint. Only a load that
        *failed* makes this false.
        """
        return self._state is not None

    @property
    def state_id(self) -> str:
        return self._state.state_id if self._state else ""

    def sources(self) -> list[str]:
        return [*self._config.packs.sources, *self._config.lists.sources]

    # ---- loading ----------------------------------------------------------

    def _attempt(self) -> tuple[LoadedState | None, list[Diagnostic]]:
        """Try to build a snapshot. Never mutates anything."""
        pipelines = {
            name: spec.model_dump() for name, spec in self._config.pipelines.items()
        }
        try:
            state = load_sources(
                self.sources(),
                pipelines=pipelines,
                loaded_at=self._clock(),
                stages=self._stages,
            )
        except LoadError as exc:
            return None, list(exc.diagnostics)
        except Exception as exc:  # noqa: BLE001
            # The loader turns everything it meets into a diagnostic (its module
            # docstring: "unreadable sources are diagnostics, not exceptions"),
            # so reaching here means it met something it did not expect. That is
            # a bug to fix, not a reason to take Analyze down with it — the whole
            # point of this class is that a bad pack tree costs RunPipeline and
            # nothing else. Loud, and reported through the same channel.
            logger.exception("unexpected error loading packs: %s", exc)
            return None, [
                diagnostic(
                    NLS_PACK_001,
                    f"unexpected error loading packs: {type(exc).__name__}: {exc}",
                )
            ]
        return state, []

    #: The two-phase halves of `reload`, exposed so the two snapshots the front
    #: owns — packs and the morph artifact — can be reloaded together without
    #: either half applying while the other refuses (`reload.py`). `reload()`
    #: below is still the single-owner path, unchanged.
    def prepare(self) -> tuple[LoadedState | None, list[Diagnostic]]:
        return self._attempt()

    def commit(self, state: LoadedState, diagnostics: list[Diagnostic]) -> None:
        self._state = state
        self._boot_diagnostics = []

    @property
    def lock(self) -> asyncio.Lock:
        return self._lock

    def load(self) -> bool:
        """Boot load. Returns whether a snapshot is now serving."""
        state, diagnostics = self._attempt()
        if state is None:
            self._boot_diagnostics = diagnostics
            logger.error(
                "pack load failed (%d diagnostic(s)) — RunPipeline will answer "
                "FAILED_PRECONDITION; Analyze and BatchLemmatize are unaffected. "
                "First: %s",
                len(diagnostics),
                diagnostics[0].message if diagnostics else "?",
            )
            return False

        self._state = state
        self._boot_diagnostics = []
        logger.info(
            "packs loaded: state_id=%s packs=%d lists=%d pipelines=%d",
            state.state_id,
            state.packs_loaded,
            state.lists_loaded,
            len(self._config.pipelines),
        )
        return True

    async def reload(self) -> tuple[bool, str, list[Diagnostic]]:
        """Validate-then-swap. Returns `(applied, state_id, diagnostics)`.

        On refusal the returned `state_id` is the one still serving — a caller
        comparing it before and after must see it unchanged, or "the reload did
        nothing" is not something it can establish from the response.
        """
        async with self._lock:
            state, diagnostics = self._attempt()
            if state is None:
                logger.error(
                    "reload refused (%d diagnostic(s)) — snapshot %s still "
                    "serving",
                    len(diagnostics),
                    self.state_id,
                )
                return (
                    False,
                    self.state_id,
                    [diagnostic(NLS_PACK_010, meaning(NLS_PACK_010)), *diagnostics],
                )

            self._state = state
            self._boot_diagnostics = []
            logger.info("reload applied: state_id=%s", state.state_id)
            return True, state.state_id, []


__all__ = ["PackState"]
