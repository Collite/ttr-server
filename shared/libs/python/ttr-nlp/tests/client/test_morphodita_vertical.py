# SPDX-License-Identifier: Apache-2.0
"""What the MorphoDiTa adapter makes of the backend's real output.

The fixture is the backend's own `result` strings, captured from a live
`nlp-morphodita:0.11.0`. Two things were wrong with the adapter on them:

- a lemma with both a homonym index and a comment kept the index:
  `místo-1_^(fyzické_umístění)` became `místo-1`, not `místo`. A caller matching
  the lemma against a dictionary form (a glossary term) no longer found it;
- no Czech noun was ever PROPN. The adapter looked for a PDT `NP` tag, and this
  model tags `Zlín` exactly as it tags `město` (`NN…`); a proper name is marked
  in the lemma (`Zlín_;G`) or by its capital (`Kvarton`).
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from ttrnlp.client.backends import (
    parse_morphodita_lemma_groups,
    parse_morphodita_vertical,
    pdt_tag_to_upos,
)
from ttrnlp.client.pdt import lemma_stem

_FIXTURE = (
    Path(__file__).parent.parent
    / "fixtures"
    / "morphodita"
    / "vertical-czech-morfflex2.0-pdtc1.0-220710.json"
)
WIRE = {k: v for k, v in json.loads(_FIXTURE.read_text()).items() if k != "_comment"}


def _parse(text: str):
    tokens, _ = parse_morphodita_vertical(WIRE[text], text)
    return {t.text: t for t in tokens}


def test_the_homonym_index_goes_with_the_comment():
    tokens = _parse("Zobraz prodeje dodacího místa v Hradci Králové za minulý rok")

    assert tokens["místa"].lemma == "místo"
    assert tokens["Hradci"].lemma == "Hradec"
    assert tokens["Králové"].lemma == "Králové"
    assert tokens["prodeje"].lemma == "prodej"
    assert tokens["v"].lemma == "v"


def test_a_lemma_with_only_an_index_or_only_a_comment_is_unchanged_from_before():
    tokens = _parse("Kolik prodal Novák ve Zlíně a v Praze?")

    assert tokens["ve"].lemma == "v"  # `v-1`: index only
    assert tokens["Zlíně"].lemma == "Zlín"  # `Zlín_;G`: marker only
    assert tokens["Praze"].lemma == "Praha"


@pytest.mark.parametrize(
    ("raw", "stem"),
    [
        ("Frýdek-Místek_;G", "Frýdek-Místek"),  # a hyphen that is not an index stays
        ("1-2", "1-2"),  # a number keeps its shape
        ("_", "_"),  # the lemma of an underscore is not empty
        ("Shell-2_;m", "Shell"),
        ("pět-1`5", "pět"),  # a numeral's value, after the index
        ("druhý`2", "druhý"),
        ("tolik-3_^(ve_spojení_s_adj.)", "tolik"),
        ("`", "`"),  # the lemma of a backquote is not empty
    ],
)
def test_only_a_numeric_index_after_a_letter_is_stripped(raw, stem):
    assert lemma_stem(raw) == stem


def test_the_batch_path_strips_the_same_way():
    groups = parse_morphodita_lemma_groups(
        WIRE["Zobraz prodeje dodacího místa v Hradci Králové za minulý rok"], 1
    )

    assert groups[0][3] == "místo"
    assert groups[0][5] == "Hradec"


def test_a_proper_name_is_read_from_the_lemma_not_the_tag():
    tokens = _parse("Kolik prodal Novák ve Zlíně a v Praze?")

    assert tokens["Novák"].xpos.startswith("NN")  # the tag says "noun", nothing more
    assert tokens["Novák"].upos == "PROPN"  # `_;Y`
    assert tokens["Zlíně"].upos == "PROPN"  # `_;G`
    assert tokens["Praze"].upos == "PROPN"


def test_a_name_the_guesser_met_is_proper_by_its_capital():
    tokens = _parse("Obraty pobočky Kvartonu za rok 2025")

    assert tokens["Kvartonu"].upos == "PROPN"  # `Kvarton`, no marker
    assert tokens["Obraty"].upos == "NOUN"  # capitalised word, lower-case lemma
    assert tokens["pobočky"].upos == "NOUN"
    assert tokens["2025"].upos == "NUM"


def test_a_name_marker_does_not_make_a_non_noun_proper():
    assert pdt_tag_to_upos("AAIS1----1A----", "pražský_;G") == "ADJ"


def test_the_markers_decide_before_the_capital():
    tokens = _parse("Pražané a Češi nakupují Škodovky od Škody")

    assert tokens["Pražané"].upos == "NOUN"  # `Pražan_;E`: capitalised, an inhabitant
    assert tokens["Češi"].upos == "PROPN"  # `Čech_;E_;Y`: one reading is a surname
    assert tokens["Škodovky"].upos == "PROPN"  # `_;m`
    assert tokens["Škody"].upos == "PROPN"
    assert tokens["Škody"].lemma == "Škoda"  # `Škoda-1_;m`


def test_the_np_tag_still_counts_where_a_tagger_emits_it():
    assert pdt_tag_to_upos("NP", "") == "PROPN"
