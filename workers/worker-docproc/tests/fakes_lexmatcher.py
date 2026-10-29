# SPDX-License-Identifier: Apache-2.0
"""In-process BatchMatch replay. Real Hartland recordings and authored PX cases stay separate."""

from __future__ import annotations

import json
from collections.abc import Iterator
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from typing import Any

import grpc
from google.protobuf.json_format import ParseDict

from docproc._generated.org.tatrman.fuzzy.v1 import fuzzy_pb2 as pb
from docproc._generated.org.tatrman.fuzzy.v1 import fuzzy_pb2_grpc as rpc
from docproc.sdk.lexmatcher import LexMatcherClient
from tests.conftest import WORKER


class RecordedLexMatcher(rpc.FuzzyServiceServicer):
    def __init__(self, fixture: str = "px-authored.json") -> None:
        self.rows = json.loads((WORKER / "tests/fixtures/lexmatcher" / fixture).read_text())
        self.calls: list[Any] = []
        self.metadata: list[dict[str, str]] = []

    def BatchMatch(self, request: pb.BatchMatchRequest, context: Any) -> pb.BatchMatchResponse:  # noqa: N802
        self.calls.append(request)
        self.metadata.append(dict(context.invocation_metadata()))
        results = []
        for q in request.spans:
            row = next((r for r in self.rows if r["query"] == q.query and r["categories"] == list(q.categories)), None)
            if row is None:
                context.abort(grpc.StatusCode.NOT_FOUND, "fixture query not recorded")
            assert row is not None
            results.append(ParseDict(row["response"], pb.FuzzyMatchResponse()))
        return pb.BatchMatchResponse(results=results)


@contextmanager
def recorded_lexmatcher(replay: RecordedLexMatcher | None = None) -> Iterator[LexMatcherClient]:
    with ThreadPoolExecutor(max_workers=2) as pool:
        server = grpc.server(pool)
        rpc.add_FuzzyServiceServicer_to_server(replay or RecordedLexMatcher(), server)  # type: ignore[no-untyped-call]
        port = server.add_insecure_port("127.0.0.1:0")
        server.start()
        client = LexMatcherClient(f"127.0.0.1:{port}")
        try:
            yield client
        finally:
            client.close()
            server.stop(0).wait()
