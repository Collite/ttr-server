# SPDX-License-Identifier: Apache-2.0
"""A14.2 — grounding eval runner (E2E, against a deployed stack).

Two tiers (see `SCHEMA.md`):

  * **bulk** — drives `grounding-mcp` (`ground_time`/`ground_geo`/`ground_money`)
    directly, one call per span, asserting the GroundingResult. Reports the
    rules-vs-LLM source rate, outcome distribution, and latency percentiles.
  * **e2e** — drives Golem `/v2/chat`, asserting the grounded condition survives
    into the final plan/answer.

    python eval/grounding/run_eval.py \
        --grounding-url http://localhost:7153/mcp \
        --golem-url http://localhost:7999 \
        --models accounting-period,calendar,money-domestic,money-fx,money-native,geo-brno-praha \
        --output-json eval/grounding/reports/metrics.json \
        --output-md   eval/grounding/reports/report.md

Gates (non-zero exit): bulk pass-rate ≥ `--pass-gate` (0.80) AND LLM-fallback rate
≤ `--llm-gate` (0.10). The `--models` allow-list marks cases whose fixture the
deployed metadata model can't serve as `skipped:model-unavailable` rather than
failing them. Pure comparison/aggregation lives in `report.py`.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from pathlib import Path
from typing import Any

import httpx

from report import (  # noqa: E402 — sibling module, run as a script
    evaluate_bulk_case,
    evaluate_e2e_turn,
    render_markdown,
    summarize,
)

_HERE = Path(__file__).resolve().parent
_CORPUS = _HERE / "corpus"


# ---------------------------------------------------------------------------
# GroundingContext assembly (mirror of golem's grounding_context, standalone)
# ---------------------------------------------------------------------------


def _context(case: dict[str, Any]) -> dict[str, Any]:
    ctx: dict[str, Any] = {
        "referenceDatetime": case["reference_datetime"],
        "timezone": case.get("timezone", "Europe/Prague"),
        "locale": case.get("locale", "cs-CZ"),
        "defaultCurrency": "CZK",
        "fxPolicy": "TRANSACTION_DATE",
    }
    if case.get("here"):
        ctx["herePlaceRef"] = case["here"]
    if case.get("prior_questions"):
        # The user's earlier questions, newest first — what an anaphoric period word reads against (#214).
        ctx["priorQuestions"] = list(case["prior_questions"])
    return ctx


def _tool_args(case: dict[str, Any]) -> dict[str, Any]:
    args: dict[str, Any] = {
        "spanText": case["span"],
        "questionText": case.get("question_text") or case["span"],
        "package": case.get("package", "cnc"),
        "context": _context(case),
    }
    if case.get("clarification_answer_id"):
        args["clarificationAnswerId"] = case["clarification_answer_id"]
    return args


# ---------------------------------------------------------------------------
# Bulk tier — one MCP session, N tool calls
# ---------------------------------------------------------------------------


def _normalize(raw: Any) -> dict[str, Any]:
    """CallToolResult → {ok, status, result, options} (see grounding_mcp.py)."""
    structured: dict[str, Any] = getattr(raw, "structuredContent", None) or {}
    is_error = bool(getattr(raw, "isError", False))
    err_code = structured.get("errorCode")
    if is_error or err_code:
        return {"ok": False, "status": "", "result": None, "options": [], "error": structured.get("error") or err_code}
    return {
        "ok": True,
        "status": structured.get("status") or "",
        "result": structured.get("result"),
        "options": structured.get("options") or [],
    }


async def run_bulk(
    cases: list[dict[str, Any]],
    grounding_url: str,
    models: set[str] | None,
) -> list[dict[str, Any]]:
    from mcp import ClientSession
    from mcp.client.streamable_http import streamable_http_client

    records: list[dict[str, Any]] = []
    async with streamable_http_client(grounding_url) as (read, write, _):
        async with ClientSession(read, write) as session:
            await session.initialize()
            for case in cases:
                if models is not None and case.get("model") not in models:
                    records.append({
                        "id": case["id"], "tier": "bulk", "tool": case["tool"], "model": case.get("model"),
                        "locale": "cs" if str(case.get("locale", "")).lower().startswith("cs") else "en",
                        "passed": True, "failures": [], "status": "skipped:model-unavailable",
                        "source": "", "latency_ms": None,
                    })
                    continue
                t0 = time.perf_counter()
                try:
                    raw = await session.call_tool(case["tool"], arguments=_tool_args(case))
                    actual = _normalize(raw)
                except Exception as exc:  # noqa: BLE001 — a dead tool is a failed case, not a dead run.
                    actual = {"ok": False, "status": "", "result": None, "options": [], "error": str(exc)}
                actual["latency_ms"] = round((time.perf_counter() - t0) * 1000, 2)
                records.append(evaluate_bulk_case(case, actual))
    return records


# ---------------------------------------------------------------------------
# E2E tier — Golem /v2/chat (+ /v2/chat/resume for clarification cases)
# ---------------------------------------------------------------------------


async def run_e2e(cases: list[dict[str, Any]], golem_url: str) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    async with httpx.AsyncClient(base_url=golem_url, timeout=90.0) as client:
        for case in cases:
            records.append(await _run_e2e_case(client, case))
    return records


async def _run_e2e_case(client: httpx.AsyncClient, case: dict[str, Any]) -> dict[str, Any]:
    thread_id = f"eval-grnd-{case['id'].replace('/', '-')}"
    await client.post("/v2/session", json={"thread_id": thread_id, "locale": case.get("locale", "cs")})

    turn_results: list[dict[str, Any]] = []
    prior: dict[str, Any] = {}
    for turn in case["turns"]:
        try:
            if "resume_selects" in turn:
                response = await _resume(client, thread_id, prior, turn["resume_selects"])
            else:
                r = await client.post("/v2/chat", json={"thread_id": thread_id, "user_text": turn["user_text"]})
                r.raise_for_status()
                response = r.json()
        except Exception as exc:  # noqa: BLE001
            turn_results.append({"passed": False, "failures": [f"http error: {exc}"], "grounded": False})
            break
        prior = response
        turn_results.append(evaluate_e2e_turn(turn.get("expect") or {}, response))

    passed = all(t["passed"] for t in turn_results)
    return {
        "id": case["id"], "tier": "e2e",
        "locale": "cs" if str(case.get("locale", "")).lower().startswith("cs") else "en",
        "passed": passed,
        "failures": [f for t in turn_results for f in t["failures"]],
    }


async def _resume(client: httpx.AsyncClient, thread_id: str, prior: dict[str, Any], selects: str) -> dict[str, Any]:
    pc = prior.get("pending_clarification") or (prior.get("envelope") or {}).get("pending_clarification") or {}
    options = pc.get("options") or []
    idx = 0 if selects == "first" else (int(selects) if str(selects).isdigit() else 0)
    option_id = options[idx]["id"] if idx < len(options) else None
    r = await client.post(
        "/v2/chat/resume",
        json={"thread_id": thread_id, "resume_token": pc.get("resume_token", ""), "selected_option_id": option_id},
    )
    r.raise_for_status()
    return r.json()


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _load(name: str) -> list[dict[str, Any]]:
    return json.loads((_CORPUS / name).read_text(encoding="utf-8"))


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Grounding eval runner")
    ap.add_argument("--grounding-url", default="http://localhost:7153/mcp")
    ap.add_argument("--golem-url", default="http://localhost:7999")
    ap.add_argument("--models", default="", help="comma list of model fixtures the stack serves; empty = score all")
    ap.add_argument("--only", default="", help="substring filter on case id")
    ap.add_argument("--skip-bulk", action="store_true")
    ap.add_argument("--skip-e2e", action="store_true")
    ap.add_argument("--output-json", type=Path, default=_HERE / "reports" / "metrics.json")
    ap.add_argument("--output-md", type=Path, default=_HERE / "reports" / "report.md")
    ap.add_argument("--pass-gate", type=float, default=0.80)
    ap.add_argument("--llm-gate", type=float, default=0.10)
    args = ap.parse_args(argv)

    models = {m.strip() for m in args.models.split(",") if m.strip()} or None
    bulk_cases = [c for c in _load("grounding-cases.json") if args.only in c["id"]]
    e2e_cases = [c for c in _load("e2e-cases.json") if args.only in c["id"]]

    bulk = [] if args.skip_bulk else asyncio.run(run_bulk(bulk_cases, args.grounding_url, models))
    e2e = [] if args.skip_e2e else asyncio.run(run_e2e(e2e_cases, args.golem_url))

    summary = summarize(bulk, e2e)
    report = {"summary": summary, "bulk": bulk, "e2e": e2e}
    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    args.output_json.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    args.output_md.write_text(render_markdown(summary), encoding="utf-8")

    print(render_markdown(summary))
    print(f"reports → {args.output_json}, {args.output_md}")

    b = summary["bulk"]
    llm_rate = b["source_rate"]["llm_fallback_rate"]
    ok = True
    if b["pass_rate"] is not None and b["pass_rate"] < args.pass_gate:
        print(f"FAIL: bulk pass rate {b['pass_rate']} < gate {args.pass_gate}", file=sys.stderr)
        ok = False
    if llm_rate is not None and llm_rate > args.llm_gate:
        print(f"FAIL: LLM-fallback rate {llm_rate} > gate {args.llm_gate}", file=sys.stderr)
        ok = False
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
