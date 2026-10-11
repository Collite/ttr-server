# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import logging
from typing import Set

from lingua import LanguageDetectorBuilder

from nlp_service.config import LangidEngineConfig
from nlp_service.engines.base import EngineResult, NlpOp

logger = logging.getLogger(__name__)


class LangidEngine:
    """Language detection engine using lingua-language-detector.

    The one engine that stays in the front (tiny, no model files, no torch).
    Only supports DETECT_LANGUAGE. `model`/`model_version` back the S-1 echo.
    """

    def __init__(self, config: LangidEngineConfig | None = None):
        cfg = config or LangidEngineConfig()
        self.model = cfg.model
        self.model_version = cfg.model_version
        self._detector = LanguageDetectorBuilder.from_all_languages().build()

    @property
    def name(self) -> str:
        return "langid"

    def supported_languages(self) -> Set[str]:
        # lingua-language-detector supports many languages
        # We primarily care about cs and en for this platform
        return {"cs", "en", "de", "sk", "pl", "hu", "sl", "hr", "sr", "mk", "bg"}

    def supports(self, lang: str, op: NlpOp) -> bool:
        return op == NlpOp.DETECT_LANGUAGE

    def analyze(self, text: str, lang: str, ops: Set[NlpOp]) -> EngineResult:
        """Detect the language of the input text.

        Note: The 'lang' parameter is ignored for DETECT_LANGUAGE -
        the engine detects the language from the text itself.
        """
        if NlpOp.DETECT_LANGUAGE not in ops:
            return EngineResult(error="LangidEngine only supports DETECT_LANGUAGE operation")

        try:
            # lingua-language-detector 2.x API: `detect_language_of` returns a
            # `Language | None`; confidence comes from
            # `compute_language_confidence_values` (sorted desc). The forked
            # The predecessor original called the non-existent `.detect(...)` +
            # `.iso_code_639_1.value`, so language detection silently defaulted
            # to Czech for every input — fixed here (fork-time bugfix, Stage 2.3).
            detected = self._detector.detect_language_of(text)
            if detected is None:
                return EngineResult(error="No language detected")

            language_code = detected.iso_code_639_1.name.lower()
            confidence = next(
                (cv.value for cv in self._detector.compute_language_confidence_values(text) if cv.language == detected),
                0.0,
            )

            return EngineResult(
                tokens=[],
                entities=[],
                sentences=[],
                paragraphs=[],
                detected_language=language_code,
                language_confidence=confidence,
            )
        except Exception as e:
            logger.exception(f"Langid engine error: {e}")
            return EngineResult(error=str(e))