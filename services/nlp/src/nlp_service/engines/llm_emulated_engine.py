# SPDX-License-Identifier: Apache-2.0
"""`LLM_EMULATED` — an NlpEngine backed by a language model (RV-6, RV-P8.1).

For an estate that cannot host a real engine: the UFAL stack is CC BY-NC-SA and
its licence is a per-deployment decision (NL-5), so a deployment in the `default`
lane simply has no Czech NER at all. This engine fills that hole through the
llm-gateway — **off by default**, and an ordinary engine in every other respect:
it registers like one, routes like one, is withheld by a lane like one, and its
absence degrades like one.

**What it emulates, and what it refuses to.** LEMMATIZE, POS_TAG and NER (⚑ the
v1 set, flagged for Bora at p8-1 T1). Not DEP_PARSE — a wrong parse is silent and
head indices have to be self-consistent to be worth anything. Not TOKENIZE or
SENTENCE_SPLIT — the degrade floor already does both deterministically, in
process, for any language, and spending an LLM call to do them worse would be an
odd way to save money.

**The engine never asks the model to count characters.** The orchestrator merges
engines on the character span `(char_start, char_end)`: a lemma at a span no
other engine produced does not error, it simply never attaches, and the token is
added alongside — the same word twice with different boundaries. So the model is
asked only for surface forms in order, and this module locates each one in the
source text with a forward cursor, exactly as the wheel's parsers do for the UFAL
tools (which report no offsets either).

**A token that is not in the text fails the analysis.** Not dropped, not placed
at `char_start=-1`: a model that returned a word the text does not contain has
invented content, and the tokens around an invented one are exactly as
trustworthy as it is. This is the whole posture — *a wrong lemma delivered
confidently is the failure mode this engine must never have silently*.

**"In the text" means the whole word, and that needs two different rules.**
`str.find` alone is not the check it looks like: `Microsoft` IS in `Microsoftu`,
so a model returning the base form of an inflected entity — the exact mistake
Czech makes likeliest, and the reason this engine exists — would locate cleanly
on a span that is not a word, attach to no token the orchestrator merged, and be
served as a confident wrong answer. So:

* a TOKEN STREAM must **cover the text**. The prompts ask for every token in
  order, punctuation included, so the gaps between located spans can only be
  whitespace. That admits a legitimate split (`do` + `n't`, adjacent spans) and
  rejects a truncated one (`Microsoft` leaving a stranded `u`) without either
  needing a word-boundary rule.
* an ENTITY is sparse — nothing covers the gaps — so it is located on **word
  boundaries** instead: an alphanumeric may not sit against an alphanumeric edge
  of the surface.

**Entities are located anywhere, tokens only forward.** A token stream is
ordered by construction and a shared cursor is what makes a repeated word land on
its own occurrence. Entities are not: models emit them out of document order
routinely, and a shared cursor turned that into `RV-NLP-021` for the whole
request — every entity lost because one arrived early. Each entity is therefore
searched from the start of the text and claims the first free occurrence, which
keeps "must exist in the text" intact, still separates repeats, and lets a nested
entity have its own span.

**Determinism is conditional, and says so.** Temperature 0 is necessary and not
sufficient: a provider can change what a model name serves. So results are cached
per `(template_version, model, purpose, language, text)` — same input, same
output, within one deployment — and the route advertises the `REMOTE_UNPINNED`
tier, which already carries `RG-NLP-002` ("non-conformant for parity/
determinism"). Cross-provider stability is NOT promised; the capability matrix is
how an estate knows.
"""

from __future__ import annotations

import json
import logging
import threading
from collections import OrderedDict
from pathlib import Path
from typing import Any, Set

from ttrnlp.doc.labels import CNEC_CLASS_TO_UNIVERSAL, UNIVERSAL_FALLBACK

from nlp_service.config import LlmEmulatedConfig
from nlp_service.diagnostics import RV_NLP_020, RV_NLP_021
from nlp_service.engines.base import EngineResult, NerEntity, NlpOp, Token
from nlp_service.engines.llm_gateway import (
    GatewaySpec,
    GatewayUnavailable,
    LlmGatewayClient,
)

logger = logging.getLogger(__name__)

EMULATED_ENGINE_NAME = "llm_emulated"

#: Bumped whenever a prompt under `prompts/v<n>/` changes meaning. It rides the
#: advertised model string (S-1), so two deployments on the same model with
#: different templates are visibly not the same engine — and it is half the cache
#: key, so a template change cannot be served from the old one's answers.
TEMPLATE_VERSION = "1"

_PROMPTS = Path(__file__).parent / "prompts" / f"v{TEMPLATE_VERSION}"

#: Universal POS (UD v2). A tag outside this set is an invention, not a tag.
_UPOS = frozenset(
    "ADJ ADP ADV AUX CCONJ DET INTJ NOUN NUM PART PRON PROPN PUNCT SCONJ SYM VERB X".split()
)

#: cs — the coarse set this service already folds NameTag's CNEC classes into.
#: Derived from the wheel's table rather than restated, so the two cannot drift.
_LABELS_CS = frozenset({*CNEC_CLASS_TO_UNIVERSAL.values(), UNIVERSAL_FALLBACK})

#: en — OntoNotes, passed through untouched (`ttrnlp.doc.labels`). The asymmetry
#: with cs is real and deliberate: a pack matches ORGANIZATION in cs and ORG in
#: en, and an emulated engine that "helpfully" harmonised them would look right
#: and break every Czech pack.
_LABELS_EN = frozenset(
    "PERSON NORP FAC ORG GPE LOC PRODUCT EVENT WORK_OF_ART LAW LANGUAGE DATE TIME "
    "PERCENT MONEY QUANTITY ORDINAL CARDINAL".split()
)

#: One gateway call serves one purpose; LEMMATIZE and POS_TAG share theirs,
#: because asking twice would cost twice for a strictly worse joint result — the
#: two answers would not have to agree about what the tokens are.
_MORPH_OPS = frozenset({NlpOp.LEMMATIZE, NlpOp.POS_TAG})

_SUPPORTED_OPS = frozenset({NlpOp.LEMMATIZE, NlpOp.POS_TAG, NlpOp.NER})


class EmulationError(Exception):
    """The model answered with something no analysis can be built from."""


class LlmEmulatedEngine:
    """An `NlpEngine` whose backend is a language model behind llm-gateway."""

    def __init__(self, config: LlmEmulatedConfig, client: Any | None = None):
        self._config = config
        self._client = client or LlmGatewayClient(
            GatewaySpec(
                url=config.url,
                model=config.model,
                api_key=config.api_key,
                timeout_seconds=config.timeout_seconds,
                max_retries=config.max_retries,
            )
        )
        self._ops = frozenset(_parse_ops(config.ops))
        self._languages = frozenset(config.languages)
        self._cache: OrderedDict[tuple, EngineResult] = OrderedDict()
        # The front analyses on worker threads, and an OrderedDict's LRU moves are
        # not safe across them (`move_to_end` on a key another thread just evicted
        # raises). Held for the cache only, never across the gateway call.
        self._cache_lock = threading.Lock()

    # ---- identity ---------------------------------------------------------

    @property
    def name(self) -> str:
        return EMULATED_ENGINE_NAME

    @property
    def model_version(self) -> str:
        """S-1: the model AND the prompt revision that produced an analysis."""
        return f"{self._config.model}/tpl-{TEMPLATE_VERSION}"

    def supported_languages(self) -> Set[str]:
        return set(self._languages)

    def supports(self, lang: str, op: NlpOp) -> bool:
        return lang in self._languages and op in self._ops and _template_for(op, lang).exists()

    # ---- analysis ---------------------------------------------------------

    def analyze(self, text: str, lang: str, ops: Set[NlpOp]) -> EngineResult:
        wanted = {op for op in ops if self.supports(lang, op)}
        if not wanted:
            return EngineResult(error=f"{EMULATED_ENGINE_NAME} serves none of {sorted(o.value for o in ops)}")

        tokens: list[Token] = []
        entities: list[NerEntity] = []
        try:
            if wanted & _MORPH_OPS:
                tokens = self._morph(text, lang)
            if NlpOp.NER in wanted:
                entities = self._ner(text, lang)
        except GatewayUnavailable as exc:
            # Indistinguishable from an absent backend, by design: that is what
            # it IS from the front's side, and the front already knows how to
            # degrade for one.
            logger.warning("%s: gateway unavailable (%s)", EMULATED_ENGINE_NAME, exc)
            return EngineResult(error=f"{RV_NLP_020}: {exc}")
        except EmulationError as exc:
            logger.warning("%s: unusable model output (%s)", EMULATED_ENGINE_NAME, exc)
            return EngineResult(error=f"{RV_NLP_021}: {exc}")

        return EngineResult(tokens=tokens, entities=entities)

    # ---- the two calls ----------------------------------------------------

    def _morph(self, text: str, lang: str) -> list[Token]:
        body = self._ask("morph", text, lang)
        raw = _require_list(body, "tokens")
        located = _locate_tokens([_require_str(item, "text", "a token") for item in raw], text)

        tokens: list[Token] = []
        for item, (start, end) in zip(raw, located, strict=True):
            lemma = _require_str(item, "lemma", "a token")
            upos = _require_str(item, "upos", "a token")
            if upos not in _UPOS:
                raise EmulationError(f"{upos!r} is not a Universal POS tag")
            tokens.append(
                Token(text=text[start:end], char_start=start, char_end=end, lemma=lemma, upos=upos)
            )
        return tokens

    def _ner(self, text: str, lang: str) -> list[NerEntity]:
        body = self._ask("ner", text, lang)
        raw = _require_list(body, "entities")
        vocabulary = _LABELS_CS if lang == "cs" else _LABELS_EN
        located = _locate_entities([_require_str(item, "text", "an entity") for item in raw], text)

        entities: list[NerEntity] = []
        for item, (start, end) in zip(raw, located, strict=True):
            label = _require_str(item, "label", "an entity")
            if label not in vocabulary:
                raise EmulationError(
                    f"{label!r} is not a {lang} entity label "
                    f"(this service's {lang} vocabulary is {sorted(vocabulary)})"
                )
            entities.append(
                NerEntity(
                    text=text[start:end],
                    label=label,
                    char_start=start,
                    char_end=end,
                    source_engine=EMULATED_ENGINE_NAME,
                )
            )
        return entities

    # ---- the gateway hop, cached ------------------------------------------

    def _ask(self, purpose: str, text: str, lang: str) -> dict:
        key = (TEMPLATE_VERSION, self._config.model, purpose, lang, text)
        with self._cache_lock:
            if (hit := self._cache.get(key)) is not None:
                self._cache.move_to_end(key)
                return hit  # type: ignore[return-value]

        system = _template_for_purpose(purpose, lang).read_text(encoding="utf-8")
        content = self._client.chat(system=system, user=text, purpose=purpose)
        body = _parse_json(content)

        # Only successes are cached. A failure must not become this deployment's
        # answer for that input — the next request may well be after the outage.
        with self._cache_lock:
            self._cache[key] = body  # type: ignore[assignment]
            if len(self._cache) > max(1, self._config.cache_max_entries):
                self._cache.popitem(last=False)
        return body


# ── parsing helpers — strict, and loud about why ─────────────────────────────


def _parse_json(content: str) -> dict:
    """The reply as JSON, tolerating exactly one wrapper and nothing else.

    A markdown fence is stripped because it is the single most common way a model
    wraps JSON and rejecting it would make the engine unusable for no honesty
    gained. What is inside is then parsed strictly — leniency stops at the fence.
    """
    body = content.strip()
    if body.startswith("```"):
        body = body.split("\n", 1)[-1] if "\n" in body else ""
        if body.rstrip().endswith("```"):
            body = body.rstrip()[: -len("```")]
    try:
        parsed = json.loads(body)
    except json.JSONDecodeError as exc:
        raise EmulationError(f"the model did not answer with JSON ({exc.msg})") from exc
    if not isinstance(parsed, dict):
        raise EmulationError(f"expected a JSON object, got {type(parsed).__name__}")
    return parsed


def _require_list(body: dict, key: str) -> list[dict]:
    value = body.get(key)
    if not isinstance(value, list):
        raise EmulationError(f"the answer has no {key!r} list (keys: {sorted(body)})")
    for item in value:
        if not isinstance(item, dict):
            raise EmulationError(f"{key!r} holds {type(item).__name__}, expected objects")
    return value


def _require_str(item: dict, key: str, what: str) -> str:
    value = item.get(key)
    if not isinstance(value, str) or not value:
        raise EmulationError(f"{what} has no {key!r} ({item})")
    return value


def _locate_tokens(surfaces: list[str], text: str) -> list[tuple[int, int]]:
    """Spans for a token stream: found in order, and covering the text.

    In order, because that is what makes a repeated word land on its own
    occurrence rather than all of them on the first. A surface the text does not
    contain from the cursor onward is the model inventing content, and there is
    no honest partial answer to give: the whole analysis fails, naming it.

    Then the coverage check, which is what makes "in the text" mean the whole
    word. Located spans may only be separated by whitespace, because the prompt
    asks for every token of the input in order, punctuation included. A model
    that answered `Microsoft` for `Microsoftu` locates fine and strands a `u`, and
    the stranded character is the only evidence that the span is not a word.
    """
    spans: list[tuple[int, int]] = []
    cursor = 0
    for surface in surfaces:
        found = text.find(surface, cursor)
        if found < 0:
            raise EmulationError(
                f"{surface!r} is not in the text at or after offset {cursor} — "
                "the model returned a form the input does not contain"
            )
        spans.append((found, found + len(surface)))
        cursor = found + len(surface)

    covered = 0
    for start, end in [*spans, (len(text), len(text))]:
        if (dropped := text[covered:start]).strip():
            raise EmulationError(
                f"the tokens do not cover the text: {dropped.strip()!r} at offset "
                f"{covered} belongs to no token — the model dropped or truncated it "
                "(a partial word locates cleanly and is still the wrong span)"
            )
        covered = end
    return spans


def _locate_entities(surfaces: list[str], text: str) -> list[tuple[int, int]]:
    """Spans for entity surfaces: each claims the first free occurrence.

    Searched from the start of the text rather than from a shared cursor. Entities
    are not a stream — a model listing them out of document order is ordinary, and
    a cursor made that fail the whole request rather than the one entity. Repeats
    still land on distinct occurrences (an occurrence already claimed is skipped),
    and a nested entity can still have its own span.

    Word boundaries do here what coverage does for tokens: nothing fills the gaps
    between entities, so the only evidence that `Microsoft` in `Microsoftu` is not
    the entity is the alphanumeric sitting against the surface's alphanumeric edge.
    """
    spans: list[tuple[int, int]] = []
    taken: set[tuple[int, int]] = set()
    for surface in surfaces:
        span = _first_free_occurrence(surface, text, taken)
        taken.add(span)
        spans.append(span)
    return spans


def _first_free_occurrence(
    surface: str, text: str, taken: set[tuple[int, int]]
) -> tuple[int, int]:
    """The first whole-word occurrence of `surface` not already claimed.

    The three ways this fails are three different mistakes and are named as such:
    a form the text does not contain at all, a form that occurs only inside longer
    words (the inflection case), and more mentions claimed than the text has.
    """
    occurrences = 0
    whole_word = 0
    start = text.find(surface)
    while start >= 0:
        occurrences += 1
        span = (start, start + len(surface))
        if _on_word_boundaries(text, *span):
            whole_word += 1
            if span not in taken:
                return span
        start = text.find(surface, start + 1)

    if not occurrences:
        raise EmulationError(
            f"{surface!r} is not in the text — the model returned a form the input "
            "does not contain"
        )
    if not whole_word:
        raise EmulationError(
            f"{surface!r} occurs in the text only inside a longer word — the model "
            "returned a base or truncated form, not the surface form the text carries"
        )
    raise EmulationError(
        f"{surface!r} is claimed more often than the text mentions it "
        f"({whole_word} occurrence(s)) — the model returned a duplicate entity"
    )


def _on_word_boundaries(text: str, start: int, end: int) -> bool:
    """Whether `text[start:end]` is a whole word rather than part of one.

    Only alphanumeric-against-alphanumeric is a violation, so a surface that
    begins or ends on punctuation (`leden 2025.`, a quoted name) is unaffected —
    the rule exists to catch an inflected word being cut short, not to require
    that entities be surrounded by spaces.
    """
    before = text[start - 1] if start > 0 else ""
    after = text[end] if end < len(text) else ""
    if before.isalnum() and text[start].isalnum():
        return False
    if after.isalnum() and text[end - 1].isalnum():
        return False
    return True


def _parse_ops(names: list[str]) -> set[NlpOp]:
    ops: set[NlpOp] = set()
    for name in names:
        try:
            op = NlpOp(name)
        except ValueError as exc:
            raise ValueError(f"{name!r} is not an NLP op (config `engines.llm_emulated.ops`)") from exc
        if op not in _SUPPORTED_OPS:
            raise ValueError(
                f"{name} is not emulated in v1 — the set is "
                f"{sorted(o.value for o in _SUPPORTED_OPS)} (RV-6: DEP_PARSE is not promised)"
            )
        ops.add(op)
    return ops


def _template_for(op: NlpOp, lang: str) -> Path:
    return _template_for_purpose("morph" if op in _MORPH_OPS else "ner", lang)


def _template_for_purpose(purpose: str, lang: str) -> Path:
    return _PROMPTS / f"{purpose}.{lang}.txt"


__all__ = [
    "EMULATED_ENGINE_NAME",
    "TEMPLATE_VERSION",
    "EmulationError",
    "LlmEmulatedEngine",
]
