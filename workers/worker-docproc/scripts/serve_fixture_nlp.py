# SPDX-License-Identifier: Apache-2.0
"""Local real NLP front for recording; run with services/nlp's venv and PYTHONPATH.

Uses existing local stanza/spacy model containers at 18090/18091. No fake engine.
The test-only world lists are built from PX, then the production front loads them.
"""

from __future__ import annotations

import asyncio
import csv
import json
import re
import tempfile
from pathlib import Path

import yaml
from nlp_service.api.grpc_server import serve_grpc
from nlp_service.config import AppConfig
from nlp_service.engines import EngineRegistry
from nlp_service.pipeline.orchestrator import Orchestrator

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parents[1]
PACK = ROOT / "tests/fixtures/packs/hartland-mini"


async def main() -> None:
    with tempfile.TemporaryDirectory(prefix="docproc-record-nlp-") as temp:
        lists = Path(temp)
        rows = list(csv.DictReader((PACK / "gazetteers/items.tsv").open(), delimiter="\t"))
        entries = [{"term": r["surface"], "features": {"majorType": "item", "concept": "cn.Item"}} for r in rows]
        (lists / "items.list.yaml").write_text(
            yaml.safe_dump(
                {
                    "list": "items",
                    "version": 1,
                    "matching": "ci",
                    "annotation": "Lookup",
                    "source": {"world": "hartland-mini", "origin": "PX-64ad33f6"},
                    "entries": entries,
                }
            )
        )
        texts = "\n".join(
            s["text"]
            for p in (PACK / "golden/mini-gold-v1").glob("*.expected.json")
            for s in json.loads(p.read_text())["sections"]
        )
        dates = sorted(set(re.findall(r"\b\d{4}-\d{2}-\d{2}\b", texts)))
        (lists / "dates.list.yaml").write_text(
            yaml.safe_dump(
                {
                    "list": "dates",
                    "version": 1,
                    "matching": "ci",
                    "annotation": "DATE",
                    "source": {"world": "hartland-mini", "origin": "recording-only date rules"},
                    "entries": [{"term": d, "features": {"normalized_value": d}} for d in dates]
                    + [{"term": "3 October 2026", "features": {"normalized_value": "2026-10-03"}}],
                }
            )
        )
        dates_file = lists / "dates.list.yaml"
        date_spec = yaml.safe_load(dates_file.read_text())
        date_spec["matching"] = "exact"
        date_spec["entries"].extend(
            [
                {"term": "2. září 2026", "features": {"normalized_value": "2026-09-02"}},
                {"term": "2026-10-03", "features": {"normalized_value": "2026-10-03"}},
            ]
        )
        dates_file.write_text(yaml.safe_dump(date_spec))
        modifiers = [
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
        (lists / "modifiers.list.yaml").write_text(
            yaml.safe_dump(
                {
                    "list": "modifiers",
                    "version": 1,
                    "matching": "ci",
                    "annotation": "Modifier",
                    "source": {"world": "hartland-mini", "origin": "recording-only modifier rules"},
                    "entries": [{"term": word, "features": {"kind": kind}} for word, kind in modifiers],
                }
            )
        )
        config = yaml.safe_load((REPO / "services/nlp/config.yaml").read_text())
        config["service"] = {"NLP_SERVICE_HOST": "127.0.0.1", "NLP_SERVICE_GRPC_PORT": 17271}
        config["engines"]["stanza"]["url"] = "http://127.0.0.1:18090"
        config["engines"]["spacy"]["url"] = "http://127.0.0.1:18091"
        config["packs"] = {"sources": []}
        config["lists"] = {"sources": [str(lists)]}
        config["pipelines"] = {
            "hartland-mini-mentions": {
                "ops": ["TOKENIZE", "LEMMATIZE"],
                "gazetteer": ["items", "item-lemmas", "dates", "modifiers"],
            }
        }
        # Explicit English sentence route too; otherwise service fallback chooses it.
        config["op_routing"]["SENTENCE_SPLIT.en"] = "stanza"
        cfg = AppConfig.model_validate(config)
        registry = EngineRegistry(cfg)
        orchestrator = Orchestrator(cfg, registry)
        lemma_entries = []
        for lang in sorted({row["lang"] for row in rows}):
            terms = [row["surface"] for row in rows if row["lang"] == lang]
            outcome = await asyncio.to_thread(orchestrator.batch_lemmatize, terms, lang)
            lemma_entries.extend(
                {"term": " ".join(lemmas), "features": {"majorType": "item", "concept": "cn.Item"}}
                for lemmas in outcome.results
            )
        (lists / "lemmas.list.yaml").write_text(
            yaml.safe_dump(
                {
                    "list": "item-lemmas",
                    "version": 1,
                    "matching": "lemma",
                    "annotation": "Lookup",
                    "source": {"world": "hartland-mini", "origin": "PX terms lemmatized by the real recording engine"},
                    "entries": lemma_entries,
                }
            )
        )
        server = await serve_grpc(cfg, registry)
        print("real fixture NLP ready on 127.0.0.1:17271", flush=True)
        try:
            await server.wait_for_termination()
        finally:
            await server.stop(0)


if __name__ == "__main__":
    asyncio.run(main())
