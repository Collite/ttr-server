# SPDX-License-Identifier: Apache-2.0
"""`RG-NLP-*` / `NLS-*` diagnostics for nlp (contracts §8).

The Python counterpart of the Kotlin `diagnostics` registry (RG-P0.S3):
named, stable, fixture-backed codes. Emitted onto responses as
`ResponseMessage`-shaped dicts (severity/code/human_message) via `message()`.

NLS-P3.2 adds the codes the pipeline surface needs. `NLS-NLP-011` is the one that
matters: it is the *explicit* half of the NL-14 degrade. An op the active lane
cannot route is skipped and every other phase still runs — but the response says
so, by name, on the Rule-6 message slot. A degrade nobody is told about is
indistinguishable from a pack that failed to match, and the two have completely
different fixes.
"""

from __future__ import annotations

from typing import Dict

# error — no engine backend reachable for a routed (lang, op) at startup
RG_NLP_001 = "RG-NLP-001"
# warning — route points at a REMOTE_UNPINNED tier (non-conformant). Lindat was
# the only one until RV-P8 made LLM_EMULATED a second: a hosted model is unpinned
# in exactly the sense this code means, so the wording no longer names Lindat and
# `RV-NLP-022` rides beside it to say WHICH unpinned thing served the op.
RG_NLP_002 = "RG-NLP-002"
# error — backend launched/responded without an explicit model id (S-1)
RG_NLP_003 = "RG-NLP-003"
# warning — the backend's rate limit (the remote tier's `rate_limit_per_minute`) is
# spent: the op was skipped, not queued, and the rest of the analysis still ran.
# Raised by `ttrnlp.client.backends.RateLimited`, whose message leads with it.
RG_NLP_004 = "RG-NLP-004"
# info — unsupported (lang, op): degrade floor applied (tokenize+fold+langid)
RG_NLP_010 = "RG-NLP-010"

# ── NLS-P3.2 (NLS contracts §8) ──────────────────────────────────────────────
# warning — an op the ACTIVE LANE does not route (default-lane `NER.cs`): skipped,
# every other phase still ran. The explicit half of the NL-14 degrade.
NLS_NLP_011 = "NLS-NLP-011"
# error — a ReloadPacks was refused; the previous snapshot is still serving
NLS_PACK_010 = "NLS-PACK-010"
# info — ReportToken arrived but the morph queue sink is not wired (LM, NLS-P9)
LM_MORPH_007 = "LM-MORPH-007"

# ── RV-P8 (LLM_EMULATED, RV-6) ───────────────────────────────────────────────
# error — the llm-gateway could not be reached or would not serve. Deliberately
# NOT its own degrade shape: from the front's side an unreachable gateway is an
# absent backend, and `RG-NLP-010`'s floor is what an absent backend already
# means. This code names the cause in the log; the posture is the existing one.
RV_NLP_020 = "RV-NLP-020"
# error — the model answered with something no analysis can be built from. The
# engine emits NOTHING in this case: a confidently wrong lemma is the failure
# mode emulation must never have silently, so a partial parse is not a partial
# success.
RV_NLP_021 = "RV-NLP-021"
# warning — this op was served by emulation. The response-level counterpart of
# the capability matrix's row: determinism here is conditional-on-matrix (RV-6),
# and a caller that reads only the answer still gets told.
RV_NLP_022 = "RV-NLP-022"

_SEVERITY: Dict[str, str] = {
    RG_NLP_001: "ERROR",
    RG_NLP_002: "WARNING",
    RG_NLP_003: "ERROR",
    RG_NLP_004: "WARNING",
    RG_NLP_010: "INFO",
    NLS_NLP_011: "WARNING",
    NLS_PACK_010: "ERROR",
    LM_MORPH_007: "INFO",
    RV_NLP_020: "ERROR",
    RV_NLP_021: "ERROR",
    RV_NLP_022: "WARNING",
}

_MEANING: Dict[str, str] = {
    RG_NLP_001: "no engine backend reachable for a routed (language, op)",
    RG_NLP_002: "route points at a REMOTE_UNPINNED tier — non-conformant for parity/determinism",
    RG_NLP_003: "backend has no explicit model id (S-1 violation)",
    RG_NLP_004: "backend rate limit spent — op skipped, not queued; the rest of the analysis ran",
    RG_NLP_010: "unsupported (language, op) — degrade floor applied (tokenize+fold+langid)",
    NLS_NLP_011: "op not routed in the active lane — skipped; the remaining phases still ran",
    NLS_PACK_010: "reload refused — the previous pack snapshot is still serving",
    LM_MORPH_007: "token report accepted=false — the morph queue sink is not configured",
    RV_NLP_020: "llm-gateway unreachable — the emulated engine degrades like an absent backend",
    RV_NLP_021: "the emulated engine's model output could not be parsed — no analysis emitted",
    RV_NLP_022: "this op was served by LLM_EMULATED — determinism is conditional (RV-6)",
}


def severity(code: str) -> str:
    return _SEVERITY.get(code, "INFO")


def meaning(code: str) -> str:
    """The registry's own wording for a code, unadorned.

    Callers that build a diagnostic in a different shape (the wheel's
    `Diagnostic`, for a `ReloadPacks` response) need the text without reaching
    into `_MEANING` — one registry, one wording, wherever it surfaces.
    """
    return _MEANING.get(code, code)


def split_code(text: str) -> tuple[str, str]:
    """`("RV-NLP-020", "detail")` for a `CODE: detail` string, `("", text)` else.

    An engine reports a failure as one string on `EngineResult.error`, which is
    the right shape for a log and the wrong one for a caller: a consumer filters
    `messages[].code`, and a code that only ever appears inside a human message
    is not in the registry in any sense the wire can see. This is how the
    orchestrator gets it back out — matched against `_SEVERITY` rather than a
    prefix pattern, so only a REGISTERED code is ever promoted, and an engine that
    happens to start a sentence with a colon still reads as an untyped failure.
    """
    code, separator, detail = text.partition(":")
    code = code.strip()
    if separator and code in _SEVERITY:
        return code, detail.strip()
    return "", text


def message(code: str, detail: str = "") -> dict:
    """Build a ResponseMessage-shaped dict for `code`, optionally suffixing
    `detail` (e.g. the offending language/op)."""
    text = _MEANING.get(code, code)
    if detail:
        text = f"{text}: {detail}"
    return {"severity": severity(code), "code": code, "message": text}
