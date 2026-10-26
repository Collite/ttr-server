# SPDX-License-Identifier: Apache-2.0
"""The four LLM-gateway facts an extraction worker relies on (DX-P0 S0.4 T6).

    uv run python scripts/gateway-facts.py --url http://llm-gateway:8080 --key ttrk-… [--model gpt-4.1]
    uv run python scripts/gateway-facts.py            # no gateway: the facts as read from services/llm-gateway's source

(a) structured output for a JSON schema · (b) a stable call id and the prompt log behind it ·
(c) a prompt hash · (d) the embeddings shape. Live mode exercises (a), (b)'s header, (c) client-side and (d);
reading a prompt log needs a Keycloak JWT with llm-gateway-inspect (a gateway key gets 401), so it is not probed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from typing import Any

import httpx

SCHEMA: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["sentiment"],
    "properties": {"sentiment": {"type": "string", "enum": ["positive", "neutral", "negative"]}},
}
MESSAGES = [
    {"role": "system", "content": "Classify the sentiment of the review. Answer with the JSON object only."},
    {"role": "user", "content": "The Voltaic headphones are great, the order was just held for a week."},
]
DECODING: dict[str, Any] = {"temperature": 0, "seed": 7, "max_tokens": 200}

FROM_SOURCE = {
    "a_structured_output": (
        "openai-wire providers (azure, openai, gemini): the body is forwarded verbatim (only `model` rewritten), so "
        "response_format {type: json_schema, json_schema: {name, schema, strict}} reaches the upstream unchanged; "
        "anthropic: response_format is STRIPPED silently (logged at INFO, recorded in prompt_logs.stripped_params, "
        "no signal to the caller); the gateway validates no completion against a schema. No `extract-*` route "
        "exists; hartland serves azure routes (gpt-4.1 -> gpt-5-mini, gpt-6.1-sol, gpt-4o-mini) and anthropic "
        "routes without a key."
    ),
    "b_call_id": (
        "response header X-Prompt-Log-Id (numeric prompt-log row id) on every chat call incl. cache hits, absent "
        "without the prompt-log store; NOT resolvable by id — GET /v1/prompt-logs takes turn_ref or trace_id only "
        "(send X-Turn-Ref, then match items[].id); readable with a Keycloak JWT only (inspect/admin roles see "
        "bodies); the log stores the messages' content TEXT and the assistant text, not the request body "
        "(no roles, params, response_format); a row is dropped when the async queue is full."
    ),
    "c_prompt_hash": (
        "computed only as the response-cache key (sha256 of the canonical whole request minus stream/user/"
        "metadata/model_tags, model -> catalog name) and never exposed; hartland caches chat for 3600 s — send "
        "X-Gateway-Cache: bypass for an extraction that must run. The worker hashes messages + schema + decoding "
        "itself (client_prompt_hash below)."
    ),
    "d_embeddings": (
        "POST /v1/embeddings {model, input: string|array, dimensions?, encoding_format?} forwarded verbatim; the "
        "reply is the upstream's (data[].embedding, data[].index, model) with usage enriched "
        "(prompt_tokens, total_tokens, input_tokens, cost, estimated) and top-level cached:false; model "
        "`embedding` = text-embedding-3-small (1536); no X-Prompt-Log-Id on embeddings."
    ),
}


def client_prompt_hash(
    model: str, messages: list[dict[str, Any]], schema: dict[str, Any], decoding: dict[str, Any]
) -> str:
    """What an extraction run records as its prompt hash: sha256 of the canonical JSON of what decides the answer."""
    canon = json.dumps(
        {"model": model, "messages": messages, "schema": schema, "decoding": decoding},
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    )
    return "sha256:" + hashlib.sha256(canon.encode("utf-8")).hexdigest()


def live(url: str, key: str, model: str, embed_model: str) -> dict[str, Any]:
    h = {"Authorization": f"Bearer {key}", "X-Gateway-Cache": "bypass", "X-Call-Purpose": "gateway-facts"}
    out: dict[str, Any] = {}
    with httpx.Client(base_url=url, timeout=60) as c:
        body = {
            "model": model,
            "messages": MESSAGES,
            **DECODING,
            "response_format": {
                "type": "json_schema",
                "json_schema": {"name": "sentiment", "schema": SCHEMA, "strict": True},
            },
        }
        r = c.post("/v1/chat/completions", json=body, headers=h)
        content = None
        if r.status_code == 200:
            content = r.json()["choices"][0]["message"].get("content")
        try:
            parsed = json.loads(content) if content else None
            conforms = (
                isinstance(parsed, dict)
                and set(parsed) == {"sentiment"}
                and parsed["sentiment"] in ("positive", "neutral", "negative")
            )
        except ValueError:
            conforms = False
        out["a_structured_output"] = {
            "status": r.status_code,
            "provider": r.headers.get("X-Gateway-Provider"),
            "served_model": r.headers.get("X-Gateway-Model"),
            "content": content,
            "conforms_to_schema": conforms,
        }
        out["b_call_id"] = {
            "X-Prompt-Log-Id": r.headers.get("X-Prompt-Log-Id"),
            "body_id": (r.json().get("id") if r.status_code == 200 else None),
        }
        out["c_prompt_hash"] = {"client_prompt_hash": client_prompt_hash(model, MESSAGES, SCHEMA, DECODING)}
        e = c.post("/v1/embeddings", json={"model": embed_model, "input": ["Sluchátka Voltaic"]}, headers=h)
        shape: dict[str, Any] = {"status": e.status_code}
        if e.status_code == 200:
            j = e.json()
            shape |= {
                "keys": sorted(j),
                "dims": len(j["data"][0]["embedding"]),
                "model": j.get("model"),
                "usage": j.get("usage"),
            }
        out["d_embeddings"] = shape
    return out


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--url")
    p.add_argument("--key")
    p.add_argument("--model", default="gpt-4.1")
    p.add_argument("--embed-model", default="embedding")
    a = p.parse_args(argv)
    if a.url and a.key:
        facts: dict[str, Any] = {"mode": "live", "gateway": a.url, **live(a.url, a.key, a.model, a.embed_model)}
    else:
        facts = {
            "mode": "from source (services/llm-gateway at llm-gateway/v0.11.5 — not exercised live)",
            **FROM_SOURCE,
            "client_prompt_hash_example": client_prompt_hash(a.model, MESSAGES, SCHEMA, DECODING),
        }
    print(json.dumps(facts, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
