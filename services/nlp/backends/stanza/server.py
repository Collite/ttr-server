# SPDX-License-Identifier: Apache-2.0
"""Stanza backend server (RG-P1.S3.T2) — the uniform-JSON NLP contract.

A standalone FastAPI app the engine-free front's `JsonBackendEngine` adapter
talks to. Runs Stanza in-process (models baked in the image) and serves:

    POST /analyze  {"text", "language", "ops": [..], "model"}
      -> {"tokens":[{text,charStart,charEnd,lemma,upos,xpos,feats,depHead,depRelation}],
          "entities":[{text,label,charStart,charEnd,normalizedValue,sourceEngine}],
          "sentences":[{charStart,charEnd}], "modelVersion"}

Stanza serves cs (tokenize/mwt/lemma/pos/depparse — no cs NER head) and en
(+ ner). DEP_PARSE is the cs hot path (feeds the resolver's span proposal).

Transparent huge pages are switched OFF for this process at import, before torch
loads (`_disable_transparent_huge_pages`). On a node running THP `enabled=always`
with the kernel's default `khugepaged/max_ptes_none=511`, khugepaged collapses any
2 MB range of anonymous memory holding even one resident page into a full huge
page. A torch process leaves a large, sparse heap behind it — measured on this
image: ~2.5 GiB of writable anonymous address space, ~0.9 GiB of it resident — so
khugepaged fills that in while the server sits IDLE. Observed on a K3s node, and
consistent with exactly that:
resident memory flat at ~0.7 GiB for over an hour, then up to the 2 GiB limit in
20 minutes with no request served, the node's AnonHugePages rising by the same
amount; pinned at the limit, a 4-token request then took minutes, and the pod was
OOM-killed every day or two. `PR_SET_THP_DISABLE` is per process (the flag is
inherited across fork and exec), needs no privilege and no node change, and costs
nothing measurable here (same latency). Set `STANZA_KEEP_THP=1` to leave the
kernel's setting alone. `GET /readyz` reports which way it went.
"""

from __future__ import annotations

import ctypes
import os
import sys
from typing import Any

#: prctl(2) option — `<linux/prctl.h>`.
_PR_SET_THP_DISABLE = 41


def _disable_transparent_huge_pages() -> bool:
    """Switch transparent huge pages off for this process; `True` when the kernel took it.

    Best effort: not Linux, the opt-out set, or a libc without `prctl` ⇒ `False`, and the
    server runs exactly as before.
    """
    if not sys.platform.startswith("linux") or os.getenv("STANZA_KEEP_THP"):
        return False
    try:
        return ctypes.CDLL(None, use_errno=True).prctl(_PR_SET_THP_DISABLE, 1, 0, 0, 0) == 0
    except (OSError, AttributeError):
        return False


# Before `import stanza` pulls torch in — the flag covers the whole process either way,
# but nothing is allocated under the kernel's default first.
THP_DISABLED = _disable_transparent_huge_pages()

import stanza  # noqa: E402 — after the THP switch above, on purpose
from fastapi import FastAPI  # noqa: E402
from fastapi.responses import JSONResponse  # noqa: E402
from pydantic import BaseModel  # noqa: E402

MODEL_DIR = os.getenv("STANZA_RESOURCES_DIR", "/opt/nlp-models/stanza")
MODEL_VERSION = f"stanza-{stanza.__version__}"

app = FastAPI(title="nlp Stanza backend")
_pipelines: dict[str, Any] = {}


def _pipeline(lang: str):
    if lang not in _pipelines:
        processors = (
            "tokenize,mwt,lemma,pos,depparse" if lang == "cs"
            else "tokenize,lemma,pos,depparse,ner"
        )
        _pipelines[lang] = stanza.Pipeline(
            lang=lang, model_dir=MODEL_DIR, processors=processors, quiet=True
        )
    return _pipelines[lang]


class AnalyzeRequest(BaseModel):
    text: str
    language: str = "cs"
    ops: list[str] = []
    model: str = ""


def _feats(feats_str: str | None) -> dict[str, str]:
    out: dict[str, str] = {}
    for part in (feats_str or "").split("|"):
        if "=" in part:
            k, v = part.split("=", 1)
            out[k] = v
    return out


@app.get("/healthz")
def healthz():
    return {"status": "ok"}


@app.get("/readyz")
def readyz():
    return {"status": "ready", "modelVersion": MODEL_VERSION, "thpDisabled": THP_DISABLED}


@app.post("/analyze")
def analyze(req: AnalyzeRequest):
    lang = req.language or "cs"
    try:
        doc = _pipeline(lang)(req.text)
    except Exception as e:  # noqa: BLE001
        return JSONResponse(status_code=500, content={"error": str(e)})

    tokens: list[dict] = []
    entities: list[dict] = []
    sentences: list[dict] = []
    for sentence in doc.sentences:
        if sentence.tokens:
            sentences.append(
                {"charStart": sentence.tokens[0].start_char, "charEnd": sentence.tokens[-1].end_char}
            )
        for word in sentence.words:
            tokens.append(
                {
                    "text": word.text,
                    "charStart": word.start_char,
                    "charEnd": word.end_char,
                    "lemma": word.lemma or "",
                    "upos": word.upos or "",
                    "xpos": word.xpos or "",
                    "feats": _feats(word.feats),
                    "depHead": word.head if word.head else 0,
                    "depRelation": word.deprel or "",
                }
            )
        for ent in sentence.entities:
            entities.append(
                {
                    "text": ent.text,
                    "label": ent.type,
                    "charStart": ent.start_char,
                    "charEnd": ent.end_char,
                    "normalizedValue": "",
                    "sourceEngine": "stanza",
                }
            )
    return {"tokens": tokens, "entities": entities, "sentences": sentences, "modelVersion": MODEL_VERSION}


if __name__ == "__main__":
    import uvicorn

    print(f"transparent huge pages: {'disabled for this process' if THP_DISABLED else 'left to the kernel'}", flush=True)

    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", "8090")))
