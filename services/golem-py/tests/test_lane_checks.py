# SPDX-License-Identifier: Apache-2.0
"""OL W1c·S2·T5 — the checks before the door (OL-C15), as one table.

Every row is a reply text and what `check_reply` must make of it: the SQL it passes, or the
rule it refuses on. The slice, the carried gap and the literal sources are fixed, so a row
changes exactly one thing.
"""

from __future__ import annotations

import json

import pytest

from golem_py.errors import LaneReplyRefused
from golem_py.lane_checks import check_reply, literal_sources
from golem_py.state import (
    Attribution,
    Binding,
    Grounding,
    Mention,
    ResolutionState,
    Span,
    ValueFinding,
    ValueKind,
)

SLICE = ("er.entity.store_sales", "er.entity.store")
CARRIED = (Span(start=22, end=34, text="minulý měsíc"),)
SOURCES = (
    "Zobraz tržby v Praze za minulý měsíc",
    "Praha",
    "md.member.city#Brno",
    "2025-01-01/2025-12-31",
)


def reply(sql: str, spans: list[list[int]] | None = None, note: str = "n") -> str:
    return json.dumps({"sql": sql, "handled_spans": spans or [], "note": note})


def check(text: str):  # type: ignore[no-untyped-def]
    return check_reply(text, allowed_entities=SLICE, carried=CARRIED, literal_sources=SOURCES)


PASSES = [
    ("plain select", "SELECT SUM(s.amount) FROM er.entity.store_sales s"),
    ("trailing semicolon", "SELECT 1 FROM er.entity.store_sales;"),
    (
        "join over two slice entities",
        "SELECT st.city, SUM(s.amount) FROM er.entity.store_sales s "
        "JOIN er.entity.store st ON s.store_id = st.id GROUP BY st.city",
    ),
    ("comma join", "SELECT 1 FROM er.entity.store_sales s, er.entity.store st"),
    ("qualified attribute", "SELECT er.entity.store_sales.amount FROM er.entity.store_sales"),
    (
        "member literal from a binding's key",
        "SELECT 1 FROM er.entity.store st WHERE st.city = 'Brno'",
    ),
    (
        "member literal from the question",
        "SELECT 1 FROM er.entity.store st WHERE st.city = 'Praze'",
    ),
    ("member literal from a value", "SELECT 1 FROM er.entity.store st WHERE st.city = 'praha'"),
    ("like pattern", "SELECT 1 FROM er.entity.store st WHERE st.city LIKE 'Pra%'"),
    (
        "a relative period as dates",
        "SELECT 1 FROM er.entity.store_sales s "
        "WHERE s.sold_on >= DATE '2026-10-01' AND s.sold_on < '2026-11-01'",
    ),
    (
        "interval literal",
        "SELECT 1 FROM er.entity.store_sales s WHERE s.d > s.e - INTERVAL '1' MONTH",
    ),
    ("extract from", "SELECT EXTRACT(YEAR FROM s.sold_on) FROM er.entity.store_sales s"),
    ("is distinct from", "SELECT 1 FROM er.entity.store_sales s WHERE s.a IS DISTINCT FROM s.b"),
    ("postgres cast", "SELECT s.sold_on::date FROM er.entity.store_sales s"),
    (
        "sub-query in FROM",
        "SELECT t.n FROM (SELECT COUNT(*) AS n FROM er.entity.store_sales) t, er.entity.store",
    ),
    ("a column named like a keyword", "SELECT s.update FROM er.entity.store_sales s"),
    ("comment", "SELECT 1 -- the total\nFROM er.entity.store_sales"),
]


@pytest.mark.parametrize(("case", "sql"), PASSES, ids=[c for c, _ in PASSES])
def test_a_reply_that_keeps_the_rules_passes(case: str, sql: str) -> None:
    q = check(reply(sql, [[22, 34]], note="the period"))
    assert q.sql == sql.strip()
    assert q.handled_spans == ((22, 34),)
    assert q.note == "the period"


REFUSALS = [
    ("not json", "SELECT 1", "invalid_object"),
    (
        "fenced json",
        "```json\n" + reply("SELECT 1 FROM er.entity.store") + "\n```",
        "invalid_object",
    ),
    ("an array", json.dumps([{"sql": "SELECT 1"}]), "invalid_object"),
    ("no sql", json.dumps({"handled_spans": []}), "invalid_object"),
    ("sql not a string", json.dumps({"sql": 1}), "invalid_object"),
    ("spans not pairs", json.dumps({"sql": "SELECT 1", "handled_spans": [[1]]}), "invalid_object"),
    (
        "span of bools",
        json.dumps({"sql": "SELECT 1", "handled_spans": [[True, False]]}),
        "invalid_object",
    ),
    ("note not a string", json.dumps({"sql": "SELECT 1", "note": 3}), "invalid_object"),
    (
        "a span no gap sits on",
        reply("SELECT 1 FROM er.entity.store", [[0, 6]]),
        "handled_span_not_carried",
    ),
    (
        "two statements",
        reply("SELECT 1 FROM er.entity.store; SELECT 2 FROM er.entity.store"),
        "not_select",
    ),
    ("a CTE", reply("WITH x AS (SELECT 1) SELECT * FROM x"), "not_select"),
    ("a delete", reply("DELETE FROM er.entity.store"), "not_select"),
    ("select into", reply("SELECT * INTO t FROM er.entity.store"), "not_select"),
    (
        "a drop hidden after a select",
        reply("SELECT 1 FROM er.entity.store WHERE DROP"),
        "not_select",
    ),
    (
        "question-mark parameter",
        reply("SELECT 1 FROM er.entity.store st WHERE st.id = ?"),
        "parameter",
    ),
    ("named parameter", reply("SELECT 1 FROM er.entity.store st WHERE st.id = :id"), "parameter"),
    ("dollar parameter", reply("SELECT 1 FROM er.entity.store st WHERE st.id = $1"), "parameter"),
    ("at parameter", reply("SELECT 1 FROM er.entity.store st WHERE st.id = @id"), "parameter"),
    ("an er name outside the slice", reply("SELECT 1 FROM er.entity.customer"), "outside_slice"),
    ("a bare table", reply("SELECT 1 FROM store_sales"), "outside_slice"),
    ("a db name", reply("SELECT 1 FROM db.public.store_sales"), "outside_slice"),
    ("a bare table after a comma", reply("SELECT 1 FROM er.entity.store, sales"), "outside_slice"),
    (
        "a bare table after a join",
        reply("SELECT 1 FROM er.entity.store s JOIN sales x ON 1 = 1"),
        "outside_slice",
    ),
    (
        "outside the slice in a column",
        reply("SELECT er.entity.customer.id FROM er.entity.store"),
        "outside_slice",
    ),
    (
        "a member from nowhere",
        reply("SELECT 1 FROM er.entity.store st WHERE st.city = 'Ostrava'"),
        "literal_unsourced",
    ),
    (
        "an unterminated quote",
        reply("SELECT 1 FROM er.entity.store st WHERE st.city = 'Praha"),
        "invalid_sql",
    ),
]


@pytest.mark.parametrize(("case", "text", "rule"), REFUSALS, ids=[c for c, _, _ in REFUSALS])
def test_a_reply_that_breaks_a_rule_is_refused_naming_it(case: str, text: str, rule: str) -> None:
    with pytest.raises(LaneReplyRefused) as exc:
        check(text)
    assert exc.value.rule == rule, exc.value.detail


def test_handled_spans_default_to_none_and_repeat_once() -> None:
    assert check(json.dumps({"sql": "SELECT 1 FROM er.entity.store"})).handled_spans == ()
    q = check(reply("SELECT 1 FROM er.entity.store", [[22, 34], [22, 34]]))
    assert q.handled_spans == ((22, 34),)


def test_the_literal_sources_are_what_the_lattice_carries() -> None:
    state = ResolutionState(
        question="Tržby za účet 501001 v Praze",
        mentions=[
            Mention(
                id="m1",
                span=Span(start=23, end=28, text="Praze"),
                lemma="Praha",
                bindings=[Binding(ref="md.member.city#Praha")],
            )
        ],
        values=[
            ValueFinding(
                id="v1",
                span=Span(start=14, end=20, text="501001"),
                kind=ValueKind.GROUNDED,
                attributions=[Attribution(attribute_ref="er.CostCenter.code#220")],
                grounding=Grounding(kind="DATE", normalized_value="2025-01-01/2025-12-31"),
            ),
            ValueFinding(id="v2", kind=ValueKind.VERBATIM, verbatim_text="Hartland Prime"),
        ],
    )
    sources = literal_sources(state)
    for expected in (
        "Tržby za účet 501001 v Praze",
        "Praha",
        "md.member.city#Praha",
        "220",
        "2025-01-01/2025-12-31",
        "Hartland Prime",
    ):
        assert expected in sources
