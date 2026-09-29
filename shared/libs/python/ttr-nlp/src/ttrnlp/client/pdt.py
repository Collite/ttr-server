# SPDX-License-Identifier: Apache-2.0
"""A MorphoDiTa token, in Universal Dependencies terms.

MorphoDiTa's Czech model (MorfFlex CZ 2.0 / PDT-C 1.0) answers with a raw lemma
and a 15-position PDT tag. Everything downstream of the nlp front reads UD: a
`upos` and a `feats` map, which is what Stanza, the other Czech lane, emits. An
adapter that forwarded only a coarse `upos` made the two lanes read differently
on the same question: no `feats` at all (so no `Mood=Imp` on `Zobraz`, no
`PronType=Int` on `Který`), and every numeral `NUM`, which callers treat as a
literal (`prvních`, `Kolik`).

The conventions are UD Czech-PDT's, taken from the pairs Stanza (trained on that
treebank) emits, not from memory: the PDT-C tagset differs from PDT 2.0 in
places (aspect in position 13; `B` abbreviations; the pronoun subclasses were
renumbered, so `jeho` is `P9` and `nic` is `PY`). Where the tag cannot say what
UD needs, the lemma does: the pronouns are a closed class, and `jež` shares its
`P4` with `který` while being a relative PRON rather than an interrogative DET.

Deliberately NOT derived, because nothing in the tag or the lemma settles it:
the person and number of a conditional (`bych` is `Vc----------Ic-`: position
14 carries them in a code this module does not guess at), `Style` from the
variant digit, and Animacy on `kdo`/`co`.
"""

from __future__ import annotations

import re
import unicodedata

# ── The lemma ────────────────────────────────────────────────────────────────

#: A homonym index, written straight after the stem: `místo-1`, `Hradec-2`.
_HOMONYM_INDEX = re.compile(r"-\d+$")

#: A PDT term-semantic marker, `_;<code>` in a raw lemma.
_MARKER = re.compile(r"_;(.)")

#: The markers of a proper name, as UD `NameType`. `E` (an inhabitant,
#: `Pražan_;E`) is a name type but not a proper name: UD tags it a common noun,
#: although Czech capitalises it.
_NAME_TYPES = {
    "G": "Geo",
    "Y": "Giv",
    "S": "Sur",
    "K": "Com",
    "R": "Pro",
    "m": "Oth",
    "E": "Nat",
}
_PROPER_NAME_MARKERS = frozenset("GYSKRm")


def lemma_stem(lemma: str) -> str:
    """MorphoDiTa decorates a lemma: a homonym index right after the stem, the
    value of a numeral after a backquote, then comments and term-semantic
    markers, each after an `_` (`místo-1_^(fyzické_umístění)`, `pět-1`5`,
    `druhý`2`, `Hradec-2_;G`). Take the stem.

    They are removed in the reverse of the order they are written: the `_` part,
    then the numeral's value, then the index. Cutting at the `_` and stopping
    there left `místo-1` and `Shell-2` in every lemma that also carried a comment
    or a marker; leaving the backquote kept `druhý`2` in `druhé čtvrtletí`. A
    lemma that still carries either no longer matches the word as a dictionary
    writes it.

    Only a numeric index is an index (`Frýdek-Místek` is a stem), and only after a
    letter, so a lemma that IS a hyphenated number keeps its shape. A lemma that
    is nothing but the decoration (`_`, a backquote) is its own stem.
    """
    stem = lemma.split("_", 1)[0] or lemma
    stem = stem.split("`", 1)[0] or stem
    if stem[:1].isalpha():
        stem = _HOMONYM_INDEX.sub("", stem)
    return stem


def is_proper_name(raw_lemma: str) -> bool:
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
    return lemma_stem(raw_lemma)[:1].isupper()


def _name_type(raw_lemma: str) -> str:
    return ",".join(
        sorted({_NAME_TYPES[m] for m in _MARKER.findall(raw_lemma) if m in _NAME_TYPES})
    )


# ── The tag, position by position ────────────────────────────────────────────

_TAG_LENGTH = 15

_GENDER = {
    "M": {"Gender": "Masc", "Animacy": "Anim"},
    "I": {"Gender": "Masc", "Animacy": "Inan"},
    "Y": {"Gender": "Masc"},  # masculine, animacy not said
    "F": {"Gender": "Fem"},
    "N": {"Gender": "Neut"},
    "H": {"Gender": "Fem,Neut"},
    "Q": {"Gender": "Fem,Neut"},
    "T": {"Gender": "Fem,Masc", "Animacy": "Inan"},
    "Z": {"Gender": "Masc,Neut"},  # "not feminine"
}
_NUMBER = {"S": "Sing", "P": "Plur", "D": "Dual", "W": "Plur,Sing"}
_CASE = {
    "1": "Nom",
    "2": "Gen",
    "3": "Dat",
    "4": "Acc",
    "5": "Voc",
    "6": "Loc",
    "7": "Ins",
}
_POSS_GENDER = {"M": "Masc", "F": "Fem", "Z": "Masc,Neut"}
_PERSON = {"1": "1", "2": "2", "3": "3"}
_TENSE = {"P": "Pres", "F": "Fut", "R": "Past", "H": "Past,Pres"}
_DEGREE = {"1": "Pos", "2": "Cmp", "3": "Sup"}
_POLARITY = {"A": "Pos", "N": "Neg"}
_VOICE = {"A": "Act", "P": "Pass"}
_ASPECT = {"P": "Perf", "I": "Imp"}  # `B`, both aspects, is left unsaid
#: Variant letters MorfFlex puts on an abbreviation (`s.r.o.`, `tzv.`).
_ABBREVIATION_VARIANTS = frozenset("ab")


def _positional(tag: str) -> dict[str, str]:
    """What the positions say, whatever the part of speech. `-` and `X` say nothing."""
    feats: dict[str, str] = dict(_GENDER.get(tag[2], {}))
    for key, table, pos in (
        ("Number", _NUMBER, 3),
        ("Case", _CASE, 4),
        ("Gender[psor]", _POSS_GENDER, 5),
        ("Number[psor]", _NUMBER, 6),
        ("Person", _PERSON, 7),
        ("Tense", _TENSE, 8),
        ("Degree", _DEGREE, 9),
        ("Polarity", _POLARITY, 10),
        ("Voice", _VOICE, 11),
        ("Aspect", _ASPECT, 12),
    ):
        if value := table.get(tag[pos]):
            feats[key] = value
    if tag[14] in _ABBREVIATION_VARIANTS:
        feats["Abbr"] = "Yes"
    return feats


# ── The closed classes, by lemma ─────────────────────────────────────────────

_PRS = {"PronType": "Prs"}
_POSS = {"PronType": "Prs", "Poss": "Yes"}
_DEM = {"PronType": "Dem"}
_INT = {"PronType": "Int,Rel"}
_REL = {"PronType": "Rel"}
_IND = {"PronType": "Ind"}
_NEG = {"PronType": "Neg"}
_TOT = {"PronType": "Tot"}


def _lemmas(
    upos: str, feats: dict[str, str], *lemmas: str
) -> dict[str, tuple[str, dict[str, str]]]:
    return {lemma: (upos, feats) for lemma in lemmas}


#: Pronominal words: their UD part of speech and type. A DET is what agrees with
#: a noun (`který sklad`, `naše pobočka`), a PRON what stands in for one.
_PRONOMINAL: dict[str, tuple[str, dict[str, str]]] = {
    **_lemmas("PRON", _PRS, "já", "ty", "on", "my", "vy"),
    **_lemmas("PRON", {**_PRS, "Reflex": "Yes"}, "se"),
    **_lemmas("DET", _POSS, "můj", "tvůj", "náš", "váš", "jeho", "její"),
    **_lemmas("DET", {**_POSS, "Reflex": "Yes"}, "svůj"),
    **_lemmas(
        "DET",
        _DEM,
        "ten",
        "tento",
        "tenhle",
        "tenhleten",
        "tamten",
        "onen",
        "takový",
        "takovýto",
        "takovýhle",
        "tentýž",
        "týž",
        "tolik",
    ),
    **_lemmas("DET", _INT, "který", "jaký", "čí", "kolik"),
    **_lemmas("PRON", _INT, "kdo", "co"),
    **_lemmas("PRON", _REL, "jenž", "což"),
    **_lemmas("DET", {**_REL, "Poss": "Yes"}, "jehož"),
    **_lemmas(
        "DET",
        _IND,
        "nějaký",
        "některý",
        "něčí",
        "jakýsi",
        "kterýsi",
        "jakýkoli",
        "jakýkoliv",
        "kterýkoli",
        "kterýkoliv",
        "několik",
        "mnoho",
        "málo",
    ),
    **_lemmas(
        "PRON",
        _IND,
        "někdo",
        "něco",
        "kdosi",
        "cosi",
        "kdokoli",
        "kdokoliv",
        "cokoli",
        "cokoliv",
        "leccos",
        "leckdo",
    ),
    **_lemmas("DET", _NEG, "žádný", "ničí"),
    **_lemmas("PRON", _NEG, "nikdo", "nic"),
    **_lemmas("DET", _TOT, "všechen", "každý"),
    **_lemmas("DET", {"PronType": "Emp"}, "sám"),
}

#: Adjectives UD counts as determiners. The tag says `AA`; the word is a quantifier.
_ADJECTIVAL_DETERMINERS = frozenset({"každý"})

#: Pronominal adverbs (`Db`): the tag says only "adverb".
_PRONOMINAL_ADVERBS: dict[str, str] = {
    **dict.fromkeys(("kde", "kdy", "jak", "proč", "kam", "odkud", "kudy"), "Int,Rel"),
    **dict.fromkeys(
        ("tam", "tady", "tu", "zde", "sem", "tehdy", "teď", "tak", "odtud", "tolik"),
        "Dem",
    ),
    **dict.fromkeys(("někde", "někdy", "nějak", "někam", "odněkud"), "Ind"),
    **dict.fromkeys(("nikde", "nikdy", "nijak", "nikam", "odnikud"), "Neg"),
    **dict.fromkeys(("vždy", "vždycky", "všude"), "Tot"),
}

#: Signs PDT tags as punctuation (`Z:`) and UD as SYM. Unicode files `%` and `§`
#: under punctuation, so the category test alone would miss the two that matter
#: most in a question (`20 %`, `§ 5`).
_SYMBOLS = frozenset("%‰§#&@")


# ── The tag, as a whole ──────────────────────────────────────────────────────

#: PDT major POS -> UD POS, before the subclass and the lemma have their say.
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

#: The subclass of an abbreviation (`BN` DPH, `Bb` atd.) names the POS it stands for.
_ABBREVIATED_UPOS = {"N": "NOUN", "A": "ADJ", "V": "VERB", "D": "ADV", "b": "ADV"}

#: Numeral subclasses whose UD reading is settled. Any other stays NUM with only
#: what its positions say.
_NUMERALS: dict[str, tuple[str, dict[str, str]]] = {
    "=": ("NUM", {"NumForm": "Digit", "NumType": "Card"}),  # 10
    "}": ("NUM", {"NumForm": "Roman", "NumType": "Card"}),  # XIV
    "l": ("NUM", {"NumForm": "Word", "NumType": "Card"}),  # tři, pět
    "n": ("NUM", {"NumForm": "Word", "NumType": "Card"}),  # oba, deset
    "a": ("DET", {"NumType": "Card"}),  # kolik, několik
    "r": ("ADJ", {"NumType": "Ord"}),  # první, desátý
    "w": ("ADJ", {"NumType": "Ord"}),  # kolikátý
    "d": ("ADJ", {"NumType": "Mult"}),  # dvojí
    "v": ("ADV", {"NumType": "Mult"}),  # dvakrát
    "o": ("ADV", {"NumType": "Mult"}),  # několikrát
}

#: Verb subclasses: the form, then what the subclass implies that the positions
#: leave out. `Vs` (a passive participle, `prodáno`) is an ADJ in UD Czech.
_VERB_FORMS: dict[str, dict[str, str]] = {
    "B": {"VerbForm": "Fin", "Mood": "Ind"},
    "t": {"VerbForm": "Fin", "Mood": "Ind"},
    "i": {"VerbForm": "Fin", "Mood": "Imp"},
    "c": {"VerbForm": "Fin", "Mood": "Cnd"},
    "f": {"VerbForm": "Inf"},
    "p": {"VerbForm": "Part"},
    "q": {"VerbForm": "Part"},
    "s": {"VerbForm": "Part", "Variant": "Short", "Degree": "Pos"},
    "e": {"VerbForm": "Conv", "Tense": "Pres", "Voice": "Act"},
    "m": {"VerbForm": "Conv", "Tense": "Past", "Voice": "Act"},
}

_ADJECTIVE_FORMS: dict[str, dict[str, str]] = {
    "C": {"Variant": "Short", "Degree": "Pos"},  # rád
    "U": {"Poss": "Yes"},  # otcův
    "G": {"VerbForm": "Part", "Tense": "Pres", "Voice": "Act"},  # dělající
    "M": {"VerbForm": "Part", "Tense": "Past", "Voice": "Act"},  # přišedší
}


def _imply(feats: dict[str, str], implied: dict[str, str]) -> None:
    """What a subclass implies fills a gap; it never overrides a position."""
    for key, value in implied.items():
        feats.setdefault(key, value)


def pdt_to_ud(
    tag: str, raw_lemma: str = "", word: str = ""
) -> tuple[str, dict[str, str]]:
    """A PDT-C tag as `(upos, feats)`. `raw_lemma` is the lemma as MorphoDiTa sent
    it, markers and all; `word` is the token's text.

    Empty in, empty out: a token the backend did not tag gets no POS rather than a
    guessed one.
    """
    if not tag:
        return "", {}
    tag = tag.ljust(_TAG_LENGTH, "-")
    pos, sub = tag[0], tag[1]
    stem = lemma_stem(raw_lemma)
    feats = _positional(tag)

    if pos == "N" or (pos == "B" and sub == "N"):
        # A noun's polarity is lexical (UD Czech marks none on `tržba`, `A` in the
        # tag or not), and a name's type is in its lemma.
        if feats.get("Polarity") == "Pos":
            del feats["Polarity"]
        if name_type := _name_type(raw_lemma):
            feats["NameType"] = name_type
        if pos == "B":
            feats["Abbr"] = "Yes"
            # An abbreviation's lemma is capitalised whatever it is (`DPH`, `Kč`),
            # so only a marker makes it a name (`ČR_;G`).
            named = bool(_MARKER.search(raw_lemma)) and is_proper_name(raw_lemma)
            return ("PROPN" if named else "NOUN"), feats
        proper = sub == "P" or is_proper_name(raw_lemma)
        return ("PROPN" if proper else "NOUN"), feats

    if pos == "B":
        feats["Abbr"] = "Yes"
        return _ABBREVIATED_UPOS.get(sub, "X"), feats

    if pos == "Q":
        # A word segment (`e` in `e-shop`): UD Czech-PDT reads an abbreviated noun.
        return "NOUN", {**feats, "Abbr": "Yes"}

    if pos == "F":
        return "X", {**feats, "Foreign": "Yes"}

    if pos == "C":
        upos, implied = _NUMERALS.get(sub, ("NUM", {}))
        _imply(feats, implied)
        # `kolik` (Ca) and `kolikrát` (Co) are pronominal: the type is the quantifier's.
        # The compound drops a `k` where the stem ends in one (`několik` + `krát` is
        # `několikrát`) and keeps both otherwise (`mnohokrát`).
        if sub in ("a", "o"):
            candidates = (stem, stem.removesuffix("rát"), stem.removesuffix("krát"))
            if quantifier := next((q for q in candidates if q in _PRONOMINAL), None):
                _imply(feats, _PRONOMINAL[quantifier][1])
        return upos, feats

    if pos == "P":
        upos, implied = _PRONOMINAL.get(stem, ("", {}))
        if not upos:
            # Not a lemma we know: possessive positions make it agree like a DET.
            upos = "DET" if tag[5] != "-" or tag[6] != "-" else "PRON"
        _imply(feats, implied)
        if sub in ("7", "H"):
            feats["Variant"] = "Short"  # se, si, mi, mu
        initial = word[:1].lower()
        if stem == "on" and initial in ("n", "j") and tag[4] not in ("1", "-", "X"):
            # `ním`, `něj` after a preposition; `jich`, `jí` without one.
            feats["PrepCase"] = "Pre" if initial == "n" else "Npr"
        return upos, feats

    if pos == "V":
        _imply(feats, _VERB_FORMS.get(sub, {}))
        if sub == "s":
            return "ADJ", feats
        return ("AUX" if stem == "být" else "VERB"), feats

    if pos == "A":
        _imply(feats, _ADJECTIVE_FORMS.get(sub, {}))
        if stem in _ADJECTIVAL_DETERMINERS:
            upos, implied = _PRONOMINAL[stem]
            for key in ("Degree", "Polarity"):  # a determiner has neither
                feats.pop(key, None)
            _imply(feats, implied)
            return upos, feats
        return "ADJ", feats

    if pos == "D":
        if pron_type := _PRONOMINAL_ADVERBS.get(stem):
            feats["PronType"] = pron_type
        return "ADV", feats

    if pos == "R":
        feats["AdpType"] = {"V": "Voc", "F": "Comprep"}.get(sub, "Prep")
        return "ADP", feats

    if pos == "J":
        return ("SCONJ" if sub == "," else "CCONJ"), feats

    if pos == "Z":
        symbol = bool(word) and all(
            c in _SYMBOLS or unicodedata.category(c)[0] == "S" for c in word
        )
        return ("SYM" if symbol else "PUNCT"), {}

    return _PDT_TO_UPOS.get(pos, "X"), feats


def pdt_tag_to_upos(pdt_tag: str, lemma: str = "") -> str:
    """The UD POS alone. `lemma` is the RAW lemma.

    A proper noun matters because `upos: PROPN` is what the invoices hero's
    default-lane fallback rule matches on — the one thing that still finds
    "Microsoft" when cs NER is unrouted — and what callers build name runs from.
    But the tag does not say it: the Czech model tags `Zlín` and `město` alike,
    `NN…`. The lemma does (`Zlín_;G`, `Novák_;Y`, a capitalised stem — see
    `is_proper_name`), so a noun is PROPN when its raw lemma says so. `NP` is kept
    for a tagset that does emit it.
    """
    return pdt_to_ud(pdt_tag, lemma)[0]


__all__ = ["is_proper_name", "lemma_stem", "pdt_tag_to_upos", "pdt_to_ud"]
