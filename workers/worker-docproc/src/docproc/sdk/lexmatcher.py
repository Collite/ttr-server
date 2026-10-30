# SPDX-License-Identifier: Apache-2.0
"""BatchMatch only; no per-mention REST calls or implicit cross-category search."""

from __future__ import annotations

from typing import Protocol

import grpc
from opentelemetry.propagate import inject

from docproc._generated.org.tatrman.fuzzy.v1 import fuzzy_pb2 as pb
from docproc._generated.org.tatrman.fuzzy.v1 import fuzzy_pb2_grpc as rpc


class LexMatcher(Protocol):
    def batch(self, queries: list[pb.SpanQuery]) -> pb.BatchMatchResponse: ...


class LexMatcherClient:
    def __init__(self, target: str, timeout: float = 30) -> None:
        self.channel = grpc.insecure_channel(target) if target else None
        self.stub = rpc.FuzzyServiceStub(self.channel) if self.channel else None  # type: ignore[no-untyped-call]
        self.timeout = timeout

    def batch(self, queries: list[pb.SpanQuery]) -> pb.BatchMatchResponse:
        if self.stub is None:
            raise ValueError("link/endpoint-required: LEXMATCHER_GRPC")
        carrier: dict[str, str] = {}
        inject(carrier)
        response: pb.BatchMatchResponse = self.stub.BatchMatch(
            pb.BatchMatchRequest(spans=queries), timeout=self.timeout, metadata=tuple(carrier.items())
        )
        if len(response.results) != len(queries):
            raise ValueError("link/result-cardinality")
        if any(r.is_error for r in response.results):
            raise ValueError("link/lexmatcher-error")
        return response

    def close(self) -> None:
        if self.channel:
            self.channel.close()
