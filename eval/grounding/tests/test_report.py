# SPDX-License-Identifier: Apache-2.0
"""A14.2 — pure report/eval core, unit-tested without a deployed stack."""
from __future__ import annotations

import report


# ----- percentiles -----------------------------------------------------------


def test_percentile_nearest_rank() -> None:
    xs = [10, 20, 30, 40, 50, 60, 70, 80, 90, 100]
    assert report.percentile(xs, 50) == 50
    assert report.percentile(xs, 95) == 100
    assert report.percentile([], 50) is None
    assert report.percentile([42.0], 99) == 42.0


# ----- source rate / outcome dist -------------------------------------------


def test_source_rate_and_llm_fallback() -> None:
    recs = [
        {"status": "OK", "source": "RULES"},
        {"status": "OK", "source": "RULES"},
        {"status": "OK", "source": "RULES"},
        {"status": "OK", "source": "LLM"},
        {"status": "UNGROUNDABLE", "source": ""},
    ]
    sr = report.source_rate(recs)
    assert sr["ok_total"] == 4
    assert sr["rules_rate"] == 0.75
    assert sr["llm_fallback_rate"] == 0.25
    assert report.outcome_distribution(recs) == {"OK": 4, "UNGROUNDABLE": 1}


# ----- bulk evaluation -------------------------------------------------------


def _case(**over) -> dict:
    base = {
        "id": "chrono/x", "tool": "ground_time", "kind": "DATE", "locale": "en-US",
        "model": "accounting-period",
        "expect": {"status": "OK", "application": "FILTER", "source": "RULES", "sql_contains": ["t.\"date\" >= {start}"]},
    }
    base.update(over)
    return base


def _ok_actual() -> dict:
    return {
        "ok": True, "status": "OK", "options": [],
        "result": {
            "source": "RULES",
            "sqlPreview": "t.\"date\" >= {start} AND t.\"date\" < {end}",
            "filter": {"parameters": [{"name": "p", "value": {"stringValue": "202605"}}]},
        },
    }


def test_bulk_pass() -> None:
    r = report.evaluate_bulk_case(_case(), _ok_actual())
    assert r["passed"], r["failures"]
    assert r["status"] == "OK" and r["source"] == "RULES"


def test_bulk_status_mismatch() -> None:
    actual = {"ok": True, "status": "UNGROUNDABLE", "result": {}, "options": []}
    r = report.evaluate_bulk_case(_case(), actual)
    assert not r["passed"]
    assert any("status" in f for f in r["failures"])


def test_bulk_sql_fragment_missing() -> None:
    actual = _ok_actual()
    actual["result"]["sqlPreview"] = "something else"
    r = report.evaluate_bulk_case(_case(), actual)
    assert not r["passed"]
    assert any("sql_contains" in f for f in r["failures"])


def test_bulk_period_code_and_application() -> None:
    case = _case(expect={"status": "OK", "application": "JOIN", "period_code": "202605"})
    actual = {
        "ok": True, "status": "OK", "options": [],
        "result": {"join": {"parameters": [{"name": "p", "value": {"stringValue": "202605"}}]}},
    }
    assert report.evaluate_bulk_case(case, actual)["passed"]
    # wrong application
    actual2 = {"ok": True, "status": "OK", "options": [], "result": {"filter": {"parameters": []}}}
    assert not report.evaluate_bulk_case(case, actual2)["passed"]


def test_bulk_transport_error() -> None:
    r = report.evaluate_bulk_case(_case(), {"ok": False, "error": "boom"})
    assert not r["passed"]
    assert any("transport" in f for f in r["failures"])


# ----- e2e evaluation --------------------------------------------------------


def _resp(notes=None, clarifying=False, plan_source="free_sql", sql=None) -> dict:
    env: dict = {"grounding_notes": notes or []}
    if clarifying:
        env["pending_clarification"] = {"options": []}
    if sql is not None:
        env["sql"] = sql
    return {"plan_source": plan_source, "envelope": env}


def test_e2e_grounded_ok() -> None:
    exp = {"grounded": True, "not_clarifying": True, "grounding_note_contains": ["May"], "plan_source_in": ["free_sql"]}
    r = report.evaluate_e2e_turn(exp, _resp(notes=["„May period“: Resolved to May 2026."]))
    assert r["passed"], r["failures"]


def test_e2e_expected_clarification() -> None:
    assert report.evaluate_e2e_turn({"clarifies": True}, _resp(clarifying=True))["passed"]
    assert not report.evaluate_e2e_turn({"clarifies": True}, _resp())["passed"]


def test_e2e_not_grounded_expected() -> None:
    assert report.evaluate_e2e_turn({"grounded": False, "not_clarifying": True}, _resp())["passed"]
    assert not report.evaluate_e2e_turn({"grounded": True}, _resp())["passed"]


def test_e2e_sql_fragment() -> None:
    exp = {"grounded": True, "sql_contains": ["period_start"]}
    ok = report.evaluate_e2e_turn(exp, _resp(notes=["x"], sql="WHERE period_start(?) <= t.date"))
    assert ok["passed"], ok["failures"]
    miss = report.evaluate_e2e_turn(exp, _resp(notes=["x"], sql="WHERE 1=1"))
    assert not miss["passed"]


# ----- summarize -------------------------------------------------------------


def test_summarize_skips_model_unavailable() -> None:
    bulk = [
        {"status": "OK", "source": "RULES", "passed": True, "latency_ms": 12.0},
        {"status": "skipped:model-unavailable", "source": "", "passed": True, "latency_ms": None},
    ]
    e2e = [{"passed": True}, {"passed": False}]
    s = report.summarize(bulk, e2e)
    assert s["bulk"]["scored"] == 1 and s["bulk"]["skipped"] == 1
    assert s["bulk"]["pass_rate"] == 1.0
    assert s["e2e"]["pass_rate"] == 0.5


# ----- #214: a conversation case, the period it lands on, and the options it asks between -----


def test_bulk_interval_start_checked() -> None:
    case = _case(expect={"status": "OK", "interval_start": "2024-01-01"})
    actual = _ok_actual()
    actual["result"]["normalized"] = {"interval": {"start": "2024-01-01T00:00:00+01:00", "end": "2025-01-01T00:00:00+01:00"}}
    assert report.evaluate_bulk_case(case, actual)["passed"]
    actual["result"]["normalized"]["interval"]["start"] = "2025-01-01T00:00:00+01:00"
    r = report.evaluate_bulk_case(case, actual)
    assert not r["passed"]
    assert any("interval_start" in f for f in r["failures"])


def test_bulk_option_ids_checked() -> None:
    case = _case(expect={"status": "AWAITING_CLARIFICATION", "option_ids": ["2025", "2024"]})
    actual = {"ok": True, "status": "AWAITING_CLARIFICATION", "result": None,
              "options": [{"id": "2025", "label": "2025"}, {"id": "2024", "label": "2024"}]}
    assert report.evaluate_bulk_case(case, actual)["passed"]
    actual["options"] = [{"id": "", "label": "x"}, {"id": "", "label": "y"}]
    r = report.evaluate_bulk_case(case, actual)
    assert not r["passed"]
    assert any("option_ids" in f for f in r["failures"])
