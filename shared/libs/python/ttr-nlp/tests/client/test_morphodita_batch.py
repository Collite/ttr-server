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


def _joined(texts: list[str]) -> str:
    return f"\n\n{MORPHODITA_BATCH_BREAK}\n\n".join(texts)


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


def test_blank_texts_are_not_sent():
    backend = _Backend({})

    assert batch_lemmatize_morphodita(backend, ["", "  "]) == [[], []]
    assert backend.sent == []
