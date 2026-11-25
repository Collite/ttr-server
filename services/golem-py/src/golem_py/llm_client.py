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
  carrying the status and the gateway's error CODE (else its TYPE) only
  (`HTTP 401 invalid_api_key`, `HTTP 400 invalid_request_error`) — never its message,
  which may echo the request;
- a 2xx reply with no usable completion text, or one the gateway says stopped early
  (`finish_reason` `length` or `content_filter`) → `LaneReplyRefused("no_completion", …)`.

A target that is not an http(s) URL ending in `/v1`, or a key no HTTP header can carry, is
`ConfigInvalid` when the client is built — never a failure in the middle of a turn.

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

# The gateway's error codes and types, and a reply's `finish_reason`, are short snake_case
# tokens (contracts §1.7). Anything else in those slots is not reported, so a refusal can
# never carry text the gateway echoed.
_TOKEN = re.compile(r"^[a-z][a-z0-9_]{0,63}$")
# A completion that stopped before it was finished: half an object, or none (review-357 F5).
_STOPPED_EARLY = frozenset({"length", "content_filter"})


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
    is appended once. An error never repeats the value: it names the part that is wrong and
    at most the `scheme://host[:port]` it parsed. The path, query, fragment and userinfo are
    never shown, so a key pasted into this variable, or into its URL, is not printed back
    (review-357 F2)."""
    url = base_url.strip()
    name = "GOLEM_LLM_TARGET"
    try:
        parsed = httpx.URL(url)
    except (httpx.InvalidURL, TypeError) as exc:
        raise ConfigInvalid(f"{name} is not a URL") from exc
    if parsed.userinfo:
        raise ConfigInvalid(f"{name} must not carry credentials; use GOLEM_LLM_API_KEY")
    if parsed.scheme not in ("http", "https") or not parsed.host:
        scheme = parsed.scheme if _TOKEN.match(parsed.scheme) else ""
        raise ConfigInvalid(
            f"{name} must be an http(s) URL with a host"
            + (f" (scheme {scheme!r})" if scheme else "")
        )
    origin = f"{parsed.scheme}://{parsed.netloc.decode('ascii', 'replace')}"
    if parsed.query or parsed.fragment:
        raise ConfigInvalid(f"{name} must have no query or fragment (host {origin})")
    if not parsed.path.rstrip("/").endswith("/v1"):
        raise ConfigInvalid(f"{name}: the path must end in /v1 (host {origin})")
    return url.rstrip("/") + CHAT_PATH


def check_api_key(key: str) -> None:
    """An HTTP header carries printable ASCII only. A key with anything else in it (an inner
    NBSP or a smart quote, copied from a document) would pass the emptiness check and then
    fail the first gateway call; this refuses it at startup, without quoting the key or the
    offending character (review-357 F4). Emptiness is the CLI's check: a lane that is off
    needs no key."""
    if key and not (key.isascii() and key.isprintable() and not any(c.isspace() for c in key)):
        raise ConfigInvalid("GOLEM_LLM_API_KEY contains characters an HTTP header cannot carry")


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
    reason = choice.get("finish_reason") if isinstance(choice, dict) else None
    status = reason if isinstance(reason, str) and _TOKEN.fullmatch(reason) else ""
    if status in _STOPPED_EARLY:
        # Whatever text there is, it is not the whole answer: refuse as cut off rather than
        # let the lane blame the model's format (still QUERY_NOT_WRITTEN, OL-C13/C16).
        raise LaneReplyRefused(
            "no_completion", f"the completion stopped early (finish_reason {status})"
        )
    if not isinstance(content, str) or not content.strip():
        raise LaneReplyRefused("no_completion", "the gateway's completion text is empty")
    return LlmReply(
        text=content,
        model=str(doc.get("model") or ""),
        response_id=str(doc.get("id") or ""),
        status=status,
    )


def _error_code(resp: httpx.Response) -> str:
    """The error's `code`, else its `type` (a validation error renders `code: null`) — only
    when it is a safe token (review-357 F5). Never its `message`, which may echo the request."""
    try:
        error = resp.json()["error"]
    except (ValueError, KeyError, TypeError):
        return ""
    if not isinstance(error, dict):
        return ""
    for slot in ("code", "type"):
        value = error.get(slot)
        if isinstance(value, str) and _TOKEN.match(value):
            return value
    return ""


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
        # One derivation of the URL, checked here, so no caller can build a gateway on an
        # unchecked target or a key no header can carry (review-357 F3/F4): `ConfigInvalid`.
        self.endpoint = chat_endpoint(target)
        self.target = target
        self._api_key = api_key if isinstance(api_key, SecretStr) else SecretStr(api_key)
        check_api_key(self._api_key.get_secret_value())
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
