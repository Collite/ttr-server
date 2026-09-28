# SPDX-License-Identifier: Apache-2.0
"""NameTag entity offsets are where the words are in the source (ttr-server#118).

The parser used to end an entity at `start + len(" ".join(words))`. NameTag
tokenizes a hyphenated name as three words, so `Frýdku-Místku` became
`Frýdku - Místku`, two characters longer than the question, and the entity's
end ran into the next word. The resolver reads that end: it decides which
characters are a place, and which tokens a place-typed value is made of.
"""

from __future__ import annotations

from ttrnlp.client.backends import parse_nametag_conll

HYPHENATED = "Frýdku\tB-gu\n-\tI-gu\nMístku\tI-gu\na\tO\nOstravě\tB-gu\n"
QUESTION = "Prodejny ve Frýdku-Místku a Ostravě"


def test_a_hyphenated_name_ends_where_its_last_word_does():
    first, second = parse_nametag_conll(HYPHENATED, QUESTION)

    assert (first.char_start, first.char_end) == (12, 25)
    assert first.text == "Frýdku-Místku"
    assert QUESTION[first.char_start : first.char_end] == first.text
    # …so the next entity, and the word between, are untouched by it
    assert (second.char_start, second.char_end) == (28, 35)
    assert second.text == "Ostravě"


def test_every_found_entity_is_its_own_source_slice():
    conll = "Ústí\tB-gu\nn\tI-gu\n.\tI-gu\nL\tI-gu\n.\tI-gu\n"
    question = "Sklady v Ústí n. L. a jinde"
    (entity,) = parse_nametag_conll(conll, question)

    assert entity.text == "Ústí n. L."
    assert question[entity.char_start : entity.char_end] == entity.text


def test_single_spaced_words_are_unchanged():
    conll = "pražských\tB-gu\npobočkách\tI-gu\n"
    (entity,) = parse_nametag_conll(conll, "v pražských pobočkách")

    assert (entity.char_start, entity.char_end) == (2, 21)
    assert entity.text == "pražských pobočkách"
    assert entity.normalized_value == "cnec:gu"


def test_a_word_the_search_misses_keeps_the_joined_spelling():
    # NameTag normalized a quote the question spells differently: the offsets
    # cannot be trusted past the miss, so the entity falls back to the old rule.
    (entity,) = parse_nametag_conll("Hotel\tB-if\n\"\tI-if\nRex\tI-if\n", "Hotel „Rex“")

    assert entity.text == 'Hotel " Rex'
    assert (entity.char_start, entity.char_end) == (0, len('Hotel " Rex'))
