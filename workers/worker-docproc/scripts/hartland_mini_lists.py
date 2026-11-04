# SPDX-License-Identifier: Apache-2.0
"""The hartland-mini world's nlp gazetteer lists, built from the PX pack — what `cz-mentions`'s pipeline
`hartland-mini-mentions` matches against (TOKENIZE, LEMMATIZE; gazetteer items, item-lemmas, dates, modifiers).

`serve_fixture_nlp.py` builds the recordings' front from these; a cluster mounts the same files as nlp's
`lists.configMapName` (DX-P3 T8f), with the pipeline in its `NLP_PIPELINES`. `item-lemmas` is the item surfaces
lemmatized by the front that will match them, so a cluster's copy is built against that cluster's nlp:

    uv run python scripts/hartland_mini_lists.py --target 127.0.0.1:17271 --out <dir>

Never imported by CI. Test-only content: the world is the PX fixture pack (NL-17: content is per world).
"""

from __future__ import annotations

import argparse
import csv
import json
import re
from collections.abc import Callable
from pathlib import Path
from typing import Any

import yaml

PACK = Path(__file__).resolve().parents[1] / "tests/fixtures/packs/hartland-mini"
MODIFIERS = [
    ("dříve", "temporal_context"),
    ("předtím", "temporal_context"),
    ("previously", "temporal_context"),
    ("earlier", "temporal_context"),
    ("already", "temporal_context"),
    ("dřív", "temporal_context"),
    ("probably", "certainty"),
    ("asi", "certainty"),
    ("možná", "certainty"),
]

# (terms, language) → each term's lemmas, in order
Lemmatize = Callable[[list[str], str], list[list[str]]]


def build_lists(lemmatize: Lemmatize, pack: Path = PACK) -> dict[str, dict[str, Any]]:
    """File name → list spec, the four lists of the recordings' front."""
    rows = list(csv.DictReader((pack / "gazetteers/items.tsv").open(), delimiter="\t"))

    def item() -> dict[str, str]:  # one dict per entry: a shared one is a YAML alias in safe_dump's output
        return {"majorType": "item", "concept": "cn.Item"}

    texts = "\n".join(
        s["text"]
        for p in (pack / "golden/mini-gold-v1").glob("*.expected.json")
        for s in json.loads(p.read_text())["sections"]
    )
    dates = sorted(set(re.findall(r"\b\d{4}-\d{2}-\d{2}\b", texts)))
    lemma_entries = []
    for lang in sorted({row["lang"] for row in rows}):
        terms = [row["surface"] for row in rows if row["lang"] == lang]
        lemma_entries.extend({"term": " ".join(lemmas), "features": item()} for lemmas in lemmatize(terms, lang))
    return {
        "items.list.yaml": {
            "list": "items",
            "version": 1,
            "matching": "ci",
            "annotation": "Lookup",
            "source": {"world": "hartland-mini", "origin": "PX-64ad33f6"},
            "entries": [{"term": r["surface"], "features": item()} for r in rows],
        },
        "dates.list.yaml": {
            "list": "dates",
            "version": 1,
            "matching": "exact",
            "annotation": "DATE",
            "source": {"world": "hartland-mini", "origin": "recording-only date rules"},
            "entries": [{"term": d, "features": {"normalized_value": d}} for d in dates]
            + [
                {"term": "3 October 2026", "features": {"normalized_value": "2026-10-03"}},
                {"term": "2. září 2026", "features": {"normalized_value": "2026-09-02"}},
                {"term": "2026-10-03", "features": {"normalized_value": "2026-10-03"}},
            ],
        },
        "modifiers.list.yaml": {
            "list": "modifiers",
            "version": 1,
            "matching": "ci",
            "annotation": "Modifier",
            "source": {"world": "hartland-mini", "origin": "recording-only modifier rules"},
            "entries": [{"term": word, "features": {"kind": kind}} for word, kind in MODIFIERS],
        },
        "lemmas.list.yaml": {
            "list": "item-lemmas",
            "version": 1,
            "matching": "lemma",
            "annotation": "Lookup",
            "source": {"world": "hartland-mini", "origin": "PX terms lemmatized by the real recording engine"},
            "entries": lemma_entries,
        },
    }


def write_lists(lists: dict[str, dict[str, Any]], out: Path) -> None:
    for name, spec in lists.items():
        (out / name).write_text(yaml.safe_dump(spec))


def main() -> None:
    from docproc.sdk.nlp import NlpClient

    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--target", required=True, help="the nlp front's gRPC address")
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    nlp = NlpClient(args.target, timeout=120)
    try:
        lists = build_lists(lambda terms, lang: [list(r.lemmas) for r in nlp.lemmatize(terms, lang).results])
    finally:
        nlp.close()
    args.out.mkdir(parents=True, exist_ok=True)
    write_lists(lists, args.out)
    print(f"wrote {', '.join(lists)} to {args.out}")


if __name__ == "__main__":
    main()
