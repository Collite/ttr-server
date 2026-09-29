# SPDX-License-Identifier: Apache-2.0
"""One slow backend call no longer stalls the whole front.

The front serves gRPC and REST from one asyncio loop, and `Orchestrator.analyze`
is synchronous: it waits on backend HTTP. Called inline from an `async def`
handler, that wait blocked the loop, so every request in flight waited with it.
Measured on a deployment whose Czech NER went to Lindat at 5/min, where the
limiter slept up to 60 s: the sixth NER call in a minute took 59 s, and a
TOKENIZE-only request sent meanwhile took 57 s, although it never touched NER.

The tests below reproduce that shape with a NER engine that blocks for a second,
and ask whether an unrelated request is answered while it does.
"""

from __future__ import annotations

import asyncio
import threading
import time

import grpc
import httpx
import pytest

from org.tatrman.nlp.v1 import nlp_pb2, nlp_pb2_grpc
from ttrnlp.client import backends

from nlp_service.api import routes
from nlp_service.api.grpc_server import NlpServicer
from nlp_service.config import AppConfig, BackendConfig, EnginesConfig, LangidEngineConfig
from nlp_service.diagnostics import RG_NLP_004
from nlp_service.engines import EngineRegistry
from nlp_service.engines.base import EngineResult, NerEntity, NlpOp, Token
from nlp_service.pipeline.orchestrator import AnalyzeResponse, Orchestrator

SLOW_SECONDS = 1.0


def _config(*, ner_rate_limit: int = 0) -> AppConfig:
    return AppConfig(
        engines=EnginesConfig(
            morphodita=BackendConfig(
                url="http://morphodita:8080/tag",
                model="czech-morfflex2.0-pdtc1.0-220710",
                model_version="czech-morfflex2.0-pdtc1.0-220710",
            ),
            nametag3=BackendConfig(
                url="https://lindat.example/recognize",
                model="nametag3-czech-cnec2.0-240830",
                model_version="nametag3-czech-cnec2.0-240830",
                tier="REMOTE_UNPINNED",
                rate_limit_per_minute=ner_rate_limit,
            ),
            langid=LangidEngineConfig(model_version="lingua-2.0"),
        ),
        op_routing={
            "TOKENIZE.cs": "morphodita",
            "LEMMATIZE.cs": "morphodita",
            "NER.cs": "nametag3",
            "DETECT_LANGUAGE": "langid",
        },
        default_language="cs",
    )


def _tokens(text: str) -> EngineResult:
    return EngineResult(tokens=[Token(text=text, char_start=0, char_end=len(text), lemma=text)])


def _slow_ner(release: threading.Event):
    def analyze(text, lang, ops):
        # A blocking wait, as a backend HTTP call (or the old limiter's sleep) is.
        release.wait(SLOW_SECONDS)
        return EngineResult(entities=[NerEntity(text=text, label="ORGANIZATION", char_start=0, char_end=len(text))])

    return analyze


@pytest.mark.asyncio
async def test_grpc_a_slow_ner_call_does_not_hold_up_a_tokenize_one():
    registry = EngineRegistry(_config())
    release = threading.Event()
    registry.get_engine("nametag3").analyze = _slow_ner(release)
    registry.get_engine("morphodita").analyze = lambda text, lang, ops: _tokens(text)

    server = grpc.aio.server()
    nlp_pb2_grpc.add_NlpServiceServicer_to_server(NlpServicer(registry._config, registry), server)
    port = server.add_insecure_port("localhost:0")
    await server.start()
    try:
        async with grpc.aio.insecure_channel(f"localhost:{port}") as ch:
            stub = nlp_pb2_grpc.NlpServiceStub(ch)
            slow = asyncio.ensure_future(
                stub.Analyze(nlp_pb2.AnalyzeRequest(text="Octavia", language="cs", ops=[nlp_pb2.NER]))
            )
            await asyncio.sleep(0.1)  # the NER call is now blocking in its backend

            started = time.perf_counter()
            fast = await stub.Analyze(
                nlp_pb2.AnalyzeRequest(text="Praha", language="cs", ops=[nlp_pb2.TOKENIZE])
            )
            fast_seconds = time.perf_counter() - started
            assert not slow.done()

            release.set()
            slow_resp = await slow
    finally:
        await server.stop(None)

    assert [t.text for t in fast.tokens] == ["Praha"]
    assert fast_seconds < SLOW_SECONDS / 2
    assert [e.text for e in slow_resp.entities] == ["Octavia"]


@pytest.mark.asyncio
async def test_rest_a_slow_analysis_does_not_hold_up_another(monkeypatch):
    release = threading.Event()

    class _Orchestrator:
        def __init__(self, config):
            self._registry = EngineRegistry(_config())

        def analyze(self, *, text, language, ops, mode, engine_hints):
            if NlpOp.NER in ops:
                release.wait(SLOW_SECONDS)
            return AnalyzeResponse(language="cs", language_confidence=1.0, engine_used="stub",
                                   tokens=[Token(text=text, char_start=0, char_end=len(text))])

    monkeypatch.setattr(routes, "Orchestrator", _Orchestrator)
    app = routes.create_app()

    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://nlp") as client:
        slow = asyncio.ensure_future(
            client.post("/v1/analyze", json={"text": "Octavia", "language": "cs", "ops": ["NER"]})
        )
        await asyncio.sleep(0.1)

        started = time.perf_counter()
        fast = await client.post("/v1/analyze", json={"text": "Praha", "language": "cs", "ops": ["TOKENIZE"]})
        fast_seconds = time.perf_counter() - started
        assert not slow.done()

        release.set()
        slow_resp = await slow

    assert fast.status_code == 200
    assert fast_seconds < SLOW_SECONDS / 2
    assert slow_resp.status_code == 200


class _NametagResponse:
    status_code = 200

    def json(self) -> dict:
        return {"result": "Octavia\tB-if\n"}


class _HttpClient:
    def __init__(self, **_kw):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return None

    def post(self, url, json=None, data=None):
        return _NametagResponse()


class _Httpx:
    Client = _HttpClient
    TimeoutException = TimeoutError
    NetworkError = ConnectionError


def test_ner_over_the_rate_limit_is_skipped_with_rg_nlp_004_and_the_rest_still_runs(monkeypatch):
    monkeypatch.setattr(backends, "_httpx", lambda: _Httpx)

    def refuse(seconds):
        raise AssertionError(f"slept {seconds} s")

    monkeypatch.setattr(backends.time, "sleep", refuse)
    registry = EngineRegistry(_config(ner_rate_limit=1))
    registry.get_engine("morphodita").analyze = lambda text, lang, ops: _tokens(text)
    orchestrator = Orchestrator(registry._config, registry)
    ops = {NlpOp.TOKENIZE, NlpOp.NER}

    first = orchestrator.analyze(text="Octavia", language="cs", ops=ops)
    second = orchestrator.analyze(text="Octavia", language="cs", ops=ops)

    assert [e.text for e in first.entities] == ["Octavia"]
    assert second.entities == []
    assert [t.text for t in second.tokens] == ["Octavia"]
    refusal = [m for m in second.messages if m["code"] == RG_NLP_004]
    assert len(refusal) == 1
    assert refusal[0]["severity"] == "WARNING"
    assert "nametag3" in refusal[0]["message"]
    assert "NER" not in {u.op for u in second.used}
