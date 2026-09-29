# SPDX-License-Identifier: Apache-2.0
"""What a MorphoDiTa token looks like in UD terms.

Until this, the adapter sent a coarse `upos` and no `feats` at all, so the same
Czech question read differently on the two lanes. The Stanza lane said `Zobraz`
is `Mood=Imp`, `Který` is `PronType=Int,Rel`, and `prvních` is an ordinal
adjective; the MorphoDiTa lane said none of it, and tagged every numeral `NUM` —
which callers treat as a literal, so `prvních` and `Kolik` became values to look
up.

The fixture holds both lanes' live readings of the same 22 sentences. Stanza is
trained on UD Czech-PDT, so where its PDT tag equals MorphoDiTa's, its reading is
the UD convention for that tag; the parity test holds the adapter to it.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from ttrnlp.client.backends import parse_morphodita_vertical
from ttrnlp.client.pdt import pdt_to_ud

_FIXTURE = (
    Path(__file__).parent.parent
    / "fixtures"
    / "morphodita"
    / "ud-parity-czech-morfflex2.0-pdtc1.0-220710.json"
)
READINGS = {
    k: v for k, v in json.loads(_FIXTURE.read_text()).items() if k != "_comment"
}


def _tokens(text: str):
    tokens, _ = parse_morphodita_vertical(READINGS[text]["morphodita"], text)
    return tokens


def _by_word(text: str):
    return {t.text: t for t in _tokens(text)}


def test_an_imperative_says_it_is_one():
    for text, word in [
        ("Zobraz nejlepších 10 prodejen podle tržeb", "Zobraz"),
        ("Ukaž prvních pět zákazníků s nejvyššími náklady", "Ukaž"),
        (
            "Vypiš několik největších faktur s DPH z e-shopu firmy Marvy Oil, s.r.o.",
            "Vypiš",
        ),
        ("Seřaď prodejny sestupně, zobrazit tabulku a nezobrazovat grafy.", "Seřaď"),
    ]:
        token = _by_word(text)[word]
        assert token.upos == "VERB", word
        assert token.feats["Mood"] == "Imp", word
        assert token.feats["VerbForm"] == "Fin", word

    plural = _by_word(
        "Zobrazte si své objednávky za leden 2025, abychom viděli, že rostou."
    )
    assert plural["Zobrazte"].feats["Number"] == "Plur"
    negated = _by_word(
        "Nezobrazuj žádné prodejny, které nic neprodaly, a vše seřaď od nejmenší."
    )
    assert negated["Nezobrazuj"].feats["Polarity"] == "Neg"


def test_an_indicative_or_an_infinitive_is_not_an_imperative():
    tokens = _by_word("Seřaď prodejny sestupně, zobrazit tabulku a nezobrazovat grafy.")

    assert tokens["zobrazit"].feats["VerbForm"] == "Inf"
    assert "Mood" not in tokens["zobrazit"].feats
    assert (
        _by_word("Kde jsme prodávali víc než 20 % zboží a proč?")["prodávali"].feats[
            "VerbForm"
        ]
        == "Part"
    )


def test_an_ordinal_is_an_adjective_and_a_cardinal_stays_a_number():
    first = _by_word("Ukaž prvních pět zákazníků s nejvyššími náklady")
    assert (first["prvních"].upos, first["prvních"].feats["NumType"]) == ("ADJ", "Ord")
    assert first["prvních"].feats["Case"] == "Gen"
    assert (first["pět"].upos, first["pět"].feats["NumType"]) == ("NUM", "Card")

    top = _by_word("Zobraz nejlepších 10 prodejen podle tržeb")
    assert top["10"].upos == "NUM"
    assert top["10"].feats == {"NumForm": "Digit", "NumType": "Card"}
    assert top["nejlepších"].feats["Degree"] == "Sup"

    second = _by_word(
        "Porovnej polovinu nákladů s dvakrát vyššími tržbami za druhé čtvrtletí"
    )
    assert second["druhé"].upos == "ADJ"
    assert second["druhé"].lemma == "druhý"  # not `druhý`2`
    assert (second["dvakrát"].upos, second["dvakrát"].feats) == (
        "ADV",
        {"NumType": "Mult"},
    )


def test_a_quantifier_is_a_determiner_not_a_literal():
    tokens = _by_word("Kolik objednávek bylo zrušeno a kolik jich nebylo doručeno?")

    assert tokens["Kolik"].upos == "DET"
    assert tokens["Kolik"].feats["NumType"] == "Card"
    assert tokens["Kolik"].feats["PronType"] == "Int,Rel"
    several = _by_word(
        "Vypiš několik největších faktur s DPH z e-shopu firmy Marvy Oil, s.r.o."
    )
    assert (several["několik"].upos, several["několik"].feats["PronType"]) == (
        "DET",
        "Ind",
    )


def test_an_interrogative_determiner_says_so_and_a_relative_pronoun_does_not():
    which = _by_word("Který sklad měl loni nejvíc vratek?")["Který"]
    assert (which.upos, which.feats["PronType"]) == ("DET", "Int,Rel")
    what = _by_word("Jaké tržby by byly bez slev, kdyby se neprodávalo v Brně?")["Jaké"]
    assert (what.upos, what.feats["PronType"]) == ("DET", "Int,Rel")

    # `jež` has `který`'s tag (P4) and is neither a determiner nor a question.
    which_rel = _by_word(
        "Faktura, jejíž částka je vysoká, a smlouva, jež platí, což víme sami."
    )
    assert (which_rel["jež"].upos, which_rel["jež"].feats["PronType"]) == (
        "PRON",
        "Rel",
    )
    assert which_rel["jejíž"].upos == "DET"
    assert which_rel["jejíž"].feats["Poss"] == "Yes"


def test_the_renumbered_pdt_c_pronouns_are_read_by_lemma():
    his = _by_word("Jeho sestra a jejich firma mi to ukázaly nejlépe.")
    assert his["Jeho"].xpos.startswith("P9")  # `jenž` in PDT 2.0, a possessive here
    assert his["Jeho"].upos == "DET"
    assert his["Jeho"].feats["Poss"] == "Yes"
    assert his["mi"].feats["Variant"] == "Short"

    nothing = _by_word(
        "Někdo něco koupil, nikdo nic nevrátil a nějaký zákazník každý den platil."
    )
    assert (nothing["nic"].upos, nothing["nic"].feats["PronType"]) == ("PRON", "Neg")
    assert (nothing["Někdo"].upos, nothing["Někdo"].feats["PronType"]) == (
        "PRON",
        "Ind",
    )
    assert (nothing["nějaký"].upos, nothing["nějaký"].feats["PronType"]) == (
        "DET",
        "Ind",
    )
    # `každý` is tagged a plain adjective; UD counts it a determiner.
    assert (nothing["každý"].upos, nothing["každý"].feats["PronType"]) == ("DET", "Tot")
    assert "Degree" not in nothing["každý"].feats

    after = _by_word("Já a ty jsme s ním a pro něj prodávali, kdo co chtěl.")
    assert after["ním"].feats["PrepCase"] == "Pre"
    assert (
        _by_word("Kolik objednávek bylo zrušeno a kolik jich nebylo doručeno?")[
            "jich"
        ].feats["PrepCase"]
        == "Npr"
    )


def test_every_form_of_byt_is_an_auxiliary():
    tokens = _tokens("Jaké tržby by byly bez slev, kdyby se neprodávalo v Brně?")
    by = {t.text: t for t in tokens}

    assert by["by"].upos == "AUX"
    assert by["by"].feats["Mood"] == "Cnd"
    assert by["byly"].upos == "AUX"
    assert by["neprodávalo"].upos == "VERB"
    assert by["neprodávalo"].feats["Polarity"] == "Neg"
    assert by["kdyby"].upos == "SCONJ"
    future = _by_word(
        "Tento měsíc bude prodáno méně, protože naše pobočka v Praze je zavřená."
    )
    assert (future["bude"].upos, future["bude"].feats["Tense"]) == ("AUX", "Fut")


def test_a_passive_participle_is_an_adjective():
    tokens = _by_word("Kolik objednávek bylo zrušeno a kolik jich nebylo doručeno?")

    assert tokens["zrušeno"].upos == "ADJ"
    assert tokens["zrušeno"].feats["Voice"] == "Pass"
    assert tokens["zrušeno"].feats["VerbForm"] == "Part"
    assert tokens["zrušeno"].lemma == "zrušit"  # the lemma is MorphoDiTa's, untouched


def test_an_abbreviation_is_marked_and_a_name_is_typed():
    tokens = _by_word(
        "Vzhledem k tržbám místo nákladů ukaž tzv. obrat ČR v Kč atd. za 3. čtvrtletí."
    )

    assert (tokens["ČR"].upos, tokens["ČR"].feats) == (
        "PROPN",
        {"Abbr": "Yes", "NameType": "Geo"},
    )
    assert (tokens["Kč"].upos, tokens["Kč"].feats) == ("NOUN", {"Abbr": "Yes"})
    assert (tokens["atd"].upos, tokens["atd"].feats) == ("ADV", {"Abbr": "Yes"})
    assert tokens["tzv"].feats["Abbr"] == "Yes"
    assert tokens["Vzhledem"].feats["AdpType"] == "Comprep"

    place = _by_word(
        "Tento měsíc bude prodáno méně, protože naše pobočka v Praze je zavřená."
    )
    assert (place["Praze"].upos, place["Praze"].feats["NameType"]) == ("PROPN", "Geo")


def test_a_symbol_is_sym_and_punctuation_is_punct():
    percent = _by_word("Kde jsme prodávali víc než 20 % zboží a proč?")
    assert percent["%"].upos == "SYM"
    assert percent["?"].upos == "PUNCT"
    assert percent["než"].upos == "SCONJ"

    signs = _by_word("Zboží za 100 $ nebo 50 € + DPH § 5, tedy 2 × víc.")
    assert {w: signs[w].upos for w in ("$", "+", "§", ",", ".")} == {
        "$": "SYM",
        "+": "SYM",
        "§": "SYM",
        ",": "PUNCT",
        ".": "PUNCT",
    }


@pytest.mark.parametrize(
    ("tag", "lemma", "upos", "feats"),
    [
        # A subclass's implication fills a gap; the position still wins.
        (
            "ACYS-----2A----",
            "rád",
            "ADJ",
            {
                "Gender": "Masc",
                "Number": "Sing",
                "Degree": "Cmp",
                "Polarity": "Pos",
                "Variant": "Short",
            },
        ),
        (
            "ACYS------A----",
            "rád",
            "ADJ",
            {
                "Gender": "Masc",
                "Number": "Sing",
                "Degree": "Pos",
                "Polarity": "Pos",
                "Variant": "Short",
            },
        ),
        # Short tags, from a tagger that emits them; no tag, no guess.
        ("NP", "", "PROPN", {}),
        ("", "", "", {}),
        # An unknown numeral subclass keeps NUM, without a NumType it cannot know.
        ("Cj-P1----------", "čtvero", "NUM", {"Number": "Plur", "Case": "Nom"}),
        # `mnoho` + `krát` keeps its `k` (unlike `několikrát`).
        ("Co-------------", "mnohokrát", "ADV", {"NumType": "Mult", "PronType": "Ind"}),
    ],
)
def test_the_tag_alone(tag, lemma, upos, feats):
    assert pdt_to_ud(tag, lemma) == (upos, feats)


#: Where the adapter and Stanza differ on a token whose PDT tag they AGREE on,
#: and why. A new difference fails the parity test; so does a stale entry.
KNOWN = {
    # UD Czech-PDT: `kolik` is interrogative/relative. Stanza's feature head is wrong.
    "Kolik": {"PronType"},
    "kolik": {"PronType"},
    "což": {"PronType"},  # relative only; Stanza says Int,Rel
    # Stanza's feats contradict its own tag (predicted separately from xpos).
    "Zobrazte": {"Person"},
    "pět": {"Case"},
    # Not in the tag or the lemma: this module does not guess them.
    "kdo": {"Animacy"},
    "co": {"Animacy"},
    "Někdo": {"Animacy"},
    "něco": {"Animacy"},
    "nikdo": {"Animacy"},
    "bych": {"Number", "Person"},  # position 14's conditional code
    "dělající": {"Aspect"},
    "Otcův": {"NameType"},
    "sami": {"Variant"},
    "zavřená": {"VerbForm", "Voice"},  # read off the lemma comment `^(*3ít)`
}


def test_parity_with_stanza_where_the_two_tags_agree():
    compared, differing = 0, set()
    for text, reading in READINGS.items():
        stanza = {(t["charStart"], t["charEnd"]): t for t in reading["stanza"]}
        for token in _tokens(text):
            theirs = stanza.get((token.char_start, token.char_end))
            if theirs is None or theirs["xpos"] != token.xpos:
                continue
            compared += 1
            assert token.upos == theirs["upos"], (text, token.text)
            skip = KNOWN.get(token.text, set())
            ours = {k: v for k, v in token.feats.items() if k not in skip}
            want = {k: v for k, v in theirs["feats"].items() if k not in skip}
            assert ours == want, (text, token.text, token.xpos)
            if any(token.feats.get(k) != theirs["feats"].get(k) for k in skip):
                differing.add(token.text)

    assert compared > 150
    assert differing == set(KNOWN), "a KNOWN entry no longer differs; drop it"
