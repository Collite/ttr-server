# SPDX-License-Identifier: Apache-2.0
"""`BatchLemmatize` on MorphoDiTa gives each text its own lemmas, whatever its length.

The batch path used to join the texts with a blank line and split the output on
blank lines. MorphoDiTa writes a blank line after every SENTENCE, so a text of
three sentences came back as three groups: the first text kept only its first
sentence, the next texts got the rest of it, and the result was then cut to the
request's length — the right count, the wrong lemmas.

The fixture is the backend's own `result` strings for the same texts sent three
ways: batched with the break token, batched with blank lines only (the old
path), and one text per request.
"""

from __future__ import annotations

import json
from pathlib import Path

from ttrnlp.client.backends import (
    MORPHODITA_BATCH_BREAK,
    BackendClient,
    BackendSpec,
    batch_lemmatize_morphodita,
    parse_morphodita_lemma_groups,
)
from ttrnlp.client.pdt import lemma_stem

_FIXTURE = (
    Path(__file__).parent.parent
    / "fixtures"
    / "morphodita"
    / "batch-czech-morfflex2.0-pdtc1.0-220710.json"
)
WIRE = json.loads(_FIXTURE.read_text())
TEXTS: list[str] = WIRE["texts"]


def _lemmas(vertical: str) -> list[str]:
    return [lemma_stem(line.split("\t")[1]) for line in vertical.split("\n") if line]


ALONE = [_lemmas(v) for v in WIRE["alone"]]


class _Backend(BackendClient):
    """Answers `/tag` from the fixture and records each `data` it was sent."""

    def __init__(self, answers: dict[str, str]):
        super().__init__(
            BackendSpec(
                url="http://morphodita/tag",
                model="czech-morfflex2.0-pdtc1.0-220710",
            ),
            name="morphodita",
        )
        self.answers = answers
        self.sent: list[str] = []

    def result_text(self, data: dict) -> str:
        self.sent.append(data["data"])
        return self.answers[data["data"]]


# How the backend tags the break token: an unknown word, form = lemma.
BREAK_LINE = f"{MORPHODITA_BATCH_BREAK}\t{MORPHODITA_BATCH_BREAK}\tDb-------------"
_SEPARATOR = f"\n\n{MORPHODITA_BATCH_BREAK}\n\n"


def _joined(texts: list[str]) -> str:
    return _SEPARATOR.join(texts)


class _PerTextBackend(_Backend):
    """Answers a batch of any size as the backend does: each text's own answer,
    the break token's line between them. Checked against the captured batch in
    `test_the_per_text_backend_answers_a_batch_as_the_backend_did`."""

    def result_text(self, data: dict) -> str:
        self.sent.append(data["data"])
        return f"{BREAK_LINE}\n\n".join(
            self.answers[t] for t in data["data"].split(_SEPARATOR)
        )


def _per_text_answers() -> dict[str, str]:
    return dict(zip(TEXTS, WIRE["alone"], strict=True))


def _tainted(k: int) -> tuple[str, str, list[str]]:
    """`TEXTS[k]` with the break token in front: text, backend answer, lemmas."""
    return (
        f"{MORPHODITA_BATCH_BREAK} {TEXTS[k]}",
        f"{BREAK_LINE}\n{WIRE['alone'][k]}",
        [MORPHODITA_BATCH_BREAK, *ALONE[k]],
    )


def _sixty_four() -> tuple[list[str], list[list[str]]]:
    """64 texts cycling through the fixture (blank ones included), with lemmas."""
    texts = [TEXTS[i % len(TEXTS)] for i in range(64)]
    return texts, [ALONE[i % len(TEXTS)] for i in range(64)]


def test_each_text_gets_the_lemmas_it_gets_alone():
    groups = parse_morphodita_lemma_groups(WIRE["batched"], len(TEXTS))

    assert groups == ALONE
    # the third sentence stays with its text
    assert groups[0][-4:] == ["žádat", "vrácení", "peníze", "."]
    assert groups[1] == []  # a blank text keeps its place
    assert groups[2] == ["škoda", ",", "vypadat", "hezky", "."]


def test_a_blank_line_does_not_end_a_text():
    """What the old split saw: more groups than texts, then cut to fit."""
    blank_line_groups = [g for g in WIRE["blankLine"].split("\n\n") if g.strip()]

    assert len(blank_line_groups) > len(TEXTS)


def test_a_wrong_number_of_groups_is_refused_not_cut_to_fit():
    assert parse_morphodita_lemma_groups(WIRE["batched"], len(TEXTS) - 1) is None
    assert parse_morphodita_lemma_groups(WIRE["batched"], len(TEXTS) + 1) is None


def test_the_batch_is_one_request():
    backend = _Backend({_joined(TEXTS): WIRE["batched"]})

    assert batch_lemmatize_morphodita(backend, TEXTS) == ALONE
    assert backend.sent == [_joined(TEXTS)]


def test_a_response_that_does_not_split_back_falls_back_to_one_request_per_text():
    answers = {t: v for t, v in zip(TEXTS, WIRE["alone"], strict=True) if t.strip()}
    answers[_joined(TEXTS)] = WIRE["blankLine"]  # no break tokens in it
    backend = _Backend(answers)

    assert batch_lemmatize_morphodita(backend, TEXTS) == ALONE
    assert backend.sent == [_joined(TEXTS)] + [t for t in TEXTS if t.strip()]


def test_a_text_holding_the_break_token_is_never_batched():
    texts = [TEXTS[2], f"{MORPHODITA_BATCH_BREAK} {TEXTS[2]}"]
    backend = _Backend(
        {
            TEXTS[2]: WIRE["alone"][2],
            texts[1]: f"{BREAK_LINE}\n{WIRE['alone'][2]}",
        }
    )

    groups = batch_lemmatize_morphodita(backend, texts)

    assert backend.sent == texts
    assert groups == [ALONE[2], [MORPHODITA_BATCH_BREAK, *ALONE[2]]]


def test_the_per_text_backend_answers_a_batch_as_the_backend_did():
    backend = _PerTextBackend(_per_text_answers())

    assert backend.result_text({"data": _joined(TEXTS)}) == WIRE["batched"]


def test_sixty_four_texts_are_one_request():
    texts, lemmas = _sixty_four()
    backend = _PerTextBackend(_per_text_answers())

    assert batch_lemmatize_morphodita(backend, texts) == lemmas
    assert backend.sent == [_joined(texts)]


def test_one_text_holding_the_break_token_costs_one_more_request_not_sixty_four():
    """A caller sending batches of 64 under one deadline: one such text is sent
    on its own, and the other 63 stay one batch."""
    texts, lemmas = _sixty_four()
    tainted, answer, tainted_lemmas = _tainted(2)
    texts[37] = tainted
    lemmas[37] = tainted_lemmas
    backend = _PerTextBackend(_per_text_answers() | {tainted: answer})

    groups = batch_lemmatize_morphodita(backend, texts)

    assert backend.sent == [_joined(texts[:37] + texts[38:]), tainted]
    assert groups == lemmas


def test_each_text_holding_the_break_token_is_sent_alone_the_rest_as_one_batch():
    first, first_answer, first_lemmas = _tainted(0)
    last, last_answer, last_lemmas = _tainted(4)
    texts = [first, TEXTS[2], TEXTS[1], last, TEXTS[3]]
    backend = _PerTextBackend(
        _per_text_answers() | {first: first_answer, last: last_answer}
    )

    groups = batch_lemmatize_morphodita(backend, texts)

    assert backend.sent == [_joined([TEXTS[2], TEXTS[1], TEXTS[3]]), first, last]
    assert groups == [first_lemmas, ALONE[2], ALONE[1], last_lemmas, ALONE[3]]


def test_no_batch_is_sent_when_only_texts_holding_the_break_token_have_words():
    first, first_answer, first_lemmas = _tainted(0)
    last, last_answer, last_lemmas = _tainted(2)
    backend = _PerTextBackend({first: first_answer, last: last_answer})

    groups = batch_lemmatize_morphodita(backend, [first, "", last])

    assert backend.sent == [first, last]
    assert groups == [first_lemmas, [], last_lemmas]


def test_a_batch_that_does_not_split_back_falls_back_for_its_own_texts_only():
    tainted, answer, tainted_lemmas = _tainted(2)
    answers = {t: v for t, v in zip(TEXTS, WIRE["alone"], strict=True) if t.strip()}
    answers[_joined(TEXTS)] = WIRE["blankLine"]  # no break tokens in it
    answers[tainted] = answer
    backend = _Backend(answers)

    groups = batch_lemmatize_morphodita(backend, [*TEXTS, tainted])

    # the text holding the break token is sent once, not again in the fallback
    assert backend.sent == [_joined(TEXTS), *[t for t in TEXTS if t.strip()], tainted]
    assert groups == [*ALONE, tainted_lemmas]


def test_blank_texts_are_not_sent():
    backend = _Backend({})

    assert batch_lemmatize_morphodita(backend, ["", "  "]) == [[], []]
    assert backend.sent == []
