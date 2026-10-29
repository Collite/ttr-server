# SPDX-License-Identifier: Apache-2.0
"""An in-process gRPC servicer replaying actual NLP replies by text hash."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Iterator
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from typing import Any

import grpc
from google.protobuf.json_format import ParseDict

from docproc._generated.org.tatrman.nlp.v1 import nlp_pb2 as pb
from docproc._generated.org.tatrman.nlp.v1 import nlp_pb2_grpc as rpc
from docproc.sdk.nlp import NlpClient
from tests.conftest import WORKER


class RecordedNlp(rpc.NlpServiceServicer):
    def __init__(self) -> None:
        self.responses: dict[tuple[str, str, str], dict[str, Any]] = {}
        self.calls: list[tuple[str, Any]] = []
        self.metadata: list[dict[str, str]] = []
        for path in sorted((WORKER / "tests/fixtures/nlp").glob("*.json")):
            method = path.name.rsplit(".", 2)[1]
            for record in json.loads(path.read_text()):
                self.responses[(method, record["sha256"], record["language"])] = record["response"]

    def lookup(self, method: str, text: str, language: str) -> dict[str, Any]:
        return self.responses[(method, hashlib.sha256(text.encode()).hexdigest(), language)]

    def BatchLemmatize(self, request: pb.BatchLemmatizeRequest, context: Any) -> pb.BatchLemmatizeResponse:  # noqa: N802
        self.metadata.append(dict(context.invocation_metadata()))
        self.calls.append(("BatchLemmatize", request))
        results: list[pb.LemmaList] = []
        used: dict[bytes, pb.EngineVersion] = {}
        for text in request.texts:
            response = ParseDict(self.lookup("BatchLemmatize", text, request.language), pb.BatchLemmatizeResponse())
            results.extend(response.results)
            for identity in response.used:
                used[identity.SerializeToString()] = identity
        return pb.BatchLemmatizeResponse(results=results, used=list(used.values()))

    def Analyze(self, request: pb.AnalyzeRequest, context: Any) -> pb.AnalyzeResponse:  # noqa: N802
        self.metadata.append(dict(context.invocation_metadata()))
        self.calls.append(("Analyze", request))
        assert list(request.ops) == [pb.SENTENCE_SPLIT]
        return ParseDict(self.lookup("Analyze", request.text, request.language), pb.AnalyzeResponse())

    def RunPipeline(self, request: pb.RunPipelineRequest, context: Any) -> pb.RunPipelineResponse:  # noqa: N802
        self.metadata.append(dict(context.invocation_metadata()))
        self.calls.append(("RunPipeline", request))
        assert request.pipeline == "hartland-mini-mentions"
        response = ParseDict(self.lookup("RunPipeline", request.text, request.language), pb.RunPipelineResponse())
        if request.include_types:
            for annset in response.document.annotation_sets:
                kept = [a for a in annset.annotations if a.type in request.include_types]
                del annset.annotations[:]
                annset.annotations.extend(kept)
        return response


@contextmanager
def recorded_nlp(servicer: RecordedNlp | None = None) -> Iterator[NlpClient]:
    with ThreadPoolExecutor(max_workers=2) as executor:
        server = grpc.server(executor)
        rpc.add_NlpServiceServicer_to_server(servicer or RecordedNlp(), server)  # type: ignore[no-untyped-call]
        port = server.add_insecure_port("127.0.0.1:0")
        server.start()
        nlp = NlpClient(f"127.0.0.1:{port}")
        try:
            yield nlp
        finally:
            nlp.close()
            server.stop(0).wait()
