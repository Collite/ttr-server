# SPDX-License-Identifier: Apache-2.0
"""A14.1 — consolidate the per-service golden corpora into the standalone
grounding eval corpus.

Reads the three Kotlin test corpora (the A8/A9/A10 golden sets) plus this
directory's hand-authored `corpus/supplemental.json`, normalizes every row into
the unified bulk-case schema (see `SCHEMA.md`), and writes
`corpus/grounding-cases.json` (the committed source of truth the runner loads).

    python eval/grounding/build_corpus.py            # regenerate + summary
    python eval/grounding/build_corpus.py --check     # fail if the committed file is stale

Kept deliberately dependency-free (stdlib only) so it runs in any environment.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

_HERE = Path(__file__).resolve().parent
_REPO = _HERE.parents[1]
_OUT = _HERE / "corpus" / "grounding-cases.json"
_SUPPLEMENTAL = _HERE / "corpus" / "supplemental.json"

_DEFAULT_REF = "2026-05-15T12:00:00+02:00"
_DEFAULT_TZ = "Europe/Prague"

_SOURCES = {
    "chrono": _REPO / "services/chrono/src/test/resources/corpus/chrono/cases.json",
    "geo": _REPO / "services/geo/src/test/resources/corpus/geo/cases.json",
    "money": _REPO / "services/money/src/test/resources/corpus/money/cases.json",
}

# A minimal Czech-ness detector: cs-specific letters or high-signal function
# words. Good enough to balance/report cs vs en; not a general classifier.
_CS_CHARS = set("áčďéěíňóřšťúůýžÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ")
_CS_WORDS = {
    "od", "do", "v", "ve", "nad", "pod", "kolem", "za", "období", "včera", "letos",
    "loni", "měsíc", "měsíce", "měsíců", "poslední", "posledních", "tento", "příští",
    "minulý", "dnes", "týden", "roce", "mil", "milionu", "tisíc", "tis", "přesně", "mezi",
}


def _detect_locale(span: str, explicit: str | None) -> str:
    if explicit:
        return explicit
    lo = span.lower()
    if any(ch in _CS_CHARS for ch in span) or any(w in lo.split() for w in _CS_WORDS):
        return "cs-CZ"
    return "en-US"


def _expect(raw: dict[str, Any]) -> dict[str, Any]:
    exp = raw.get("expect") or {}
    out: dict[str, Any] = {"status": exp["status"]}
    for src, dst in (
        ("application", "application"),
        ("source", "source"),
        ("periodCode", "period_code"),
        ("intervalStart", "interval_start"),
    ):
        if exp.get(src) is not None:
            out[dst] = exp[src]
    if exp.get("sqlContains"):
        out["sql_contains"] = list(exp["sqlContains"])
    if exp.get("optionIds"):
        out["option_ids"] = list(exp["optionIds"])
    return out


def _conversation(raw: dict[str, Any]) -> dict[str, Any]:
    """The question a span is asked in, the earlier questions, and a clarification answer — only when set (#214)."""
    out: dict[str, Any] = {}
    if raw.get("question"):
        out["question_text"] = raw["question"]
    if raw.get("priorQuestions"):
        out["prior_questions"] = list(raw["priorQuestions"])
    if raw.get("answerId"):
        out["clarification_answer_id"] = raw["answerId"]
    return out


def _chrono(raw: dict[str, Any]) -> dict[str, Any]:
    ref_date = raw.get("reference", "2026-05-15")
    return {
        "id": f"chrono/{raw['name']}",
        "tool": "ground_time",
        "kind": "DATE",
        "span": raw["span"],
        "locale": _detect_locale(raw["span"], raw.get("locale")),
        "reference_datetime": raw.get("referenceDatetime") or f"{ref_date}T12:00:00+02:00",
        "timezone": raw.get("timezone", _DEFAULT_TZ),
        "package": raw.get("pkg", "cnc"),
        "model": "accounting-period" if raw.get("periodTable", True) else "calendar",
        "here": None,
        **_conversation(raw),
        "expect": _expect(raw),
    }


def _geo(raw: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": f"geo/{raw['name']}",
        "tool": "ground_geo",
        "kind": "LOCATION",
        "span": raw["span"],
        "locale": _detect_locale(raw["span"], raw.get("locale")),
        "reference_datetime": raw.get("referenceDatetime") or _DEFAULT_REF,
        "timezone": raw.get("timezone", _DEFAULT_TZ),
        "package": raw.get("pkg", "cnc"),
        "model": "geo-brno-praha",
        "here": raw.get("here") or None,
        "expect": _expect(raw),
    }


def _money(raw: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": f"money/{raw['name']}",
        "tool": "ground_money",
        "kind": "MONEY",
        "span": raw["span"],
        "locale": _detect_locale(raw["span"], raw.get("locale")),
        "reference_datetime": raw.get("referenceDatetime") or _DEFAULT_REF,
        "timezone": raw.get("timezone", _DEFAULT_TZ),
        "package": raw.get("pkg", "cnc"),
        "model": f"money-{raw.get('model', 'domestic')}",
        "here": None,
        "expect": _expect(raw),
    }


_NORMALIZERS = {"chrono": _chrono, "geo": _geo, "money": _money}


def build() -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    for service, path in _SOURCES.items():
        rows = json.loads(path.read_text(encoding="utf-8"))
        cases.extend(_NORMALIZERS[service](r) for r in rows)
    if _SUPPLEMENTAL.exists():
        cases.extend(json.loads(_SUPPLEMENTAL.read_text(encoding="utf-8")))
    cases.sort(key=lambda c: c["id"])
    return cases


def _summary(cases: list[dict[str, Any]]) -> str:
    by_tool: dict[str, int] = {}
    by_locale: dict[str, int] = {}
    by_status: dict[str, int] = {}
    for c in cases:
        by_tool[c["tool"]] = by_tool.get(c["tool"], 0) + 1
        lang = "cs" if str(c["locale"]).lower().startswith("cs") else "en"
        by_locale[lang] = by_locale.get(lang, 0) + 1
        by_status[c["expect"]["status"]] = by_status.get(c["expect"]["status"], 0) + 1
    return (
        f"{len(cases)} bulk cases | tool={by_tool} | locale={by_locale} | status={by_status}"
    )


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Build the grounding eval corpus")
    ap.add_argument("--check", action="store_true", help="fail if committed file is stale")
    args = ap.parse_args(argv)

    cases = build()
    rendered = json.dumps(cases, indent=2, ensure_ascii=False) + "\n"

    if args.check:
        current = _OUT.read_text(encoding="utf-8") if _OUT.exists() else ""
        if current != rendered:
            print("grounding-cases.json is STALE — run `just eval-grounding-build`", file=sys.stderr)
            return 1
        print(f"OK (up to date): {_summary(cases)}")
        return 0

    _OUT.write_text(rendered, encoding="utf-8")
    print(f"wrote {_OUT.relative_to(_REPO)} — {_summary(cases)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
