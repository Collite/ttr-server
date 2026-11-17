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
    "Brno",
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
    ("like pattern", "SELECT 1 FROM er.entity.store st WHERE st.city LIKE 'Praha%'"),
    ("a member on the right", "SELECT 1 FROM er.entity.store st WHERE 'Brno' = st.city"),
    ("REPLACE is a string function", "SELECT REPLACE(st.city,'a','b') FROM er.entity.store st"),
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
    ("a bare er", reply("SELECT * FROM er s"), "outside_slice"),
    (
        "cross apply",
        reply("SELECT 1 FROM er.entity.store_sales s CROSS APPLY other.fn(s.x) f"),
        "outside_slice",
    ),
    ("lateral", reply("SELECT 1 FROM er.entity.store s, LATERAL other.fn(s.x) f"), "outside_slice"),
    (
        "replace into",
        reply("REPLACE INTO er.entity.store_sales SELECT * FROM er.entity.store_sales"),
        "not_select",
    ),
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
        "a cut-off word in a like",
        reply("SELECT 1 FROM er.entity.store st WHERE st.city LIKE 'Pra%'"),
        "literal_unsourced",
    ),
    (
        "a member from nowhere on the right",
        reply("SELECT 1 FROM er.entity.store st WHERE 'Ostrava' <> st.city"),
        "literal_unsourced",
    ),
    (
        "a member from nowhere in NOT IN",
        reply("SELECT 1 FROM er.entity.store st WHERE st.city NOT IN ('Praha', 'Ostrava')"),
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


# review-329 F1 (✅R329-A): only a literal that restricts a member is traced, and it must be a
# whole word or run of words of a source. Sources as the review probed them: the question and
# two ref paths (which `literal_sources` no longer yields — they show a path is no word store).
PROBE = ("Zobraz tržby za minulý měsíc", "er.entity.store_sales", "er.attribute.store_sales.region")
OSTRAVA = ("Tržby prodejny Ostrava za minulý měsíc",)
SS = "SELECT 1 FROM er.entity.store_sales s"
SS2 = "SELECT 1 FROM er.entity.store s"
MEMBER_ROWS = [
    (
        "a function's unit argument",
        "SELECT DATE_TRUNC('month', DATE '2026-11-16') - INTERVAL '1' MONTH "
        "FROM er.entity.store_sales s",
        PROBE,
        None,
    ),
    (
        "a COALESCE fallback",
        "SELECT COALESCE(s.region,'unknown') FROM er.entity.store_sales s",
        PROBE,
        None,
    ),
    ("a format string", "SELECT to_char(s.d,'YYYY-MM') FROM er.entity.store_sales s", PROBE, None),
    (
        "a CASE label",
        "SELECT CASE WHEN s.amount > 0 THEN 'up' ELSE 'down' END FROM er.entity.store_sales s",
        PROBE,
        None,
    ),
    (
        "a letter of the question",
        "SELECT 1 FROM er.entity.store_sales s WHERE s.region = 'a'",
        PROBE,
        "a",
    ),
    (
        "a word of a ref path",
        "SELECT 1 FROM er.entity.store_sales s WHERE s.region = 'sales'",
        PROBE,
        "sales",
    ),
    ("a cut-off word", "SELECT 1 FROM er.entity.store_sales s WHERE s.region = 'tr'", PROBE, "tr"),
    ("a whole word", "SELECT 1 FROM er.entity.store st WHERE st.name = 'Ostrava'", OSTRAVA, None),
    (
        "an IN with one member from nowhere",
        "SELECT 1 FROM er.entity.store st WHERE st.name IN ('Ostrava','Brno')",
        OSTRAVA,
        "Brno",
    ),
    (
        "a LIKE on a cut-off word",
        "SELECT 1 FROM er.entity.store st WHERE st.name LIKE '%Ostrav%'",
        OSTRAVA,
        "%Ostrav%",
    ),
    (
        "a LIKE on a whole word",
        "SELECT 1 FROM er.entity.store st WHERE st.name LIKE '%Ostrava%'",
        OSTRAVA,
        None,
    ),
    (
        "a sub-query IN is no member list",
        "SELECT 1 FROM er.entity.store st "
        "WHERE st.id IN (SELECT s.id FROM er.entity.store_sales s)",
        OSTRAVA,
        None,
    ),
    # review-329 re-review R1 (✅R329-C, carried to W1c·S3·T0): the other operand is not a
    # literal, so the literal is traced — a column, `f(column)`, `CAST(… AS …)`, a `CASE … END`.
    # The first seven rows are the review's probe, 'Brno' nowhere in the sources.
    ("R1 an upper-cased column", f"{SS} WHERE UPPER(s.region) = 'BRNO'", PROBE, "BRNO"),
    (
        "R1 lower on both sides",
        f"{SS} WHERE LOWER(s.region) = LOWER('Brno')",
        PROBE,
        "Brno",
    ),
    ("R1 a trimmed column in a LIKE", f"{SS} WHERE TRIM(s.region) LIKE 'Brno%'", PROBE, "Brno%"),
    ("R1 a cast column", f"{SS} WHERE CAST(s.code AS VARCHAR) = 'X9'", PROBE, "X9"),
    ("R1 an upper-cased column's IN", f"{SS} WHERE UPPER(s.region) IN ('BRNO')", PROBE, "BRNO"),
    (
        "R1 a simple CASE label",
        "SELECT CASE s.region WHEN 'Brno' THEN 1 END FROM er.entity.store_sales s",
        PROBE,
        "Brno",
    ),
    ("R1 a prefixed literal", f"{SS} WHERE s.region = N'Brno'", PROBE, "Brno"),
    ("an escape-prefixed literal", f"{SS} WHERE s.region = E'Brno'", PROBE, "Brno"),
    ("a call on the right", f"{SS} WHERE 'Brno' = UPPER(s.region)", PROBE, "Brno"),
    ("a cast literal", f"{SS} WHERE s.region = CAST('Brno' AS VARCHAR)", PROBE, "Brno"),
    ("a ::-cast literal", f"{SS} WHERE s.region = 'Brno'::text", PROBE, "Brno"),
    ("a parenthesised literal", f"{SS} WHERE s.region = ('Brno')", PROBE, "Brno"),
    ("a wrapped IN element", f"{SS} WHERE s.region IN (UPPER('Brno'), 'x')", PROBE, "Brno"),
    ("an IN after a call", f"{SS} WHERE LOWER(s.region) NOT IN ('brno')", PROBE, "brno"),
    (
        "a CASE expression compared",
        f"{SS} WHERE CASE WHEN s.a > 0 THEN s.region END = 'Brno'",
        PROBE,
        "Brno",
    ),
    (
        "a not-like on a call",
        f"{SS} WHERE LOWER(s.region) NOT LIKE 'brno%'",
        PROBE,
        "brno%",
    ),
    # …and what stays untraced or sourced.
    (
        "a sourced member, case-folded",
        f"{SS} WHERE LOWER(s.name) = LOWER('Ostrava')",
        OSTRAVA,
        None,
    ),
    (
        "a sourced simple CASE label",
        f"{SS2} WHERE CASE s.name WHEN 'Ostrava' THEN 1 END = 1",
        OSTRAVA,
        None,
    ),
    # review-343 ✅R343-A: inside WHERE every literal is traced by position, compared or not.
    ("two literals compared in WHERE", f"{SS} WHERE 'a' = 'b'", PROBE, "a"),
    ("a literal against a number in WHERE", f"{SS} WHERE LOWER('x') = 1", PROBE, "x"),
    (
        "a date range on a call",
        f"{SS} WHERE DATE_TRUNC('month', s.sold_on) = DATE '2026-10-01'",
        PROBE,
        None,
    ),
    ("a month on a call", f"{SS} WHERE TO_CHAR(s.sold_on, 'YYYY-MM') = '2026-10'", PROBE, None),
    (
        "a searched CASE's labels",
        "SELECT CASE WHEN s.region IS NULL THEN 'none' ELSE 'some' END "
        "FROM er.entity.store_sales s",
        PROBE,
        None,
    ),
]


@pytest.mark.parametrize(
    ("case", "sql", "sources", "refused"), MEMBER_ROWS, ids=[r[0] for r in MEMBER_ROWS]
)
def test_only_a_member_restriction_is_traced_and_only_as_whole_words(
    case: str, sql: str, sources: tuple[str, ...], refused: str | None
) -> None:
    def run() -> object:
        return check_reply(
            reply(sql), allowed_entities=SLICE, carried=CARRIED, literal_sources=sources
        )

    if refused is None:
        run()
        return
    with pytest.raises(LaneReplyRefused) as exc:
        run()
    assert exc.value.rule == "literal_unsourced"
    assert repr(refused) in exc.value.detail


def test_a_member_said_in_an_inflected_form_is_named_by_its_lemma() -> None:
    state = ResolutionState(
        question="Tržby v Ostravě",
        mentions=[Mention(id="m1", span=Span(start=8, end=15, text="Ostravě"), lemma="Ostrava")],
    )
    q = check_reply(
        reply("SELECT 1 FROM er.entity.store st WHERE st.name = 'Ostrava'"),
        allowed_entities=SLICE,
        carried=CARRIED,
        literal_sources=literal_sources(state),
    )
    assert "'Ostrava'" in q.sql


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
        "220",
        "2025-01-01/2025-12-31",
        "Hartland Prime",
    ):
        assert expected in sources
    # A ref path is no source; only a member ref's key is.
    assert not any("." in s and "#" in s for s in sources)


# review-343 ✅R343-A — trace by POSITION, exempt by NAME. Sources: the question alone.
R343_SOURCES = ("Zobraz tržby za minulý měsíc",)
R343_FROM = "SELECT SUM(s.amount) FROM er.entity.store_sales s "
R343_WHERE = R343_FROM + "WHERE "
R343_REFUSE = [
    # L3 · concatenation
    ("concat on the column side", R343_WHERE + "s.region || '' = 'Brno'"),
    ("concat on the literal side", R343_WHERE + "s.region = '' || 'Brno'"),
    ("concat column in IN", R343_WHERE + "s.region || '' IN ('Brno')"),
    ("concat inside IN", R343_WHERE + "s.region IN ('' || 'Brno')"),
    # L3 · literal-only operand
    ("COALESCE of literals", R343_WHERE + "s.region = COALESCE(NULL,'Brno')"),
    ("REPLACE of literals", R343_WHERE + "s.region = REPLACE('Xrno','X','B')"),
    ("CASE of literals", R343_WHERE + "s.region = CASE WHEN 1 = 1 THEN 'Brno' END"),
    ("scalar sub-query", R343_WHERE + "s.region = (SELECT 'Brno')"),
    ("IN sub-query of a literal", R343_WHERE + "s.region IN (SELECT 'Brno')"),
    # L3 · other operators
    ("BETWEEN", R343_WHERE + "s.region BETWEEN 'Brno' AND 'Brno'"),
    ("range", R343_WHERE + "s.region >= 'Brno' AND s.region <= 'Brno'"),
    ("IS NOT DISTINCT FROM", R343_WHERE + "s.region IS NOT DISTINCT FROM 'Brno'"),
    ("SIMILAR TO", R343_WHERE + "s.region SIMILAR TO 'Brno'"),
    ("= ANY(ARRAY[…])", R343_WHERE + "s.region = ANY(ARRAY['Brno'])"),
    # L3 · a member fed from a sub-query
    ("derived table", R343_FROM + "JOIN (SELECT 'Brno' AS r) x ON s.region = x.r"),
    ("VALUES list", R343_FROM + "JOIN (VALUES ('Brno')) v(r) ON s.region = v.r"),
    (
        "nested derived table in IN",
        R343_WHERE + "s.region IN (SELECT r FROM (SELECT 'Brno' AS r) t)",
    ),
    (
        "conditional aggregation",
        "SELECT SUM(CASE WHEN s.region BETWEEN 'Brno' AND 'Brno' THEN s.amount END) "
        "FROM er.entity.store_sales s",
    ),
    # ✅R329-C's seven rows
    ("R329-C upper", R343_WHERE + "UPPER(s.region) = 'BRNO'"),
    ("R329-C lower both", R343_WHERE + "LOWER(s.region) = LOWER('Brno')"),
    ("R329-C trim like", R343_WHERE + "TRIM(s.region) LIKE 'Brno%'"),
    ("R329-C cast column", R343_WHERE + "CAST(s.code AS VARCHAR) = 'X9'"),
    ("R329-C upper IN", R343_WHERE + "UPPER(s.region) IN ('BRNO')"),
    (
        "R329-C simple CASE",
        "SELECT CASE s.region WHEN 'Brno' THEN 1 END FROM er.entity.store_sales s",
    ),
    ("R329-C prefixed", R343_WHERE + "s.region = N'Brno'"),
    # a digit-only member code compared to a column stays traced
    ("a member code", R343_WHERE + "s.code = '00123'"),
]
R343_PASS = [
    # L1 · period labels compared to a date function
    ("YYYYMM label", R343_WHERE + "TO_CHAR(s.sold_on,'YYYYMM') = '202610'"),
    ("MM/YYYY label", R343_WHERE + "TO_CHAR(s.sold_on,'MM/YYYY') = '10/2026'"),
    ("quarter label", R343_WHERE + """TO_CHAR(s.sold_on,'YYYY-"Q"Q') = '2026-Q3'"""),
    ("ISO month label", R343_WHERE + "TO_CHAR(s.sold_on,'YYYY-MM') = '2026-10'"),
    ("a label BETWEEN", R343_WHERE + "TO_CHAR(s.sold_on,'YYYYMM') BETWEEN '202601' AND '202610'"),
    ("labels IN", R343_WHERE + "TO_CHAR(s.sold_on,'YYYYMM') IN ('202609', '202610')"),
    ("a number", R343_WHERE + "EXTRACT(YEAR FROM s.sold_on) = 2025"),
    (
        "a unit, a typed date, an interval",
        R343_WHERE + "s.sold_on >= DATE_TRUNC('month', DATE '2026-11-16') - INTERVAL '1' MONTH",
    ),
    ("a date function's arguments", R343_WHERE + "s.sold_on >= TO_DATE('01.10.2026','DD.MM.YYYY')"),
    ("a ::interval cast", R343_WHERE + "s.sold_on >= CURRENT_DATE - '1 month'::interval"),
    ("a CAST to DATE", R343_WHERE + "s.sold_on >= CAST('1.10.2026' AS DATE)"),
    (
        "the outermost select list",
        "SELECT COALESCE(s.region,'unknown'), "
        "CASE WHEN s.amount > 0 THEN 'kladné' ELSE 'jiné' END, REPLACE(s.region,'a','b') "
        "FROM er.entity.store_sales s",
    ),
    (
        "GROUP BY and ORDER BY",
        "SELECT TO_CHAR(s.sold_on,'YYYY-MM') AS m, SUM(s.amount) FROM er.entity.store_sales s "
        "GROUP BY TO_CHAR(s.sold_on,'YYYY-MM'), COALESCE(s.region,'?') "
        "ORDER BY COALESCE(s.region,'?')",
    ),
    ("a whole word of the question", R343_WHERE + "s.note LIKE '%tržby%'"),
]


def _r343(sql: str) -> object:
    return check_reply(
        reply(sql), allowed_entities=SLICE, carried=CARRIED, literal_sources=R343_SOURCES
    )


@pytest.mark.parametrize(("case", "sql"), R343_REFUSE, ids=[c for c, _ in R343_REFUSE])
def test_r343_an_invented_member_is_refused_wherever_it_sits(case: str, sql: str) -> None:
    with pytest.raises(LaneReplyRefused) as exc:
        _r343(sql)
    assert exc.value.rule == "literal_unsourced", exc.value.detail


@pytest.mark.parametrize(("case", "sql"), R343_PASS, ids=[c for c, _ in R343_PASS])
def test_r343_periods_dates_and_the_outer_select_list_pass(case: str, sql: str) -> None:
    _r343(sql)


@pytest.mark.parametrize("depth", [65, 2000])
def test_r343_deep_nesting_is_refused_never_crashes(depth: int) -> None:
    sql = R343_WHERE + "s.region = " + "(" * depth + "'x'" + ")" * depth
    with pytest.raises(LaneReplyRefused) as exc:
        _r343(sql)
    assert exc.value.rule == "invalid_sql"


def test_r343_nesting_up_to_the_cap_is_checked_normally() -> None:
    sql = R343_WHERE + "s.region = " + "(" * 60 + "'tržby'" + ")" * 60
    _r343(sql)
