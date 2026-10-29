# SPDX-License-Identifier: Apache-2.0
"""Read-only BatchMatch recording through a temporary loopback port-forward."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from google.protobuf.json_format import MessageToDict

from docproc._generated.org.tatrman.fuzzy.v1 import fuzzy_pb2 as pb
from docproc.sdk.lexmatcher import LexMatcherClient
from docproc.sdk.models import canonical

parser = argparse.ArgumentParser()
parser.add_argument("--target", required=True)
args = parser.parse_args()
queries = [
    "Aurora Desk Lamp",
    "Aurora lamp",
    "Breeze",
    "ventilátoru Breeze",
    "Pulse Wireless Headphones",
    "Summit Kettle",
    "konvice Summit",
    "Aurora",
    "ventilátor",
    "sluchátka",
    "lamp",
]
client = LexMatcherClient(args.target)
try:
    request = [pb.SpanQuery(query=q, categories=["er.entity.item.product_name"], limit=5) for q in queries]
    response = client.batch(request)
    target = Path("tests/fixtures/lexmatcher")
    target.mkdir(parents=True, exist_ok=True)
    records = [
        {"query": q, "categories": ["er.entity.item.product_name"], "limit": 5, "response": MessageToDict(reply)}
        for q, reply in zip(queries, response.results, strict=True)
    ]
    (target / "hartland.json").write_text(canonical(records))
    print(json.dumps([{"query": r["query"], "matches": len(r["response"].get("matches", []))} for r in records]))
finally:
    client.close()
