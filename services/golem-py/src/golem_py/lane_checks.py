# SPDX-License-Identifier: Apache-2.0
"""The checks before the door (OL-C15, W1c·S2·T5) — one PURE function.

The LLM's reply must be `{"sql": "...", "handled_spans": [[start, end], …], "note": "..."}`,
and before the door is called the code checks the rules the prompt states:

* **one `SELECT`** — a single statement (a trailing `;` is tolerated), starting with
  `SELECT`; no data or schema keyword (`INSERT`, `DROP`, `INTO`, …) anywhere;
* **over `er.*` names from the slice only** — every dotted name starting with `er` names an
  entity of the slice (or one of its attributes), and every table position (after `FROM`,
  `JOIN`, `APPLY`, or a comma in a `FROM` list) holds an entity of the slice or a sub-query;
* **no parameters** — no `?`, `$1`, `:name` or `@name`;
* **a literal that restricts a member comes from a binding, a value or the question's own
  words** (review-343 ✅R343-A). A string literal (plain or `N'…'`/`E'…'`) is TRACED by where it
  sits:
  (a) in a condition clause, at any depth, from its keyword to the clause's end on that level:
      `WHERE` (including `FILTER (WHERE …)`), `HAVING`, `ON`, `QUALIFY`;
  (b) between `WHEN` and `THEN` of any `CASE`, anywhere (a simple `CASE <expr> WHEN '…'` label
      included; a `THEN`/`ELSE` result is not part of this position);
  (c) in the select list of any SELECT that is not outermost, and in any `VALUES ( … )` list —
      a derived table or a scalar sub-query feeds a comparison;
  (d) anywhere, as an operand of `=`, `<>`, `!=`, `LIKE` or `ILIKE` (with `NOT`) whose other
      operand is not itself a literal (`UPPER(s.region) = 'BRNO' AS is_brno`).
  Only the outermost select list (outside b and d), `GROUP BY` and `ORDER BY` are not traced.
  A traced literal must be a whole word or a whole run of words of what the lattice carries
  (see `literal_sources`), unless it is EXEMPT by name: (i) a typed temporal literal
  (`DATE '…'`, `INTERVAL '…'`) or one cast to a temporal type (`'1 month'::interval`,
  `CAST('…' AS DATE)`); (ii) an ISO date; (iii) a direct argument of a date function
  (`_DATE_FUNCTIONS`: `TO_CHAR(s.d,'YYYYMM')`, `DATE_TRUNC('month', …)`); (iv) a period label —
  digits, optionally followed by separated groups (`202610`, `10/2026`, `2026-Q3`) — compared by
  any operator, `BETWEEN` or `IN` to a call of such a function; (v) `%` at its edges is dropped
  first, and an empty needle is no member. A digit-only literal compared to a COLUMN is a member
  code and stays traced;
* **`handled_spans` names carried gaps** — a claim on a span no carried gap sits on is not a
  claim the envelope can hold beside `gaps_carried` (OL-C17).

The reply is parsed strictly. A reply wrapped in a code fence, or carrying anything but a
JSON object, is an invalid object: the lane refuses rather than repairs it (OL-C16) — a
"lenient" parser would hide exactly the failure the refusal exists to report.

This is a tokenizer, not a SQL parser, and it does not try to be one: the door translates
and validates the query and re-checks the allow-list against the plan it built (OL-C12).
What this function guarantees is that nothing the rules forbid is ever SENT.
"""

from __future__ import annotations

import json
import re
from collections.abc import Iterable, Sequence
from dataclasses import dataclass

from golem_py.errors import LaneReplyRefused
from golem_py.state import ResolutionState, Span

# Keywords that make a statement something other than a read, or a read that writes.
FORBIDDEN_KEYWORDS = frozenset(
    {
        "INSERT",
        "UPDATE",
        "DELETE",
        "MERGE",
        "UPSERT",
        # Not REPLACE: it is also a read-only string function, and `REPLACE INTO` cannot get
        # through anyway — the statement must start with SELECT and INTO is refused alone.
        "DROP",
        "CREATE",
        "ALTER",
        "TRUNCATE",
        "GRANT",
        "REVOKE",
        "EXEC",
        "EXECUTE",
        "CALL",
        "INTO",
        "COPY",
    }
)
# Keywords that end a FROM list at the depth they appear on.
_FROM_ENDERS = frozenset(
    {
        "WHERE",
        "GROUP",
        "HAVING",
        "ORDER",
        "LIMIT",
        "OFFSET",
        "FETCH",
        "UNION",
        "EXCEPT",
        "INTERSECT",
        "WINDOW",
        "QUALIFY",
    }
)
# A string literal right after one of these is temporal, not a member (exemption i).
_TYPED_LITERALS = frozenset({"DATE", "TIME", "TIMESTAMP", "INTERVAL"})
# The comparisons that restrict an expression to a member.
_EQUALITY = frozenset({"=", "<>", "!="})
_PATTERN = frozenset({"LIKE", "ILIKE"})
_TEMPORAL = re.compile(r"^\d{4}-\d{2}(-\d{2})?([ T]\d{2}:\d{2}(:\d{2}(\.\d+)?)?)?$")

_TOKEN = re.compile(
    r"""
     (?P<ws>\s+)
    |(?P<comment>--[^\n]*|/\*.*?\*/)
    |(?P<string>[NnEe]?'(?:[^']|'')*')
    |(?P<qident>"(?:[^"]|"")*")
    |(?P<number>\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)
    |(?P<param>\?|\$\d+|\$[A-Za-z_]\w*|@@?[A-Za-z_]\w*|(?<!:):[A-Za-z_]\w*)
    |(?P<ident>[A-Za-z_][A-Za-z0-9_$]*)
    |(?P<op>::|<=|>=|<>|!=|\|\||[(),.;*=<>+\-/%])
    |(?P<other>.)
    """,
    re.VERBOSE | re.DOTALL,
)


@dataclass(frozen=True)
class LaneQuery:
    """A reply that passed every check: the SQL to send and what it claims to handle."""

    sql: str
    handled_spans: tuple[tuple[int, int], ...] = ()
    note: str = ""


@dataclass(frozen=True)
class _Tok:
    kind: str
    text: str

    @property
    def upper(self) -> str:
        return self.text.upper() if self.kind == "ident" else ""


def _tokens(sql: str) -> list[_Tok]:
    out: list[_Tok] = []
    for m in _TOKEN.finditer(sql):
        kind = m.lastgroup or "other"
        if kind in ("ws", "comment"):
            continue
        text = m.group()
        if kind == "other" and text in ("'", '"'):
            raise LaneReplyRefused("invalid_sql", "an unterminated quote")
        out.append(_Tok(kind, text))
    return out


def _part(tok: _Tok) -> str:
    """One part of a dotted name, case-folded; a quoted identifier loses its quotes."""
    if tok.kind == "qident":
        return tok.text[1:-1].replace('""', '"').casefold()
    return tok.text.casefold()


def _name_at(toks: Sequence[_Tok], i: int) -> tuple[list[str], int]:
    """The dotted name starting at `i` and the index just after it."""
    parts = [_part(toks[i])]
    j = i + 1
    while j + 1 < len(toks) and toks[j].text == "." and toks[j + 1].kind in ("ident", "qident"):
        parts.append(_part(toks[j + 1]))
        j += 2
    return parts, j


def _in_slice(parts: list[str], allowed: Sequence[list[str]]) -> bool:
    return any(parts[: len(a)] == a for a in allowed)


# The deepest parenthesis nesting a reply may use. Checked before anything else, so no later
# step can be driven into a recursion it was not written for (review-343 L2).
MAX_DEPTH = 64


def _check_depth(toks: Sequence[_Tok]) -> None:
    depth = 0
    for tok in toks:
        if tok.text == "(":
            depth += 1
            if depth > MAX_DEPTH:
                raise LaneReplyRefused(
                    "invalid_sql", f"parentheses nest deeper than {MAX_DEPTH} levels"
                )
        elif tok.text == ")":
            depth -= 1


def _check_statement(toks: list[_Tok]) -> list[_Tok]:
    _check_depth(toks)
    while toks and toks[-1].text == ";":
        toks = toks[:-1]
    if not toks:
        raise LaneReplyRefused("not_select", "the SQL is empty")
    if any(t.text == ";" for t in toks):
        raise LaneReplyRefused("not_select", "more than one statement")
    if toks[0].upper != "SELECT":
        raise LaneReplyRefused("not_select", f"the statement starts with {toks[0].text!r}")
    for i, tok in enumerate(toks):
        after_dot = i > 0 and toks[i - 1].text == "."
        if tok.upper in FORBIDDEN_KEYWORDS and not after_dot:
            raise LaneReplyRefused("not_select", f"the keyword {tok.text.upper()} is not a read")
        if tok.kind == "param":
            raise LaneReplyRefused("parameter", f"the parameter {tok.text!r}")
    return toks


def _check_names(toks: list[_Tok], allowed_entities: Sequence[str]) -> None:
    allowed = [[p.casefold() for p in e.split(".")] for e in allowed_entities]
    # Per paren depth: did a SELECT open this level, and is a FROM list running on it.
    selects = [False]
    in_from = [False]
    expect_table = False
    i = 0
    while i < len(toks):
        tok = toks[i]
        if tok.kind in ("ident", "qident") and not (i > 0 and toks[i - 1].text == "."):
            parts, nxt = _name_at(toks, i)
            if expect_table:
                expect_table = False
                if parts[0] != "er" or not _in_slice(parts, allowed):
                    raise LaneReplyRefused(
                        "outside_slice", f"{'.'.join(parts)!r} is not an er.* name of the slice"
                    )
            if parts[0] == "er" and len(parts) > 1:
                if not _in_slice(parts, allowed):
                    raise LaneReplyRefused(
                        "outside_slice", f"{'.'.join(parts)!r} is not in the model slice"
                    )
                i = nxt
                continue
            word = tok.upper
            if word == "SELECT":
                selects[-1] = True
                in_from[-1] = False
            elif word == "FROM" and selects[-1] and (i == 0 or toks[i - 1].upper != "DISTINCT"):
                in_from[-1] = True
                expect_table = True
            elif word in ("JOIN", "APPLY") and selects[-1]:
                expect_table = True
            elif word in _FROM_ENDERS:
                in_from[-1] = False
            i = nxt
            continue
        if expect_table and tok.text != "(":
            raise LaneReplyRefused("outside_slice", f"a table position holds {tok.text!r}")
        expect_table = False
        if tok.text == "(":
            selects.append(False)
            in_from.append(False)
        elif tok.text == ")" and len(selects) > 1:
            selects.pop()
            in_from.pop()
        elif tok.text == "," and in_from[-1]:
            expect_table = True
        i += 1
    if expect_table:
        raise LaneReplyRefused("outside_slice", "the statement ends where a table should be")


def _literal(tok: _Tok) -> str:
    """A string token's value: the prefix (`N`, `E`) and the quotes dropped, `''` undone."""
    text = tok.text[tok.text.index("'") :]
    return text[1:-1].replace("''", "'")


# Words that stand where an operand would and are literals themselves.
_LITERAL_WORDS = frozenset({"NULL", "TRUE", "FALSE"})
# Calls that wrap a literal with a type rather than an argument list.
_CASTS = frozenset({"CAST", "TRY_CAST"})


def _close_of(toks: Sequence[_Tok], open_at: int) -> int:
    """The index of the `)` closing the `(` at `open_at`, or -1."""
    depth = 0
    for j in range(open_at, len(toks)):
        if toks[j].text == "(":
            depth += 1
        elif toks[j].text == ")":
            depth -= 1
            if depth == 0:
                return j
    return -1


def _open_of(toks: Sequence[_Tok], close_at: int) -> int:
    """The index of the `(` the `)` at `close_at` closes, or -1."""
    depth = 0
    for j in range(close_at, -1, -1):
        if toks[j].text == ")":
            depth += 1
        elif toks[j].text == "(":
            depth -= 1
            if depth == 0:
                return j
    return -1


def _literal_from(toks: Sequence[_Tok], k: int) -> tuple[int | None, int] | None:
    """A literal OPERAND starting at `k`: `(the string's index or None, its last index)`, or
    None when what starts at `k` is not a literal. A literal is a string (typed, prefixed or
    plain), a number, `NULL`/`TRUE`/`FALSE`, and any of these wrapped on its own side — in
    parentheses, in a one-argument call (`LOWER('Brno')`), in `CAST(… AS type)`, or followed by
    `::type`."""
    if k < 0 or k >= len(toks):
        return None
    tok = toks[k]
    found: tuple[int | None, int] | None = None
    if tok.kind == "string":
        found = (k, k)
    elif tok.kind == "number" or tok.upper in _LITERAL_WORDS:
        found = (None, k)
    elif tok.text in ("-", "+") and k + 1 < len(toks) and toks[k + 1].kind == "number":
        found = (None, k + 1)
    elif tok.upper in _TYPED_LITERALS and k + 1 < len(toks) and toks[k + 1].kind == "string":
        found = (k + 1, k + 1)
    elif tok.text == "(":
        inner = _literal_from(toks, k + 1)
        if inner is not None and inner[1] + 1 < len(toks) and toks[inner[1] + 1].text == ")":
            found = (inner[0], inner[1] + 1)
    elif tok.kind == "ident" and k + 1 < len(toks) and toks[k + 1].text == "(":
        inner = _literal_from(toks, k + 2)
        close = _close_of(toks, k + 1)
        if inner is not None and close > inner[1]:
            after = toks[inner[1] + 1]
            if after.text == ")" or (tok.upper in _CASTS and after.upper == "AS"):
                found = (inner[0], close)
    if found is None:
        return None
    end = found[1]
    while end + 2 < len(toks) and toks[end + 1].text == "::" and toks[end + 2].kind == "ident":
        end += 2
    return found[0], end


def _operand_start(toks: Sequence[_Tok], end: int) -> int:
    """Where the operand ending at `end` starts: back over a `::type`, a call or a
    parenthesis, a typed literal's keyword, a dotted name."""
    k = end
    while k >= 2 and toks[k].kind == "ident" and toks[k - 1].text == "::":
        k -= 2
    if toks[k].text == ")":
        k = max(_open_of(toks, k), 0)
        if k > 0 and toks[k - 1].kind in ("ident", "qident"):
            k -= 1
    elif toks[k].kind == "string" and k > 0 and toks[k - 1].upper in _TYPED_LITERALS:
        return k - 1
    elif toks[k].kind == "number" and k > 0 and toks[k - 1].text in ("-", "+"):
        return k - 1
    while k >= 2 and toks[k - 1].text == "." and toks[k - 2].kind in ("ident", "qident"):
        k -= 2
    return k


def _literal_ending_at(toks: Sequence[_Tok], end: int) -> tuple[int | None, int] | None:
    """A literal operand that ENDS at `end` (see `_literal_from`), or None."""
    if end < 0:
        return None
    found = _literal_from(toks, _operand_start(toks, end))
    return found if found is not None and found[1] == end else None


def _comparison_literals(toks: Sequence[_Tok]) -> list[int]:
    """The literal side of each comparison whose other side is not a literal."""
    out: list[int] = []
    for p, op in enumerate(toks):
        pattern = op.upper in _PATTERN
        if not (op.text in _EQUALITY or pattern) or p == 0:
            continue
        left_end = p - 2 if pattern and toks[p - 1].upper == "NOT" else p - 1
        left = _literal_ending_at(toks, left_end)
        right = _literal_from(toks, p + 1)
        if left is not None and right is None and left[0] is not None:
            out.append(left[0])
        elif right is not None and left is None and right[0] is not None:
            out.append(right[0])
    return out


# Keywords that open a condition clause (position a).
_CONDITIONS = frozenset({"WHERE", "HAVING", "ON", "QUALIFY"})
# Keywords that end a condition clause or a select list on their own level.
_CLAUSE_ENDERS = frozenset(
    {
        "WHERE",
        "GROUP",
        "ORDER",
        "HAVING",
        "WINDOW",
        "QUALIFY",
        "LIMIT",
        "OFFSET",
        "FETCH",
        "UNION",
        "EXCEPT",
        "INTERSECT",
        "JOIN",
        "INNER",
        "LEFT",
        "RIGHT",
        "FULL",
        "CROSS",
        "NATURAL",
    }
)
# Date functions: every literal argument is a unit or a format (exemption iii), and a period
# label compared to one of their calls is a period, not a member (exemption iv).
_DATE_FUNCTIONS = frozenset(
    {
        "DATE_TRUNC",
        "DATE_PART",
        "DATEADD",
        "DATEDIFF",
        "DATENAME",
        "DATEPART",
        "TO_CHAR",
        "TO_DATE",
        "TO_TIMESTAMP",
        "FORMAT",
        "DATE_FORMAT",
        "STRFTIME",
    }
)
_TEMPORAL_TYPES = frozenset(
    {"DATE", "TIME", "TIMESTAMP", "TIMESTAMPTZ", "INTERVAL", "DATETIME", "DATETIME2"}
)
_PERIOD_LABEL = re.compile(r"^[0-9]+([-/. :][0-9A-Za-z]+)*$")
# Operators a period label may be compared with (exemption iv).
_ORDERING = frozenset({"=", "<>", "!=", "<", ">", "<=", ">=", "LIKE", "ILIKE"})


def _depths(toks: Sequence[_Tok]) -> list[int]:
    """Each token's parenthesis depth; a `(` and its `)` sit on the outer level."""
    out: list[int] = []
    depth = 0
    for tok in toks:
        if tok.text == ")":
            depth -= 1
        out.append(depth)
        if tok.text == "(":
            depth += 1
    return out


def _span_end(toks: Sequence[_Tok], start: int, enders: frozenset[str]) -> int:
    """The index just past the run that starts at `start` and ends at an `enders` keyword on
    its own level, at the `)` closing that level, or at the end."""
    depth = 0
    j = start
    while j < len(toks):
        tok = toks[j]
        if tok.text == "(":
            depth += 1
        elif tok.text == ")":
            depth -= 1
            if depth < 0:
                return j
        elif depth == 0 and tok.upper in enders and toks[j - 1].text != ".":
            return j
        j += 1
    return j


def _condition_positions(toks: Sequence[_Tok]) -> set[int]:
    """Position (a): inside a WHERE / HAVING / ON / QUALIFY clause, at any depth."""
    out: set[int] = set()
    for i, tok in enumerate(toks):
        if tok.upper in _CONDITIONS and (i == 0 or toks[i - 1].text != "."):
            out.update(range(i + 1, _span_end(toks, i + 1, _CLAUSE_ENDERS)))
    return out


def _case_condition_positions(toks: Sequence[_Tok]) -> set[int]:
    """Position (b): between `WHEN` and its `THEN`, in any CASE."""
    out: set[int] = set()
    for i, tok in enumerate(toks):
        if tok.upper != "WHEN":
            continue
        depth = cases = 0
        j = i + 1
        while j < len(toks):
            t = toks[j]
            if t.text == "(":
                depth += 1
            elif t.text == ")":
                depth -= 1
                if depth < 0:
                    break
            elif t.upper == "CASE":
                cases += 1
            elif t.upper == "END":
                cases -= 1
            elif t.upper == "THEN" and depth == 0 and cases == 0:
                break
            j += 1
        out.update(range(i + 1, j))
    return out


def _inner_select_positions(toks: Sequence[_Tok], depths: Sequence[int]) -> set[int]:
    """Position (c): the select list of every SELECT that is not outermost, and VALUES lists."""
    out: set[int] = set()
    select_enders = _CLAUSE_ENDERS | {"FROM"}
    for i, tok in enumerate(toks):
        if tok.upper == "SELECT" and depths[i] > 0:
            j = i + 1
            while True:
                j = _span_end(toks, j, select_enders)
                # `IS DISTINCT FROM` is no FROM clause: keep going past it.
                if j < len(toks) and toks[j].upper == "FROM" and toks[j - 1].upper == "DISTINCT":
                    j += 1
                    continue
                break
            out.update(range(i + 1, j))
        elif tok.upper == "VALUES":
            j = i + 1
            while j < len(toks) and toks[j].text == "(":
                close = _close_of(toks, j)
                if close < 0:
                    close = len(toks) - 1
                out.update(range(j, close + 1))
                j = close + 1
                if j < len(toks) and toks[j].text == ",":
                    j += 1
    return out


def _member_literals(toks: Sequence[_Tok]) -> list[int]:
    """The string literals that are TRACED: by position (a–c), or as a comparison operand (d)."""
    depths = _depths(toks)
    traced = (
        _condition_positions(toks)
        | _case_condition_positions(toks)
        | _inner_select_positions(toks, depths)
    )
    found = {i for i in traced if toks[i].kind == "string"}
    found.update(_comparison_literals(toks))
    return sorted(found)


def _enclosing_open(toks: Sequence[_Tok], i: int) -> int:
    """The `(` whose parentheses directly hold token `i`, or -1."""
    depth = 0
    for j in range(i - 1, -1, -1):
        if toks[j].text == ")":
            depth += 1
        elif toks[j].text == "(":
            if depth == 0:
                return j
            depth -= 1
    return -1


def _is_date_call_ending_at(toks: Sequence[_Tok], end: int) -> bool:
    if end < 0 or toks[end].text != ")":
        return False
    start = _operand_start(toks, end)
    return toks[start].upper in _DATE_FUNCTIONS and toks[start + 1].text == "("


def _compared_to_date_call(toks: Sequence[_Tok], i: int) -> bool:
    """Is literal `i` compared — by an ordering operator, `BETWEEN` or `IN` — to a call of a
    date function?"""
    prev = toks[i - 1] if i > 0 else None
    # `F(…) op 'x'`
    if prev is not None and (prev.text in _ORDERING or prev.upper in _ORDERING):
        left = i - 2
        if left >= 0 and toks[left].upper == "NOT":
            left -= 1
        if _is_date_call_ending_at(toks, left):
            return True
    # `'x' op F(…)`
    if i + 2 < len(toks) and (toks[i + 1].text in _ORDERING or toks[i + 1].upper in _ORDERING):
        k = i + 2
        if toks[k].upper in _DATE_FUNCTIONS and k + 1 < len(toks) and toks[k + 1].text == "(":
            return True
    # `F(…) [NOT] BETWEEN 'a' AND 'b'`
    between = -1
    if prev is not None and prev.upper == "BETWEEN":
        between = i - 1
    elif prev is not None and prev.upper == "AND" and i >= 3 and toks[i - 3].upper == "BETWEEN":
        between = i - 3
    if between >= 0:
        left = between - 1
        if left >= 0 and toks[left].upper == "NOT":
            left -= 1
        if _is_date_call_ending_at(toks, left):
            return True
    # `F(…) [NOT] IN ('a', 'b')`
    open_at = _enclosing_open(toks, i)
    if open_at > 0 and toks[open_at - 1].upper == "IN":
        left = open_at - 2
        if left >= 0 and toks[left].upper == "NOT":
            left -= 1
        if _is_date_call_ending_at(toks, left):
            return True
    return False


def _exempt(toks: Sequence[_Tok], i: int, value: str) -> bool:
    """The exemptions by NAME (i–iv); (v) is applied to the needle by the caller."""
    prev = toks[i - 1] if i > 0 else None
    # (i) a typed temporal literal, or one cast to a temporal type
    if prev is not None and prev.upper in _TYPED_LITERALS:
        return True
    if i + 2 < len(toks) and toks[i + 1].text == "::" and toks[i + 2].upper in _TEMPORAL_TYPES:
        return True
    if (
        prev is not None
        and prev.text == "("
        and i >= 2
        and toks[i - 2].upper in _CASTS
        and i + 2 < len(toks)
        and toks[i + 1].upper == "AS"
        and toks[i + 2].upper in _TEMPORAL_TYPES
    ):
        return True
    # (ii) an ISO date
    if _TEMPORAL.match(value):
        return True
    # (iii) a direct argument of a date function
    open_at = _enclosing_open(toks, i)
    if open_at > 0 and toks[open_at - 1].upper in _DATE_FUNCTIONS:
        return True
    # (iv) a period label compared to a date function's call
    return bool(_PERIOD_LABEL.match(value)) and _compared_to_date_call(toks, i)


def _whole_words(needle: str) -> re.Pattern[str]:
    """`needle` as a whole word or run of words — `\\w` is Unicode, so `tr` is no word of
    `tržby`, nor `sales` of `store_sales`."""
    return re.compile(rf"(?<!\w){re.escape(needle)}(?!\w)")


def _check_literals(toks: list[_Tok], sources: Iterable[str]) -> None:
    haystack = [s.casefold() for s in sources if s]
    for i in _member_literals(toks):
        value = _literal(toks[i]).strip()
        if _exempt(toks, i, value):
            continue
        needle = value.strip("%").strip().casefold()
        if not needle:
            continue
        word = _whole_words(needle)
        if not any(word.search(s) for s in haystack):
            raise LaneReplyRefused(
                "literal_unsourced",
                f"the literal {value!r} is no whole word of a binding, a value or the question",
            )


def literal_sources(state: ResolutionState) -> list[str]:
    """Where a member literal may come from (OL-C15): the question's own words, the
    mentions' span texts and lemmas, a member ref's key after `#` (of a binding, a grounding
    or an attribution), the values' span, VERBATIM and normalised texts. The lemmas count as
    the question's words — a member named in an inflected form is still named. A ref path
    itself is no source: a member is never spelled `er.entity.store_sales`."""
    out = [state.question]

    def _ref(ref: str) -> None:
        if "#" in ref:
            out.append(ref.split("#", 1)[1])

    for m in state.mentions:
        out += [m.span.text, m.lemma]
        for b in m.bindings:
            _ref(b.ref)
    for v in state.values:
        out.append(v.span.text)
        if v.verbatim_text:
            out.append(v.verbatim_text)
        if v.grounding is not None:
            out.append(v.grounding.normalized_value)
            _ref(v.grounding.ref)
        for a in v.attributions:
            _ref(a.attribute_ref)
            if a.binding is not None:
                _ref(a.binding.ref)
    return [s for s in out if s]


def _parse_reply(text: str) -> tuple[str, list[tuple[int, int]], str]:
    try:
        obj = json.loads(text)
    except ValueError as exc:
        raise LaneReplyRefused("invalid_object", f"the reply is not JSON: {exc}") from exc
    if not isinstance(obj, dict):
        raise LaneReplyRefused("invalid_object", "the reply is not a JSON object")
    sql = obj.get("sql")
    if not isinstance(sql, str) or not sql.strip():
        raise LaneReplyRefused("invalid_object", "`sql` is missing or not a string")
    note = obj.get("note", "")
    if not isinstance(note, str):
        raise LaneReplyRefused("invalid_object", "`note` is not a string")
    raw_spans = obj.get("handled_spans", [])
    if not isinstance(raw_spans, list):
        raise LaneReplyRefused("invalid_object", "`handled_spans` is not a list")
    spans: list[tuple[int, int]] = []
    for item in raw_spans:
        if (
            not isinstance(item, list)
            or len(item) != 2
            or not all(isinstance(x, int) and not isinstance(x, bool) for x in item)
        ):
            raise LaneReplyRefused("invalid_object", f"`handled_spans` holds {item!r}")
        span = (item[0], item[1])
        if span not in spans:
            spans.append(span)
    return sql.strip(), spans, note


def check_reply(
    text: str,
    *,
    allowed_entities: Sequence[str],
    carried: Sequence[Span],
    literal_sources: Iterable[str],
) -> LaneQuery:
    """The LLM's reply → a `LaneQuery`, or `LaneReplyRefused` naming the broken rule.

    Pure: the reply text, the slice's entity names, the carried gaps' spans and the
    literal sources in; nothing read from anywhere else."""
    sql, spans, note = _parse_reply(text)
    carried_at = {(s.start, s.end) for s in carried}
    for span in spans:
        if span not in carried_at:
            raise LaneReplyRefused(
                "handled_span_not_carried", f"handled span {list(span)} is not a carried gap"
            )
    toks = _check_statement(_tokens(sql))
    _check_names(toks, allowed_entities)
    _check_literals(toks, literal_sources)
    return LaneQuery(sql=sql, handled_spans=tuple(spans), note=note)
