# SPDX-License-Identifier: Apache-2.0
"""Local real NLP front for recording; run with services/nlp's venv and PYTHONPATH.

Uses existing local stanza/spacy model containers at 18090/18091. No fake engine.
The test-only world lists are built from PX (`hartland_mini_lists.py`, beside this file), then the production
front loads them.
"""

from __future__ import annotations

import asyncio
import tempfile
from pathlib import Path

import yaml
from hartland_mini_lists import build_lists, write_lists
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
        # The four lists (hartland_mini_lists.py — a cluster mounts the same files); item-lemmas from this engine.
        built = await asyncio.to_thread(
            build_lists, lambda terms, lang: orchestrator.batch_lemmatize(terms, lang).results, PACK
        )
        write_lists(built, lists)
        server = await serve_grpc(cfg, registry)
        print("real fixture NLP ready on 127.0.0.1:17271", flush=True)
        try:
            await server.wait_for_termination()
        finally:
            await server.stop(0)


if __name__ == "__main__":
    asyncio.run(main())
