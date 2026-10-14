# SPDX-License-Identifier: Apache-2.0
"""A14.2 — pure evaluation + aggregation core for the grounding eval runner.

Dict-in / dict-out, no I/O and no network, so it unit-tests without a deployed
stack (`tests/test_report.py`). `run_eval.py` supplies the actual GroundingResult
(camelCase mirror of the proto) and Golem envelopes; everything here just compares
and aggregates: per-case pass/fail, outcome distribution, rules-vs-LLM source
rate, and latency percentiles.
"""
from __future__ import annotations

from typing import Any

# ---------------------------------------------------------------------------
# Bulk tier — compare a case's `expect` to the tool's GroundingResult
# ---------------------------------------------------------------------------

_APPLICATION_KEYS = ("filter", "join", "values")


def application_of(result: dict[str, Any]) -> str | None:
    """FILTER | JOIN | VALUES — whichever `application` oneof the result carries."""
    for key in _APPLICATION_KEYS:
        recipe = result.get(key)
        if isinstance(recipe, dict) and recipe:
            return key.upper()
    return None


def _recipe(result: dict[str, Any]) -> dict[str, Any]:
    for key in _APPLICATION_KEYS:
        recipe = result.get(key)
        if isinstance(recipe, dict) and recipe:
            return recipe
    return {}


def param_value(result: dict[str, Any], name: str) -> str | None:
    """String value of a named recipe parameter (e.g. chrono `p`)."""
    for pb in _recipe(result).get("parameters") or []:
        if pb.get("name") == name:
            v = pb.get("value") or {}
            for k in ("stringValue", "intValue", "datetimeValue", "floatValue"):
                if k in v:
                    return str(v[k])
    return None


def evaluate_bulk_case(case: dict[str, Any], actual: dict[str, Any]) -> dict[str, Any]:
    """Compare one bulk case against the normalized tool result.

    `actual` shape (from run_eval / the MCP wrapper): ``{ok, status, result, options}``.
    Returns a result record with ``passed`` + ``failures`` + carried facets for
    aggregation (``status``, ``source``, ``latency_ms``, ``model``, ``tool``).
    """
    exp = case.get("expect") or {}
    failures: list[str] = []

    if not actual.get("ok", True):
        failures.append(f"transport error: {actual.get('error')!r}")
        return _bulk_record(case, actual, passed=False, failures=failures)

    status = actual.get("status") or ""
    if status != exp["status"]:
        failures.append(f"status: expected {exp['status']!r}, got {status!r}")

    result = actual.get("result") or {}
    if exp["status"] == "OK" and status == "OK":
        if exp.get("application") and application_of(result) != exp["application"]:
            failures.append(f"application: expected {exp['application']!r}, got {application_of(result)!r}")
        if exp.get("source") and (result.get("source") or "") != exp["source"]:
            failures.append(f"source: expected {exp['source']!r}, got {result.get('source')!r}")
        if exp.get("period_code") and param_value(result, "p") != exp["period_code"]:
            failures.append(f"period_code: expected {exp['period_code']!r}, got {param_value(result, 'p')!r}")
        preview = result.get("sqlPreview") or ""
        for frag in exp.get("sql_contains") or []:
            if frag not in preview:
                failures.append(f"sql_contains: {frag!r} not in sql_preview {preview!r}")
        if exp.get("interval_start"):
            start = ((result.get("normalized") or {}).get("interval") or {}).get("start") or ""
            if not start.startswith(exp["interval_start"]):
                failures.append(f"interval_start: expected {exp['interval_start']!r}, got {start!r}")
    if exp["status"] == "AWAITING_CLARIFICATION" and status == "AWAITING_CLARIFICATION" and exp.get("option_ids"):
        ids = [o.get("id") or "" for o in actual.get("options") or []]
        if ids != exp["option_ids"]:
            failures.append(f"option_ids: expected {exp['option_ids']!r}, got {ids!r}")

    return _bulk_record(case, actual, passed=not failures, failures=failures)


def _bulk_record(case: dict[str, Any], actual: dict[str, Any], *, passed: bool, failures: list[str]) -> dict[str, Any]:
    result = actual.get("result") or {}
    return {
        "id": case["id"],
        "tier": "bulk",
        "tool": case["tool"],
        "model": case.get("model"),
        "locale": "cs" if str(case.get("locale", "")).lower().startswith("cs") else "en",
        "passed": passed,
        "failures": failures,
        "status": actual.get("status") or "",
        "source": (result.get("source") or "") if actual.get("ok", True) else "",
        "latency_ms": actual.get("latency_ms"),
    }


# ---------------------------------------------------------------------------
# E2E tier — compare an `expect` block to a Golem /v2/chat response
# ---------------------------------------------------------------------------


def _envelope(response: dict[str, Any]) -> dict[str, Any]:
    return response.get("envelope") or response.get("last_envelope") or {}


def _is_clarifying(response: dict[str, Any]) -> bool:
    env = _envelope(response)
    return bool(response.get("pending_clarification") or env.get("pending_clarification"))


def evaluate_e2e_turn(expect: dict[str, Any], response: dict[str, Any]) -> dict[str, Any]:
    """Compare one E2E turn's `expect` to the Golem response envelope."""
    failures: list[str] = []
    env = _envelope(response)
    notes = env.get("grounding_notes") or []

    if expect.get("clarifies"):
        if not _is_clarifying(response):
            failures.append("expected a clarification, got none")
        return {"passed": not failures, "failures": failures, "grounded": bool(notes)}

    if expect.get("not_clarifying") and _is_clarifying(response):
        failures.append("unexpected clarification")

    if "grounded" in expect:
        grounded = bool(notes)
        if grounded != bool(expect["grounded"]):
            failures.append(f"grounded: expected {expect['grounded']}, got {grounded} (notes={notes})")

    for frag in expect.get("grounding_note_contains") or []:
        if not any(frag in n for n in notes):
            failures.append(f"grounding_note_contains: {frag!r} not in {notes}")

    plan_source = response.get("plan_source") or env.get("plan_source")
    if expect.get("plan_source_in") and plan_source not in expect["plan_source_in"]:
        failures.append(f"plan_source {plan_source!r} not in {expect['plan_source_in']}")

    sql = _extract_sql(response)
    for frag in expect.get("sql_contains") or []:
        if sql is None:
            failures.append(f"sql_contains: {frag!r} — no SQL exposed on response")
        elif frag.lower() not in sql.lower():
            failures.append(f"sql_contains: {frag!r} not in planned SQL")

    return {"passed": not failures, "failures": failures, "grounded": bool(notes)}


def _extract_sql(response: dict[str, Any]) -> str | None:
    env = _envelope(response)
    for source in (response, env, env.get("debug") or {}, response.get("picked_plan") or {}):
        sql = source.get("sql") if isinstance(source, dict) else None
        if isinstance(sql, str) and sql:
            return sql
    return None


# ---------------------------------------------------------------------------
# Aggregation
# ---------------------------------------------------------------------------


def percentile(values: list[float], p: float) -> float | None:
    """Nearest-rank percentile (no numpy). `p` in [0, 100]."""
    xs = sorted(v for v in values if v is not None)
    if not xs:
        return None
    k = max(1, min(len(xs), (int((p / 100.0) * len(xs) + 0.999999)))) - 1
    return round(xs[k], 2)


def latency_percentiles(values: list[float]) -> dict[str, float | None]:
    return {f"p{p}": percentile(values, p) for p in (50, 95, 99)}


def outcome_distribution(records: list[dict[str, Any]]) -> dict[str, int]:
    dist: dict[str, int] = {}
    for r in records:
        dist[r["status"]] = dist.get(r["status"], 0) + 1
    return dist


def source_rate(records: list[dict[str, Any]]) -> dict[str, Any]:
    """Rules-vs-LLM share among OK groundings. `llm_fallback_rate` is the gate
    tracked in A14.5 (target < 0.10).
    """
    ok = [r for r in records if r["status"] == "OK"]
    counts: dict[str, int] = {}
    for r in ok:
        src = r.get("source") or "NONE"
        counts[src] = counts.get(src, 0) + 1
    total = len(ok) or 0
    llm = counts.get("LLM", 0)
    return {
        "counts": counts,
        "ok_total": total,
        "rules_rate": round(counts.get("RULES", 0) / total, 4) if total else None,
        "llm_fallback_rate": round(llm / total, 4) if total else None,
    }


def summarize(bulk: list[dict[str, Any]], e2e: list[dict[str, Any]]) -> dict[str, Any]:
    scored_bulk = [r for r in bulk if not str(r.get("status", "")).startswith("skipped")]
    bulk_pass = sum(1 for r in scored_bulk if r["passed"])
    e2e_pass = sum(1 for r in e2e if r["passed"])
    lat = [r["latency_ms"] for r in bulk if r.get("latency_ms") is not None]
    return {
        "bulk": {
            "total": len(bulk),
            "scored": len(scored_bulk),
            "skipped": len(bulk) - len(scored_bulk),
            "passed": bulk_pass,
            "pass_rate": round(bulk_pass / len(scored_bulk), 4) if scored_bulk else None,
            "outcome_distribution": outcome_distribution(scored_bulk),
            "source_rate": source_rate(scored_bulk),
            "latency_ms": latency_percentiles(lat),
        },
        "e2e": {
            "total": len(e2e),
            "passed": e2e_pass,
            "pass_rate": round(e2e_pass / len(e2e), 4) if e2e else None,
        },
    }


def render_markdown(summary: dict[str, Any]) -> str:
    b = summary["bulk"]
    sr = b["source_rate"]
    lat = b["latency_ms"]
    lines = [
        "# Grounding eval report",
        "",
        "## Bulk (GroundingResult tier)",
        f"- cases: {b['total']} ({b['scored']} scored, {b['skipped']} skipped:model-unavailable)",
        f"- pass rate: **{_pct(b['pass_rate'])}** ({b['passed']}/{b['scored']})",
        f"- outcome distribution: {b['outcome_distribution']}",
        f"- rules rate: {_pct(sr['rules_rate'])} · **LLM-fallback rate: {_pct(sr['llm_fallback_rate'])}** (gate < 10%)",
        f"- latency ms: p50={lat['p50']} p95={lat['p95']} p99={lat['p99']}",
        "",
        "## E2E (final-SQL tier)",
        f"- pass rate: **{_pct(summary['e2e']['pass_rate'])}** ({summary['e2e']['passed']}/{summary['e2e']['total']})",
    ]
    return "\n".join(lines) + "\n"


def _pct(x: float | None) -> str:
    return "n/a" if x is None else f"{x * 100:.1f}%"
