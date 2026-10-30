# SPDX-License-Identifier: Apache-2.0
"""The model-free gRPC NLP client. Responses retain their S-1 engine identities."""

from __future__ import annotations

from typing import Any, Protocol

import grpc
from opentelemetry.propagate import inject

from docproc._generated.org.tatrman.nlp.v1 import nlp_pb2 as pb
from docproc._generated.org.tatrman.nlp.v1 import nlp_pb2_grpc as rpc


class Nlp(Protocol):
    def lemmatize(self, texts: list[str], language: str) -> pb.BatchLemmatizeResponse: ...
    def sentences(self, text: str, language: str) -> pb.AnalyzeResponse: ...
    def pipeline(self, text: str, language: str, name: str, include_types: list[str]) -> pb.RunPipelineResponse: ...


class NlpClient:
    def __init__(self, target: str, timeout: float = 30) -> None:
        self.channel = grpc.insecure_channel(target) if target else None
        self.stub = rpc.NlpServiceStub(self.channel) if self.channel else None  # type: ignore[no-untyped-call]
        self.timeout = timeout

    def close(self) -> None:
        if self.channel:
            self.channel.close()

    def _metadata(self) -> tuple[tuple[str, str], ...]:
        carrier: dict[str, str] = {}
        inject(carrier)
        return tuple(carrier.items())

    def lemmatize(self, texts: list[str], language: str) -> pb.BatchLemmatizeResponse:
        if self.stub is None:
            raise ValueError("nlp/endpoint-required: NLP_GRPC")
        response: pb.BatchLemmatizeResponse = self.stub.BatchLemmatize(
            pb.BatchLemmatizeRequest(texts=texts, language=language), timeout=self.timeout, metadata=self._metadata()
        )
        return response

    def sentences(self, text: str, language: str) -> pb.AnalyzeResponse:
        if self.stub is None:
            raise ValueError("nlp/endpoint-required: NLP_GRPC")
        response: pb.AnalyzeResponse = self.stub.Analyze(
            pb.AnalyzeRequest(text=text, language=language, ops=[pb.SENTENCE_SPLIT]),
            timeout=self.timeout,
            metadata=self._metadata(),
        )
        return response

    def pipeline(self, text: str, language: str, name: str, include_types: list[str]) -> pb.RunPipelineResponse:
        if self.stub is None:
            raise ValueError("nlp/endpoint-required: NLP_GRPC")
        response: pb.RunPipelineResponse = self.stub.RunPipeline(
            pb.RunPipelineRequest(text=text, language=language, pipeline=name, include_types=include_types),
            timeout=self.timeout,
            metadata=self._metadata(),
        )
        return response


def identities(used: Any, *, required: bool = True) -> list[dict[str, str]]:
    result = [{"op": ev.op, "engine": ev.engine, "model": ev.model, "modelVersion": ev.model_version} for ev in used]
    if (required and not result) or any(not all(ev.values()) for ev in result):
        raise ValueError("nlp/no-model-identity")
    return result


def feature(value: pb.FeatureValue) -> Any:
    which = value.WhichOneof("kind")
    if which == "list_value":
        return [feature(v) for v in value.list_value.items]
    return getattr(value, which) if which else None
