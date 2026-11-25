# SPDX-License-Identifier: Apache-2.0
"""OL W1c·S3a·T2 — the HTTP gateway client (OL-C13, D-43), against an HTTP fake.

The fake is a real HTTP server on the loopback (bound to 127.0.0.1): what it records is what
went over the wire — path, headers, body — and what it answers is what the client parses.
Five behaviours:

1. the exact request: path, bearer key, non-streaming JSON at temperature 0, model precedence;
2. a completion comes back as its text, its SERVED model and its id, inside the bound;
3. no answer (refused connection, timeout) or a non-2xx status is `LANE_UNAVAILABLE`, with the
   status and the error code only — never the gateway's message, never the key;
4. a 2xx reply without usable text is `no_completion`, and text that is not the lane's JSON
   object is the lane's `QUERY_NOT_WRITTEN` — through the real client;
5. one call is one request: no retry, whatever went wrong.

This does not replace the live proof on bp-dsk (the stage's T5); it pins the client's side.
"""

from __future__ import annotations

import asyncio
import json
import threading
import time
from collections.abc import Iterator
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

import pytest

from golem_py.errors import ConfigInvalid, LaneReplyRefused, LaneServiceUnavailable
from golem_py.graph import run_turn
from golem_py.llm_client import SERVICE, LiveLlmGateway, LlmPort, chat_endpoint
from golem_py.outputs import RefusalWithGaps
from tests.grpc_fakes import unused_target
from tests.helpers import h1_lattice
from tests.test_llm_lane import ROWS, FakeDoor, FakeSlice, _state, lane_deps

KEY = "ttrk-test-0000-not-a-real-key"
CONTENT = '{"sql": "SELECT 1", "handled_spans": [], "note": ""}'


def completion(
    content: Any = CONTENT, *, model: str = "gpt-6.1-sol-2026-09-29", finish: Any = "stop"
) -> bytes:
    return json.dumps(
        {
            "id": "chatcmpl-1",
            "object": "chat.completion",
            "model": model,
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": content},
                    "finish_reason": finish,
                }
            ],
        }
    ).encode()


def gateway_error(status: int, code: str | None, message: str) -> tuple[int, bytes]:
    body = {"error": {"message": message, "type": "invalid_request_error", "code": code}}
    return status, json.dumps(body).encode()


@dataclass
class Seen:
    path: str
    headers: dict[str, str]
    body: dict[str, Any]


@dataclass
class FakeGateway:
    """One canned answer per request: `(status, body)` or `("sleep", seconds)`."""

    answers: list[Any] = field(default_factory=list)
    seen: list[Seen] = field(default_factory=list)
    base: str = ""


@pytest.fixture
def fake() -> Iterator[FakeGateway]:
    state = FakeGateway()

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:  # noqa: N802
            raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
            state.seen.append(Seen(self.path, dict(self.headers), json.loads(raw or b"{}")))
            answer = state.answers.pop(0) if state.answers else (200, completion())
            if answer[0] == "sleep":
                time.sleep(float(answer[1]))
                answer = (200, completion())
            status, body = answer
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_: Any) -> None:
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    thread = threading.Thread(
        target=server.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True
    )
    thread.start()
    state.base = f"http://127.0.0.1:{server.server_address[1]}/v1"
    try:
        yield state
    finally:
        server.shutdown()
        server.server_close()


def client(base: str, **kw: Any) -> LiveLlmGateway:
    return LiveLlmGateway(base, api_key=KEY, **kw)


async def ask(gw: LiveLlmGateway, timeout_s: float = 5.0) -> Any:
    return await gw.complete_json(instructions="SYS", prompt="USER", timeout_s=timeout_s)


# 1 ------------------------------------------------------------------------------------------
@pytest.mark.parametrize(
    ("kw", "routing"),
    [
        ({}, {"model_tags": ["smart"]}),
        ({"model_class": "fast"}, {"model_tags": ["fast"]}),
        ({"model": "gpt-4.1", "model_class": "smart"}, {"model": "gpt-4.1"}),
    ],
    ids=["class-by-default", "configured-class", "explicit-model-wins"],
)
async def test_the_request_is_one_bearer_authenticated_json_chat_call(
    fake: FakeGateway, kw: dict[str, str], routing: dict[str, Any]
) -> None:
    await ask(client(fake.base + "/", **kw))  # a trailing slash is tolerated, not doubled

    [seen] = fake.seen
    assert seen.path == "/v1/chat/completions"
    assert seen.headers["Authorization"] == f"Bearer {KEY}"
    assert (
        seen.body
        == {
            "messages": [
                {"role": "system", "content": "SYS"},
                {"role": "user", "content": "USER"},
            ],
            "stream": False,
            "temperature": 0,
            "response_format": {"type": "json_object"},
        }
        | routing
    )


# 2 ------------------------------------------------------------------------------------------
async def test_a_completion_returns_its_text_and_the_served_model(fake: FakeGateway) -> None:
    reply = await ask(client(fake.base))

    assert reply.text == CONTENT
    assert reply.model == "gpt-6.1-sol-2026-09-29"  # the SERVED model, not the class asked
    assert reply.response_id == "chatcmpl-1" and reply.status == "stop"


@pytest.mark.parametrize(
    "finish",
    [["length"], {"x": 1}, 7, "invalid status", "length\n"],
    ids=["list", "object", "number", "non-token", "newline"],
)
async def test_a_completion_ignores_invalid_finish_reason(fake: FakeGateway, finish: Any) -> None:
    fake.answers.append((200, completion(finish=finish)))

    reply = await ask(client(fake.base))

    assert reply.text == CONTENT
    assert reply.status == ""


# 3 ------------------------------------------------------------------------------------------
@pytest.mark.parametrize(
    ("answer", "detail"),
    [
        (
            gateway_error(401, "invalid_api_key", f"key {KEY} is revoked"),
            "HTTP 401 invalid_api_key",
        ),
        (
            gateway_error(404, "model_not_found", "unknown model 'model_tags=[x]'"),
            "HTTP 404 model_not_found",
        ),
        (gateway_error(502, "upstream_error", "azure said no"), "HTTP 502 upstream_error"),
        (
            gateway_error(400, None, "prompt too long: SYS USER …"),
            "HTTP 400 invalid_request_error",  # `code: null` → the type (review-357 F5)
        ),
        ((500, b"<html>proxy error</html>"), "HTTP 500"),
        ((503, json.dumps({"error": {"code": "Bearer ttrk-echo"}}).encode()), "HTTP 503"),
        (("sleep", 1.5), "timeout after 0.5s"),
    ],
    ids=["401", "404", "502", "400-null-code", "500-not-json", "503-odd-code", "timeout"],
)
async def test_no_answer_or_a_non_2xx_status_is_lane_unavailable_and_sanitized(
    fake: FakeGateway, answer: Any, detail: str
) -> None:
    fake.answers.append(answer)

    with pytest.raises(LaneServiceUnavailable) as caught:
        await ask(client(fake.base), timeout_s=0.5)

    assert caught.value.service == SERVICE
    assert caught.value.detail == detail
    assert KEY not in str(caught.value)
    assert "revoked" not in str(caught.value) and "too long" not in str(caught.value)


async def test_a_refused_connection_is_lane_unavailable() -> None:
    with pytest.raises(LaneServiceUnavailable) as caught:
        await ask(client(f"http://{unused_target()}/v1"), timeout_s=2.0)

    assert caught.value.service == SERVICE
    assert caught.value.detail.startswith("transport: ")


async def test_the_whole_call_is_bounded_by_the_timeout(fake: FakeGateway) -> None:
    fake.answers.append(("sleep", 3.0))
    started = asyncio.get_running_loop().time()

    with pytest.raises(LaneServiceUnavailable, match="timeout"):
        await ask(client(fake.base), timeout_s=0.3)

    assert asyncio.get_running_loop().time() - started < 1.5


# 4 ------------------------------------------------------------------------------------------
@pytest.mark.parametrize(
    ("body", "detail"),
    [
        (json.dumps({"id": "x", "model": "m", "choices": []}).encode(), "no choices"),
        (completion(""), "empty"),
        (completion(None), "empty"),
        (b"not json at all", "not JSON"),
        # valid-looking text, but cut off or filtered: named as such (review-357 F5)
        (completion(finish="length"), "stopped early (finish_reason length)"),
        (completion(finish="content_filter"), "stopped early (finish_reason content_filter)"),
    ],
    ids=["no-choices", "empty-content", "null-content", "body-not-json", "length", "filtered"],
)
async def test_a_2xx_reply_without_completion_text_is_no_completion(
    fake: FakeGateway, body: bytes, detail: str
) -> None:
    fake.answers.append((200, body))

    with pytest.raises(LaneReplyRefused) as caught:
        await ask(client(fake.base))

    assert caught.value.rule == "no_completion"
    assert detail in caught.value.detail


@pytest.mark.parametrize(
    "body", [completion("this is prose, not an object"), completion(None)], ids=["prose", "null"]
)
async def test_through_the_lane_an_unusable_completion_is_query_not_written(
    fake: FakeGateway, body: bytes
) -> None:
    fake.answers.append((200, body))
    door = FakeDoor(ROWS)
    deps = lane_deps(h1_lattice(), llm=client(fake.base), door=door, model_slice=FakeSlice())

    out = await run_turn(_state(h1_lattice()), deps)

    assert isinstance(out, RefusalWithGaps)
    assert out.reason == "QUERY_NOT_WRITTEN"
    assert door.calls == []  # never rows from a query nobody wrote


# 5 ------------------------------------------------------------------------------------------
@pytest.mark.parametrize(
    "answer",
    [
        gateway_error(502, "upstream_error", "x"),
        gateway_error(429, "rate_limit_exceeded", "x"),
        ("sleep", 0.6),
    ],
    ids=["502", "429", "timeout"],
)
async def test_one_call_is_one_request_with_no_hidden_retry(fake: FakeGateway, answer: Any) -> None:
    fake.answers.append(answer)

    with pytest.raises(LaneServiceUnavailable):
        await ask(client(fake.base), timeout_s=0.2)
    await asyncio.sleep(0.3)  # a retry, had there been one, would have landed by now

    assert len(fake.seen) == 1


def test_the_live_client_satisfies_the_port_and_never_shows_its_key() -> None:
    gw = client("http://llm-gateway:7280/v1")

    assert isinstance(gw, LlmPort)
    assert KEY not in repr(gw) and KEY not in str(vars(gw))


# Config — refused when the client is built, never mid-turn, never echoing a secret ----------
SENTINEL = "ttrk-SENTINEL"


@pytest.mark.parametrize(
    "target",
    [
        f"http://gw:7280/v1?api_key={SENTINEL}",
        SENTINEL,  # the two variables swapped
        f"http://gw:7280/v1#{SENTINEL}",
        f"http://gw:7280/{SENTINEL}",
        f"http://golem:{SENTINEL}@gw:7280/v1",
    ],
    ids=["query", "swapped", "fragment", "path", "userinfo"],
)
def test_a_bad_target_is_refused_without_echoing_it(target: str) -> None:
    for build in (chat_endpoint, lambda t: LiveLlmGateway(t, api_key=KEY)):
        with pytest.raises(ConfigInvalid) as caught:
            build(target)
        assert "SENTINEL" not in str(caught.value)
        assert "GOLEM_LLM_TARGET" in str(caught.value)


def test_the_client_checks_its_own_target() -> None:
    # A `Deps` built in code, with no CLI startup check, still cannot hold an unchecked URL
    # (review-357 F3): the old gRPC `host:port` form is refused, not a `ValueError` mid-turn.
    with pytest.raises(ConfigInvalid, match="http"):
        LiveLlmGateway("llm-gateway:9090", api_key="k")


@pytest.mark.parametrize("key", ["ttrk-ab\u00a0cd", "ttrk-ab\u201ccd", "ttrk-ab cd"])
def test_a_key_no_header_can_carry_is_refused_without_quoting_it(key: str) -> None:
    with pytest.raises(ConfigInvalid) as caught:
        LiveLlmGateway("http://gw:7280/v1", api_key=key)

    assert "GOLEM_LLM_API_KEY" in str(caught.value)
    assert "ab" not in str(caught.value) and "cd" not in str(caught.value)
