# SPDX-License-Identifier: Apache-2.0
"""The LLM gateway client — `llm.v1` (OL-C13, W1c·S1·T5).

One client of the open LLM gateway, never a provider directly. Every call asks for a
JSON object at temperature 0, under the timeout the caller gives — the `capable` rung's
from the ladder — and names the model CLASS (`capable`), leaving the concrete model to
the gateway unless the estate pins one.

⚑ The RPC is `CreateResponse`, not `Chat`. `ChatRequest` carries only a model and the
messages: it cannot ask for temperature 0 or a JSON object, and OL-C13 requires both.
`CreateResponseRequest` carries `temperature`, `response_format`, `instructions` and
`model_tags`, so the contract is expressible only there.

What comes back is the model's TEXT, unparsed. Whether it is a valid object, and whether
it keeps the rules of OL-C15, is the lane's call (W1c·S2) — a client that "repaired" a
reply here would hide the very failure the lane must refuse on.
"""

from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol, runtime_checkable

_GENERATED = Path(__file__).resolve().parents[2] / "generated"
if str(_GENERATED) not in sys.path:  # pragma: no cover - import bootstrap
    sys.path.insert(0, str(_GENERATED))

import grpc  # noqa: E402
from org.tatrman.llm.v1 import llm_gateway_pb2, llm_gateway_pb2_grpc  # noqa: E402

from golem_py.errors import LaneServiceUnavailable  # noqa: E402

SERVICE = "llm.v1"
DEFAULT_MODEL_CLASS = "capable"
JSON_OBJECT = "json_object"


@dataclass(frozen=True)
class LlmReply:
    """The model's answer as text, plus what the envelope's provenance needs (OL-C17)."""

    text: str
    model: str = ""
    response_id: str = ""
    status: str = ""


@runtime_checkable
class LlmPort(Protocol):
    """Raises `LaneServiceUnavailable("llm.v1", …)` when the gateway cannot answer."""

    async def complete_json(
        self, *, instructions: str, prompt: str, timeout_s: float
    ) -> LlmReply: ...


def build_request(
    *, instructions: str, prompt: str, model: str = "", model_class: str = DEFAULT_MODEL_CLASS
) -> Any:
    """The one request shape the lane sends (OL-C13)."""
    return llm_gateway_pb2.CreateResponseRequest(
        model=model,
        model_tags=[model_class] if model_class else [],
        instructions=instructions,
        input=llm_gateway_pb2.ResponseInput(text=prompt),
        temperature=0.0,
        response_format=llm_gateway_pb2.ResponseFormat(type=JSON_OBJECT),
    )


def reply_from_proto(resp: Any) -> LlmReply:
    """Every output's text, in order. A tool-call output carries none and adds nothing."""
    return LlmReply(
        text="".join(out.text for out in resp.output),
        model=resp.model,
        response_id=resp.id,
        status=resp.status,
    )


class LiveLlmGateway:
    """The real `llm.v1 CreateResponse` rpc (LlmPort)."""

    def __init__(self, target: str, *, model: str = "", model_class: str = DEFAULT_MODEL_CLASS):
        self.target = target
        self.model = model
        self.model_class = model_class

    async def complete_json(self, *, instructions: str, prompt: str, timeout_s: float) -> LlmReply:
        request = build_request(
            instructions=instructions,
            prompt=prompt,
            model=self.model,
            model_class=self.model_class,
        )
        try:
            async with grpc.aio.insecure_channel(self.target) as channel:
                stub = llm_gateway_pb2_grpc.LlmGatewayServiceStub(channel)
                resp = await stub.CreateResponse(request, timeout=timeout_s)
        except grpc.aio.AioRpcError as exc:
            raise LaneServiceUnavailable(
                SERVICE, f"{exc.code().name}: {exc.details() or ''}".rstrip(": ")
            ) from exc
        return reply_from_proto(resp)
