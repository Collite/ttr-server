# SPDX-License-Identifier: Apache-2.0
"""OL W1c·S1·T2 (d) — the `llm.v1` gateway client (OL-C13), over an in-process fake.

The fake is a real `grpc.aio` server on the loopback: what it records is what went over
the wire, deadline included.
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

import grpc
import pytest

_GENERATED = Path(__file__).resolve().parents[1] / "generated"
if str(_GENERATED) not in sys.path:  # pragma: no cover
    sys.path.insert(0, str(_GENERATED))

from org.tatrman.llm.v1 import llm_gateway_pb2, llm_gateway_pb2_grpc  # noqa: E402

from golem_py.errors import LaneServiceUnavailable  # noqa: E402
from golem_py.llm_client import LiveLlmGateway, LlmPort  # noqa: E402
from tests.grpc_fakes import serve, unused_target  # noqa: E402

REPLY = '{"sql": "SELECT 1", "handled_spans": [], "note": ""}'


class FakeGateway(llm_gateway_pb2_grpc.LlmGatewayServiceServicer):  # type: ignore[misc]
    def __init__(self, *, fail_with: grpc.StatusCode | None = None) -> None:
        self.requests: list[Any] = []
        self.time_remaining: list[float] = []
        self.fail_with = fail_with

    async def CreateResponse(self, request: Any, context: Any) -> Any:  # noqa: N802
        self.requests.append(request)
        self.time_remaining.append(context.time_remaining())
        if self.fail_with is not None:
            await context.abort(self.fail_with, "provider down")
        return llm_gateway_pb2.Response(
            id="resp-1",
            model="gpt-capable-2026",
            status="completed",
            output=[
                llm_gateway_pb2.ResponseOutput(type="text", text=REPLY[:20]),
                llm_gateway_pb2.ResponseOutput(type="text", text=REPLY[20:]),
            ],
        )


def _register(fake: FakeGateway) -> Any:
    return lambda server: llm_gateway_pb2_grpc.add_LlmGatewayServiceServicer_to_server(fake, server)


async def test_the_request_asks_for_a_json_object_at_temperature_zero_within_the_timeout() -> None:
    fake = FakeGateway()
    async with serve(_register(fake)) as target:
        reply = await LiveLlmGateway(target).complete_json(
            instructions="Write one SELECT.", prompt="Zobraz tržby za rok 2025", timeout_s=7.5
        )

    (request,) = fake.requests
    assert request.response_format.type == "json_object"
    assert request.HasField("temperature") and request.temperature == 0.0
    assert list(request.model_tags) == ["capable"]  # the model CLASS (OL-C13)
    assert request.model == ""  # the gateway picks within the class unless pinned
    assert request.instructions == "Write one SELECT."
    assert request.input.text == "Zobraz tržby za rok 2025"
    # The deadline the server saw is the one given, less the trip.
    assert 6.0 < fake.time_remaining[0] <= 7.5

    assert reply.text == REPLY
    assert reply.model == "gpt-capable-2026"
    assert reply.response_id == "resp-1"


async def test_a_pinned_model_and_class_travel_as_configured() -> None:
    fake = FakeGateway()
    async with serve(_register(fake)) as target:
        await LiveLlmGateway(target, model="m-1", model_class="local").complete_json(
            instructions="i", prompt="p", timeout_s=3.0
        )

    assert fake.requests[0].model == "m-1"
    assert list(fake.requests[0].model_tags) == ["local"]


async def test_a_gateway_error_is_lane_unavailable() -> None:
    fake = FakeGateway(fail_with=grpc.StatusCode.UNAVAILABLE)
    async with serve(_register(fake)) as target:
        with pytest.raises(LaneServiceUnavailable) as caught:
            await LiveLlmGateway(target).complete_json(instructions="i", prompt="p", timeout_s=3.0)

    assert caught.value.service == "llm.v1"
    assert "provider down" in caught.value.detail


async def test_an_unreachable_gateway_is_lane_unavailable() -> None:
    with pytest.raises(LaneServiceUnavailable) as caught:
        await LiveLlmGateway(unused_target()).complete_json(
            instructions="i", prompt="p", timeout_s=1.0
        )

    assert caught.value.service == "llm.v1"


def test_the_live_client_satisfies_the_port() -> None:
    assert isinstance(LiveLlmGateway("127.0.0.1:1"), LlmPort)
