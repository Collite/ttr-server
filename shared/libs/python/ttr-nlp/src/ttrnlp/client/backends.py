# SPDX-License-Identifier: Apache-2.0
"""HTTP engine-adapter clients — one transport, four protocols (⚑NLS-D7).

These moved out of `services/nlp` because they are not the service's: they are how
anything talks to a Tatrman NLP backend image, and the service was simply the
first caller. `nlp-mcp` and the legacy `ai-platform` front need the same code, and
the alternative to moving it was a third copy diverging in its retry behaviour.

**What moved, and what did not** (⚑NLS-D7). Here: the transport, and the four
response protocols. Still service-side: `EngineRegistry`, per-op-per-language
routing, the orchestrator, and `langid` (in-process by design — lingua is tiny and
detection has no backend to call). The division is "how to talk to one backend"
versus "which backend to talk to", and the second is a deployment's business.

**One `BackendClient` instead of three copies of the same retry loop.** The three
adapters each had their own attempt loop, sleep schedule and rate-limit queue,
written three times and subtly different: the uniform-JSON one did not follow
redirects, the two UFAL ones did. That is the sort of difference that shows up as
one engine timing out on a cluster where another is fine. There is one loop now.

**The protocols, and why they are all still here.** A backend speaks whichever
protocol its upstream tool speaks, and translating them at the edge is exactly
this module's job:

=====================  =====================================================
`uniform-json`         Stanza and spaCy backends — our own JSON contract,
                       because we wrote those images
`morphodita-vertical`  MorphoDiTa's native `/tag`: `word\\tlemma\\ttag`, blank
                       lines between sentences
`nametag-conll`        NameTag 3's native `/recognize` with `output=conll`:
                       `word\\tB-/I-/O`
=====================  =====================================================

**Offsets are recovered by search, not reported.** Neither UFAL tool returns
character offsets, so the parsers walk the original text with a cursor, finding
each token after the previous one. That is why a token that cannot be found gets
`char_start=-1` rather than a guess: everything downstream indexes into the text,
and a plausible-but-wrong span is worse than an obviously absent one.
"""

from __future__ import annotations

import logging
import re
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from enum import Enum
from typing import Any

from ttrnlp.doc.labels import CNEC_PREFIX, cnec_to_universal

logger = logging.getLogger(__name__)


class NlpOp(str, Enum):  # noqa: UP042
    """The operations a backend can be asked for. Names match the proto enum.

    `(str, Enum)` rather than ruff's suggested `StrEnum`: this class MOVED here
    from `nlp_service.engines.base` at NLS-P3.3, and the two differ in what
    `str(op)` and f-string interpolation produce ("NlpOp.NER" versus "NER"). A
    code move must not change behaviour, and the service formats ops into log
    lines and error messages in a dozen places. Worth revisiting on purpose,
    never as a side effect of a lint rule.
    """

    TOKENIZE = "TOKENIZE"
    SENTENCE_SPLIT = "SENTENCE_SPLIT"
    LEMMATIZE = "LEMMATIZE"
    POS_TAG = "POS_TAG"
    DEP_PARSE = "DEP_PARSE"
    NER = "NER"
    DETECT_LANGUAGE = "DETECT_LANGUAGE"


@dataclass(frozen=True)
class Token:
    """One token with whatever the engine said about it."""

    text: str
    char_start: int
    char_end: int
    lemma: str = ""
    upos: str = ""  # Universal POS tag
    xpos: str = ""  # Language-specific POS tag
    feats: dict[str, str] = field(default_factory=dict)
    dep_head: int = 0  # Head token index (1-based), 0 = root
    dep_relation: str = ""


@dataclass(frozen=True)
class NerEntity:
    text: str
    label: str
    char_start: int
    char_end: int
    normalized_value: str = ""
    source_engine: str = ""


@dataclass
class EngineResult:
    """What one backend call produced. `error` non-empty means it did not."""

    tokens: list[Token] = field(default_factory=list)
    entities: list[NerEntity] = field(default_factory=list)
    sentences: list[tuple[int, int]] = field(default_factory=list)
    paragraphs: list[tuple[int, int]] = field(default_factory=list)
    error: str = ""
    detected_language: str = ""
    language_confidence: float = 0.0


@dataclass(frozen=True)
class BackendSpec:
    """Where a backend is and which model it must serve.

    `model` is never blank for a model-bearing backend (S-1): it goes on every
    request and is echoed on every response, so a cache key and a provenance
    record both exist. MorphoDiTa's server does an EXACT-match lookup against its
    registered id and answers 400 on a mismatch — it does not fall back to a
    default — so a blank here is a config error, not a shortcut.

    A plain dataclass rather than the service's pydantic `BackendConfig`: the
    wheel must not import `nlp_service` (there is a test), and a consumer that has
    only this wheel should not need a config framework to name a URL.
    """

    url: str
    model: str = ""
    model_version: str = ""
    timeout_seconds: int = 30
    max_retries: int = 3
    #: >0 only for the remote (Lindat) dev tier; self-hosted backends are
    #: unthrottled, and a limit there would throttle our own cluster. A call over
    #: the limit raises `RateLimited` at once; it is never queued (`_RateLimiter`).
    rate_limit_per_minute: int = 0


class BackendError(Exception):
    """A backend did not answer usefully. Carries the last transport failure."""


#: The front's diagnostic for a call the rate limit refused (nlp contracts §8). The
#: message leads with it so the front's orchestrator can put the code on the wire
#: rather than a generic engine error.
RATE_LIMITED_CODE = "RG-NLP-004"


class RateLimited(BackendError):
    """The backend's per-minute budget is spent. The call was not made.

    Raised at once rather than waited out: see `_RateLimiter`.
    """


def _httpx():
    """`httpx` from the `[http]` extra, with an actionable failure."""
    try:
        import httpx
    except ImportError as exc:  # pragma: no cover - exercised via monkeypatch
        raise ImportError(
            "the HTTP engine adapters need the `http` extra — "
            "install `ttr-nlp[http]`"
        ) from exc
    return httpx


class _RateLimiter:
    """A sliding one-minute window. Inert unless a limit is set.

    A call over the limit is REFUSED, not delayed. This used to sleep until the
    oldest call aged out of the window, up to 60 s. That sleep ran inside the
    caller's request, and the nlp front's async handlers made it block the whole
    event loop, so one throttled NER call stalled every request the service was
    serving, a TOKENIZE-only one included. Even off the loop, a wait that long is
    past any interactive caller's timeout: the caller loses the whole analysis
    instead of the one op. A refusal costs only that op.

    Thread-safe: the front runs analyses on worker threads.
    """

    def __init__(self, per_minute: int):
        self._limit = per_minute
        self._calls: deque[float] = deque()
        self._lock = threading.Lock()

    def acquire(self, name: str = "") -> None:
        """Count one call, or raise `RateLimited` if the window is full."""
        if self._limit <= 0:
            return
        with self._lock:
            now = time.monotonic()
            self._evict(now)
            if len(self._calls) >= self._limit:
                free_in = 60.0 - (now - self._calls[0])
                raise RateLimited(
                    f"{RATE_LIMITED_CODE}: {name or 'backend'}: {self._limit}/min "
                    f"spent, next slot in {free_in:.0f} s; call skipped"
                )
            self._calls.append(now)

    def _evict(self, now: float) -> None:
        while self._calls and (now - self._calls[0]) > 60.0:
            self._calls.popleft()


class BackendClient:
    """One POST, retried, against one backend.

    Retries cover the transport only — timeouts, network errors and non-200
    statuses. A 4xx is included deliberately: MorphoDiTa answers 400 for a model
    id it does not know, and a backend that has just started may not have
    registered its model yet, so the retry is what covers a rolling restart. The
    backoff is linear and small (0.1s × attempt) because these are in-cluster hops.
    """

    def __init__(self, spec: BackendSpec, *, name: str = ""):
        self.spec = spec
        self.name = name
        self._limiter = _RateLimiter(spec.rate_limit_per_minute)
        #: Backend-reported version, learned from responses that carry one (S-1).
        self.reported_model_version = spec.model_version

    def post_json(
        self,
        *,
        path: str = "",
        json: dict | None = None,
        data: dict | None = None,
    ):
        """POST and return the decoded JSON body.

        Raises:
            BackendError: If every attempt failed.
        """
        httpx = _httpx()
        url = self.spec.url.rstrip("/") + path if path else self.spec.url
        last: Exception | None = None

        for attempt in range(self.spec.max_retries + 1):
            # Raises `RateLimited` past the budget, retry or not: a refused call
            # is not retried, because the next attempt would be refused as well.
            self._limiter.acquire(self.name or url)
            try:
                with httpx.Client(
                    timeout=self.spec.timeout_seconds, follow_redirects=True
                ) as client:
                    response = client.post(url, json=json, data=data)
                if response.status_code != 200:
                    last = BackendError(f"HTTP {response.status_code}")
                    time.sleep(0.1 * (attempt + 1))
                    continue
                return response.json()
            except (httpx.TimeoutException, httpx.NetworkError) as exc:
                last = exc
                time.sleep(0.1 * (attempt + 1))
                continue

        raise BackendError(f"{self.name or url} request failed: {last}")

    def result_text(self, data: dict) -> str:
        """A UFAL backend's `result` string (both wrap their output in JSON)."""
        body = self.post_json(data=data)
        text = body.get("result", "")
        if not isinstance(text, str):
            raise BackendError(f"{self.name}: response has no string `result`")
        return text


# ── uniform JSON (Stanza, spaCy) ─────────────────────────────────────────────


def analyze_uniform_json(
    client: BackendClient, text: str, language: str, ops: set[NlpOp]
) -> EngineResult:
    """Call a backend that speaks our own JSON contract.

    `ops` is sorted before it goes on the wire so two identical requests produce
    identical bodies — which is what makes an HTTP cache in front of a backend
    safe to add later.
    """
    try:
        body = client.post_json(
            path="/analyze",
            json={
                "text": text,
                "language": language,
                "ops": sorted(op.value for op in ops),
                "model": client.spec.model,  # S-1
            },
        )
    except BackendError as exc:
        return EngineResult(error=str(exc))
    except Exception as exc:  # noqa: BLE001 — surface as an engine error
        logger.exception("%s backend error: %s", client.name, exc)
        return EngineResult(error=str(exc))

    return parse_uniform_json(body, client)


def parse_uniform_json(body: dict[str, Any], client: BackendClient) -> EngineResult:
    if client.spec.model and body.get("modelVersion"):
        client.reported_model_version = str(body["modelVersion"])

    tokens = [
        Token(
            text=t.get("text", ""),
            char_start=int(t.get("charStart", -1)),
            char_end=int(t.get("charEnd", 0)),
            lemma=t.get("lemma", ""),
            upos=t.get("upos", ""),
            xpos=t.get("xpos", ""),
            feats=dict(t.get("feats", {}) or {}),
            dep_head=int(t.get("depHead", 0)),
            dep_relation=t.get("depRelation", ""),
        )
        for t in body.get("tokens", [])
    ]
    entities = [
        NerEntity(
            text=e.get("text", ""),
            label=e.get("label", ""),
            char_start=int(e.get("charStart", -1)),
            char_end=int(e.get("charEnd", 0)),
            normalized_value=e.get("normalizedValue", ""),
            source_engine=e.get("sourceEngine", "") or client.name,
        )
        for e in body.get("entities", [])
    ]
    sentences = [
        (int(s.get("charStart", 0)), int(s.get("charEnd", 0)))
        for s in body.get("sentences", [])
    ]
    return EngineResult(tokens=tokens, entities=entities, sentences=sentences)


# ── MorphoDiTa vertical ──────────────────────────────────────────────────────

MORPHODITA_FORM = {"input": "untokenized", "output": "vertical"}


def analyze_morphodita(client: BackendClient, text: str) -> EngineResult:
    """cs TOKENIZE / SENTENCE_SPLIT / LEMMATIZE / POS_TAG via `/tag`."""
    try:
        vertical = client.result_text(
            {"data": text, "model": client.spec.model, **MORPHODITA_FORM}
        )
    except BackendError as exc:
        return EngineResult(error=str(exc))
    except Exception as exc:  # noqa: BLE001
        logger.exception("morphodita engine error: %s", exc)
        return EngineResult(error=str(exc))

    tokens, sentences = parse_morphodita_vertical(vertical, text)
    return EngineResult(tokens=tokens, sentences=sentences)


def batch_lemmatize_morphodita(
    client: BackendClient, texts: list[str]
) -> list[list[str]]:
    """N strings, ONE round trip (RS-6 / Q-10 §4).

    Joined with a blank line, which is MorphoDiTa's sentence break, so the
    vertical output segments back apart on the same boundary. Newlines inside a
    text become spaces first — one of them would split that text in two and
    silently shift every later result by one position.
    """
    if not texts:
        return []
    joined = "\n\n".join(t.replace("\n", " ") for t in texts)
    vertical = client.result_text(
        {"data": joined, "model": client.spec.model, **MORPHODITA_FORM}
    )
    return parse_morphodita_lemma_groups(vertical, len(texts))


#: A homonym index, written straight after the stem: `místo-1`, `Hradec-2`.
_HOMONYM_INDEX = re.compile(r"-\d+$")

#: A PDT term-semantic marker, `_;<code>` in a raw lemma.
_MARKER = re.compile(r"_;(.)")

#: The markers of a proper name: `G` geography, `Y` given name, `S` surname, `K`
#: company, `R` product, `m` any other. `E` (an inhabitant, `Pražan_;E`) is left
#: out: UD tags it a common noun, although Czech capitalises it.
_PROPER_NAME_MARKERS = frozenset("GYSKRm")


def _strip_lemma_suffix(lemma: str) -> str:
    """MorphoDiTa decorates a lemma: a homonym index right after the stem, then
    comments and term-semantic markers, each after an `_`
    (`místo-1_^(fyzické_umístění)`, `Hradec-2_;G`, `být_^(pomocné)`). Take the stem.

    Both are removed, in the order they are written: the `_` part first, then the
    index. Cutting at the `_` and stopping there left `místo-1` and `Shell-2` in every
    lemma that also carried a comment or a marker, and a lemma with an index no
    longer matched the word as a dictionary writes it.

    Only a numeric index is an index (`Frýdek-Místek` is a stem), and only after a
    letter, so a lemma that IS a hyphenated number keeps its shape.
    """
    stem = lemma.split("_", 1)[0] or lemma
    if stem[:1].isalpha():
        stem = _HOMONYM_INDEX.sub("", stem)
    return stem


def _is_proper_name(raw_lemma: str) -> bool:
    """The lemma's markers when it has any; otherwise, a capitalised stem.

    The markers decide first because they are the model's own classification:
    `Pražan_;E` is capitalised and still a common noun. `Čech_;E_;Y` carries both
    readings (a nationality and a surname) and counts as a name.

    Without markers, the capital decides. MorfFlex writes every common-noun lemma
    in lower case, a sentence-initial one too (`Obraty` -> `obrat-1`), so a capital
    in the stem is the model saying "name" — which is how a name the guesser met
    (`Kvartonu` -> `Kvarton`) is recognised, since the guesser does not always add
    a marker.
    """
    markers = _MARKER.findall(raw_lemma)
    if markers:
        return any(m in _PROPER_NAME_MARKERS for m in markers)
    return _strip_lemma_suffix(raw_lemma)[:1].isupper()


def parse_morphodita_vertical(
    vertical: str, original: str
) -> tuple[list[Token], list[tuple[int, int]]]:
    """`word\\tlemma\\ttag` lines, blank line between sentences."""
    tokens: list[Token] = []
    sentences: list[tuple[int, int]] = []
    if not vertical:
        return tokens, sentences

    cursor = 0
    sentence: list[Token] = []

    def close_sentence() -> None:
        if sentence:
            sentences.append((sentence[0].char_start, sentence[-1].char_end))
            sentence.clear()

    for line in vertical.split("\n"):
        if line == "":
            close_sentence()
            continue

        parts = line.split("\t")
        word = parts[0]
        raw_lemma = parts[1] if len(parts) > 1 else word
        lemma = _strip_lemma_suffix(raw_lemma)
        xpos = parts[2] if len(parts) > 2 else ""

        found = original.find(word, cursor)
        if found >= 0:
            char_start, char_end = found, found + len(word)
            cursor = char_end
        else:
            # Not a guess: everything downstream indexes into the text, and a
            # plausible-but-wrong span is worse than an obviously absent one.
            char_start, char_end = -1, 0

        token = Token(
            text=word,
            char_start=char_start,
            char_end=char_end,
            lemma=lemma,
            upos=pdt_tag_to_upos(xpos, raw_lemma),
            xpos=xpos,
        )
        tokens.append(token)
        sentence.append(token)

    close_sentence()
    return tokens, sentences


def parse_morphodita_lemma_groups(vertical: str, count: int) -> list[list[str]]:
    """Split vertical output into `count` positional lemma lists.

    Padded and truncated to `count` on purpose: `BatchLemmatize` is positional to
    its request, so a caller zipping the two lists must not have them slip. A
    short group is visible as an empty list; a misaligned one is not visible at
    all.
    """
    groups: list[list[str]] = []
    current: list[str] = []
    for line in vertical.split("\n"):
        if line == "":
            if current:
                groups.append(current)
                current = []
            continue
        parts = line.split("\t")
        current.append(_strip_lemma_suffix(parts[1] if len(parts) > 1 else parts[0]))
    if current:
        groups.append(current)

    while len(groups) < count:
        groups.append([])
    return groups[:count]


#: PDT major tag -> UD POS. `N` is special-cased for proper nouns below.
_PDT_TO_UPOS = {
    "A": "ADJ",
    "C": "NUM",
    "D": "ADV",
    "I": "INTJ",
    "J": "CCONJ",
    "P": "PRON",
    "R": "ADP",
    "T": "PART",
    "V": "VERB",
    "X": "X",
    "Z": "PUNCT",
}


def pdt_tag_to_upos(pdt_tag: str, lemma: str = "") -> str:
    """The first character of a PDT tag, as a UD POS. `lemma` is the RAW lemma.

    A proper noun matters because `upos: PROPN` is what the invoices hero's
    default-lane fallback rule matches on — the one thing that still finds
    "Microsoft" when cs NER is unrouted — and what callers build name runs from.
    But the tag does not say it: the Czech model tags `Zlín` and `město` alike,
    `NN…`. The lemma does (`Zlín_;G`, `Novák_;Y`, a capitalised stem — see
    `_is_proper_name`), so a noun is PROPN when its raw lemma says so. `NP` is kept
    for a tagset that does emit it.
    """
    if not pdt_tag:
        return ""
    if pdt_tag[0] == "N":
        proper = pdt_tag[1:2] == "P" or _is_proper_name(lemma)
        return "PROPN" if proper else "NOUN"
    return _PDT_TO_UPOS.get(pdt_tag[0], "X")


# ── NameTag 3 CoNLL ──────────────────────────────────────────────────────────

NAMETAG_FORM = {"input": "untokenized", "output": "conll"}


def analyze_nametag(client: BackendClient, text: str) -> EngineResult:
    """cs/en NER via `/recognize`.

    `output=conll` gives the `word\\tB-/I-/O` form parsed below. `output=vertical`
    is a *different* `idx\\ttype\\ttext` shape — asking for the wrong one produces
    a response that parses to zero entities rather than an error.
    """
    if not client.spec.model:
        # S-1: an enabled NER route with no explicit model is a config error, and
        # naming it here beats a 400 from the backend.
        return EngineResult(error="NameTag 3 has no explicit model id (RG-NLP-003)")

    try:
        conll = client.result_text(
            {"data": text, "model": client.spec.model, **NAMETAG_FORM}
        )
    except BackendError as exc:
        return EngineResult(error=str(exc))
    except Exception as exc:  # noqa: BLE001
        logger.exception("nametag engine error: %s", exc)
        return EngineResult(error=str(exc))

    return EngineResult(entities=parse_nametag_conll(conll, text, client.name))


def parse_nametag_conll(
    conll: str, original: str, source_engine: str = "nametag3"
) -> list[NerEntity]:
    """`word\\tB-/I-/O` lines into entities.

    The CNEC class becomes a universal coarse label and the raw tag is preserved
    in `normalized_value` as `cnec:<tag>`, because the coarse label loses
    information a pack may want back — the importers read the raw tag rather than
    trusting the label, since the label is an adapter's interpretation and a stale
    one would mistype the annotation so that the rule wanting it never fires.

    An entity's extent is where its words ARE in the source, not the length of
    the words joined by spaces: NameTag tokenizes `Frýdku-Místku` as three words,
    and `" ".join` spells it two characters longer than the question does, so the
    end overshot into the next word. Where every word was found, `text` is the
    source slice (`original[char_start:char_end] == text`), as for any tokenizer
    here; a word the search missed keeps the joined spelling as before.
    """
    entities: list[NerEntity] = []
    if not conll:
        return entities

    words: list[str] = []
    cnec = ""
    start = -1
    end = -1
    found_all = True
    cursor = 0

    def flush() -> None:
        nonlocal words, cnec, start, end, found_all
        if not words:
            return
        joined = " ".join(words)
        if start >= 0 and found_all:
            text, char_end = original[start:end], end
        else:
            text, char_end = joined, (start + len(joined)) if start >= 0 else 0
        entities.append(
            NerEntity(
                text=text,
                label=cnec_to_universal(cnec),
                char_start=start,
                char_end=char_end,
                normalized_value=f"{CNEC_PREFIX}{cnec}",
                source_engine=source_engine,
            )
        )
        words, cnec, start, end, found_all = [], "", -1, -1, True

    for line in conll.strip().split("\n"):
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        word, tag = parts[0].strip(), parts[1].strip()

        if tag == "O":
            flush()
        elif tag.startswith("B-"):
            flush()
            cnec = tag[2:]
            found = original.find(word, cursor)
            start = found
            if found >= 0:
                cursor = end = found + len(word)
            else:
                found_all = False
            words = [word]
        elif tag.startswith("I-") and words:
            words.append(word)
            found = original.find(word, cursor)
            if found >= 0:
                cursor = end = found + len(word)
            else:
                found_all = False

    flush()
    return entities


__all__ = [
    "MORPHODITA_FORM",
    "NAMETAG_FORM",
    "BackendClient",
    "BackendError",
    "BackendSpec",
    "EngineResult",
    "RATE_LIMITED_CODE",
    "RateLimited",
    "NerEntity",
    "NlpOp",
    "Token",
    "analyze_morphodita",
    "analyze_nametag",
    "analyze_uniform_json",
    "batch_lemmatize_morphodita",
    "parse_morphodita_lemma_groups",
    "parse_morphodita_vertical",
    "parse_nametag_conll",
    "parse_uniform_json",
    "pdt_tag_to_upos",
]
