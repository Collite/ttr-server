# SPDX-License-Identifier: Apache-2.0
"""Record actual gRPC responses keyed by request hash. Never imported by CI.

Run from the worker dir: uv run python scripts/record_nlp.py --target 127.0.0.1:17271.
See tests/fixtures/nlp/README.md for the local production-front recipe.
"""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

from google.protobuf.json_format import MessageToDict

from docproc.sdk.models import Unit, canonical
from docproc.sdk.nlp import NlpClient
from tests.pack import DOCS, metadata, units_of


def main() -> None:
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--target", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1] / "tests/fixtures/nlp"
    nlp = NlpClient(args.target, timeout=120)
    try:
        for doc in (*DOCS, "offset-proof.en.txt"):
            units = (
                units_of(doc)[1:]
                if doc in DOCS
                else [
                    Unit(
                        content_text="🙂 Previously the Aurora Desk Lamp probably failed on 2026-10-03.",
                        metadata=metadata(lang="en"),
                    )
                ]
            )
            data: dict[str, list[dict]] = {rpc: [] for rpc in ("BatchLemmatize", "Analyze", "RunPipeline")}
            for unit in units:
                text, lang = unit.content_text, unit.metadata["lang"].single or ""
                digest = hashlib.sha256(text.encode()).hexdigest()
                responses = {
                    "BatchLemmatize": nlp.lemmatize([text], lang),
                    "Analyze": nlp.sentences(text, lang),
                    "RunPipeline": nlp.pipeline(text, lang, "hartland-mini-mentions", []),
                }
                for rpc, response in responses.items():
                    data[rpc].append({"sha256": digest, "language": lang, "response": MessageToDict(response)})
            for rpc, records in data.items():
                (root / f"{doc}.{rpc}.json").write_text(canonical(records))
            print(f"recorded {doc}: {len(data['Analyze'])} sections", flush=True)
    finally:
        nlp.close()


if __name__ == "__main__":
    main()
