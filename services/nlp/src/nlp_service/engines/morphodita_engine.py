# SPDX-License-Identifier: Apache-2.0
"""MorphoDiTa — the front-side `NlpEngine` wrapper.

The `/tag` vertical protocol, the offset recovery and the batch path moved to
`ttrnlp.client.backends` at NLS-P3.3 (⚑NLS-D7). What remains is this engine's
claim about itself: cs only, and only the four morphology ops. The backend is
either the self-hosted `morphodita_server` (`SELF_HOSTED_PINNED`) or Lindat
(dev/eval, `REMOTE_UNPINNED`); both select on an explicit model id (S-1), which
the spec carries.
"""

from __future__ import annotations

from typing import Set

from ttrnlp.client.backends import (
    BackendClient,
    analyze_morphodita,
    batch_lemmatize_morphodita,
)

from nlp_service.config import BackendConfig
from nlp_service.engines.adapters import backend_spec
from nlp_service.engines.base import EngineResult, NlpOp

_SUPPORTED_OPS = {NlpOp.TOKENIZE, NlpOp.SENTENCE_SPLIT, NlpOp.LEMMATIZE, NlpOp.POS_TAG}


class MorphoditaEngine:
    def __init__(self, backend: BackendConfig):
        self._backend = backend
        self._client = BackendClient(backend_spec(backend), name="morphodita")

    @property
    def name(self) -> str:
        return "morphodita"

    def supported_languages(self) -> Set[str]:
        return {"cs"}

    def supports(self, lang: str, op: NlpOp) -> bool:
        return lang in self.supported_languages() and op in _SUPPORTED_OPS

    def analyze(self, text: str, lang: str, ops: Set[NlpOp]) -> EngineResult:
        if not ops & _SUPPORTED_OPS:
            return EngineResult(
                error="MorphoDiTa only supports TOKENIZE, SENTENCE_SPLIT, "
                "LEMMATIZE, POS_TAG"
            )
        return analyze_morphodita(self._client, text)

    def batch_lemmatize(self, texts: list[str], lang: str) -> list[list[str]]:
        """N strings in ONE `/tag` pass (RS-6 / Q-10 §4).

        The front batches to the backend hop; it does not loop per-string HTTP.
        Chunking to the backend's `--max_request_size` is the caller's job — this
        issues a single call per chunk (one per text only when a batch does not
        split back apart; see `batch_lemmatize_morphodita`).
        """
        return batch_lemmatize_morphodita(self._client, texts)
