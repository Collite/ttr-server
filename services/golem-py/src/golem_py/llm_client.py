# SPDX-License-Identifier: Apache-2.0
"""The LLM gateway client — HTTP `POST /v1/chat/completions` (OL-C13, D-43).

One client of the open LLM gateway, never a provider directly. Gateway 2.0 is REST-only:
the retained `llm.v1` protos describe no running service, so this client speaks the
gateway's OpenAI-shaped wire (its contracts §§1.1–1.3, 1.7) and nothing else — no gRPC and
no Responses fallback.

Every call is one non-streaming request: the lane's instructions as the system message, its
prompt as the user message, `temperature: 0` and `response_format: json_object`. The model
is named by CLASS (`model_tags: [smart]`), leaving the concrete model to the gateway, unless
the estate pins one — an explicit model takes precedence and is sent alone. The team's
virtual key travels as `Authorization: Bearer …` and appears nowhere else: not in `repr`,
not in an error, not in a log line.

The whole call is bounded by the timeout the caller gives (the `capable` rung's, from the
ladder), and there is no retry here: the lane counts every call against its invocation
budget (OL-C16), and a retry inside the client would spend calls the budget never saw.

What can go wrong, and what the lane makes of it (OL-C13):
- no answer (transport error, timeout) or a non-2xx status → `LaneServiceUnavailable`,
  carrying the status and the gateway's error CODE only (`HTTP 401 invalid_api_key`) —
  never its message, which may echo the request;
- a 2xx reply with no usable completion text → `LaneReplyRefused("no_completion", …)`.

What comes back is the model's TEXT, unparsed. Whether it is a valid object, and whether it
keeps the rules of OL-C15, is the lane's call — a client that "repaired" a reply here would
hide the very failure the lane must refuse on.
"""

from __future__ import annotations

import asyncio
import re
from dataclasses import dataclass
from typing import Any, Protocol, runtime_checkable

import httpx
from pydantic import SecretStr

from golem_py.errors import ConfigInvalid, LaneReplyRefused, LaneServiceUnavailable

# How a failure names this leg in a `LANE_UNAVAILABLE` refusal (the other legs use their
# wire packages, `query.v1` and `meta.v1`).
SERVICE = "llm-gateway"
DEFAULT_MODEL_CLASS = "smart"
CHAT_PATH = "/chat/completions"
JSON_OBJECT = "json_object"

# The gateway's error codes are short snake_case tokens (contracts §1.7). Anything else in
# the `code` slot is not reported, so a refusal can never carry text the gateway echoed.
_ERROR_CODE = re.compile(r"^[a-z][a-z0-9_]{0,63}$")


@dataclass(frozen=True)
class LlmReply:
    """The model's answer as text, plus what the envelope's provenance needs (OL-C17)."""

    text: str
    model: str = ""  # the SERVED model, as the gateway reports it
    response_id: str = ""
    status: str = ""  # `finish_reason`


@runtime_checkable
class LlmPort(Protocol):
    """Raises `LaneServiceUnavailable` when the gateway cannot answer and
    `LaneReplyRefused` when it answers without a completion."""

    async def complete_json(
        self, *, instructions: str, prompt: str, timeout_s: float
    ) -> LlmReply: ...


def chat_endpoint(base_url: str) -> str:
    """`GOLEM_LLM_TARGET` → the one URL the client posts to, or `ConfigInvalid`.

    The target is an explicit HTTP(S) base URL ending in `/v1` (OL-C13); `/chat/completions`
    is appended once. The URL itself is not repeated in the error when it carries
    credentials, so a key pasted into the wrong variable is not printed back."""
    url = base_url.strip()
    try:
        parsed = httpx.URL(url)
    except (httpx.InvalidURL, TypeError) as exc:
        raise ConfigInvalid("GOLEM_LLM_TARGET is not a URL") from exc
    if parsed.userinfo:
        raise ConfigInvalid("GOLEM_LLM_TARGET must not carry credentials; use GOLEM_LLM_API_KEY")
    shown = repr(url)
    if parsed.scheme not in ("http", "https") or not parsed.host:
        raise ConfigInvalid(f"GOLEM_LLM_TARGET must be an http(s) URL, got {shown}")
    if parsed.query or parsed.fragment:
        raise ConfigInvalid(f"GOLEM_LLM_TARGET must have no query or fragment, got {shown}")
    if not parsed.path.rstrip("/").endswith("/v1"):
        raise ConfigInvalid(f"GOLEM_LLM_TARGET must end in /v1, got {shown}")
    return url.rstrip("/") + CHAT_PATH


def build_body(
    *, instructions: str, prompt: str, model: str = "", model_class: str = DEFAULT_MODEL_CLASS
) -> dict[str, Any]:
    """The one request body the lane sends (OL-C13). An explicit model is sent alone; the
    class is sent only when no model is pinned."""
    body: dict[str, Any] = {
        "messages": [
            {"role": "system", "content": instructions},
            {"role": "user", "content": prompt},
        ],
        "stream": False,
        "temperature": 0,
        "response_format": {"type": JSON_OBJECT},
    }
    if model:
        body["model"] = model
    elif model_class:
        body["model_tags"] = [model_class]
    return body


def reply_from_json(doc: Any) -> LlmReply:
    """`choices[0].message.content` as the text, `model` as the served model, `id` as the
    response id. A reply without usable text is `LaneReplyRefused` (→ QUERY_NOT_WRITTEN)."""
    try:
        choice = doc["choices"][0]
        content = choice["message"]["content"]
    except (KeyError, IndexError, TypeError) as exc:
        raise LaneReplyRefused(
            "no_completion", "the gateway's reply has no choices[0].message.content"
        ) from exc
    if not isinstance(content, str) or not content.strip():
        raise LaneReplyRefused("no_completion", "the gateway's completion text is empty")
    return LlmReply(
        text=content,
        model=str(doc.get("model") or ""),
        response_id=str(doc.get("id") or ""),
        status=str(choice.get("finish_reason") or ""),
    )


def _error_code(resp: httpx.Response) -> str:
    try:
        code = resp.json()["error"]["code"]
    except (ValueError, KeyError, TypeError):
        return ""
    return code if isinstance(code, str) and _ERROR_CODE.match(code) else ""


class LiveLlmGateway:
    """The real gateway over HTTP (LlmPort)."""

    def __init__(
        self,
        target: str,
        *,
        api_key: SecretStr | str,
        model: str = "",
        model_class: str = DEFAULT_MODEL_CLASS,
        transport: httpx.AsyncBaseTransport | None = None,
    ):
        self.target = target
        self.endpoint = target.strip().rstrip("/") + CHAT_PATH
        self._api_key = api_key if isinstance(api_key, SecretStr) else SecretStr(api_key)
        self.model = model
        self.model_class = model_class
        self._transport = transport

    def __repr__(self) -> str:
        return (
            f"LiveLlmGateway(target={self.target!r}, model={self.model!r}, "
            f"model_class={self.model_class!r})"
        )

    async def complete_json(self, *, instructions: str, prompt: str, timeout_s: float) -> LlmReply:
        body = build_body(
            instructions=instructions,
            prompt=prompt,
            model=self.model,
            model_class=self.model_class,
        )
        headers = {
            "Authorization": f"Bearer {self._api_key.get_secret_value()}",
            "Accept": "application/json",
        }
        try:
            # httpx's timeout is per phase (connect, read, …); `asyncio.timeout` bounds the
            # whole call, which is what the rung's clock means.
            async with asyncio.timeout(timeout_s):
                async with httpx.AsyncClient(
                    timeout=httpx.Timeout(timeout_s),
                    transport=self._transport,
                    follow_redirects=False,
                ) as client:
                    resp = await client.post(self.endpoint, json=body, headers=headers)
        except (TimeoutError, httpx.TimeoutException) as exc:
            raise LaneServiceUnavailable(SERVICE, f"timeout after {timeout_s:g}s") from exc
        except httpx.HTTPError as exc:
            raise LaneServiceUnavailable(SERVICE, f"transport: {type(exc).__name__}") from exc
        if not resp.is_success:
            code = _error_code(resp)
            raise LaneServiceUnavailable(
                SERVICE, f"HTTP {resp.status_code}" + (f" {code}" if code else "")
            )
        try:
            doc = resp.json()
        except ValueError as exc:
            raise LaneReplyRefused("no_completion", "the gateway's reply is not JSON") from exc
        return reply_from_json(doc)
