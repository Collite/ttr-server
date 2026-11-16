# SPDX-License-Identifier: Apache-2.0
"""The checks before the door (OL-C15, W1c·S2·T5) — one PURE function.

The LLM's reply must be `{"sql": "...", "handled_spans": [[start, end], …], "note": "..."}`,
and before the door is called the code checks the rules the prompt states:

* **one `SELECT`** — a single statement (a trailing `;` is tolerated), starting with
  `SELECT`; no data or schema keyword (`INSERT`, `DROP`, `INTO`, …) anywhere;
* **over `er.*` names from the slice only** — every dotted name starting with `er` names an
  entity of the slice (or one of its attributes), and every table position (after `FROM`,
  `JOIN`, or a comma in a `FROM` list) holds such a name or a sub-query;
* **no parameters** — no `?`, `$1`, `:name` or `@name`;
* **a literal that restricts a member comes from a binding, a value or the question's own
  words** — every string literal that is not a date or a typed temporal literal must be found
  in what the lattice carries (see `literal_sources`);
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
        "REPLACE",
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
# A string literal right after one of these is temporal, not a member.
_TYPED_LITERALS = frozenset({"DATE", "TIME", "TIMESTAMP", "INTERVAL"})
_TEMPORAL = re.compile(r"^\d{4}-\d{2}(-\d{2})?([ T]\d{2}:\d{2}(:\d{2}(\.\d+)?)?)?$")

_TOKEN = re.compile(
    r"""
     (?P<ws>\s+)
    |(?P<comment>--[^\n]*|/\*.*?\*/)
    |(?P<string>'(?:[^']|'')*')
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


def _check_statement(toks: list[_Tok]) -> list[_Tok]:
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
                if parts[0] != "er":
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
            elif word == "JOIN" and selects[-1]:
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
    return tok.text[1:-1].replace("''", "'")


def _check_literals(toks: list[_Tok], sources: Iterable[str]) -> None:
    haystack = [s.casefold() for s in sources if s]
    for i, tok in enumerate(toks):
        if tok.kind != "string":
            continue
        if i > 0 and toks[i - 1].upper in _TYPED_LITERALS:
            continue
        value = _literal(tok).strip()
        if _TEMPORAL.match(value):
            continue
        needle = value.strip("%").casefold()
        if not needle:
            continue
        if not any(needle in s for s in haystack):
            raise LaneReplyRefused(
                "literal_unsourced",
                f"the literal {value!r} comes from no binding, value or word of the question",
            )


def literal_sources(state: ResolutionState) -> list[str]:
    """Where a member literal may come from (OL-C15): the question's own words, the bindings
    (their refs and a member ref's key after `#`), the values (span, VERBATIM text,
    normalised grounding, attributions). The mentions' lemmas count as the question's words —
    a member named in an inflected form is still named."""
    out = [state.question]

    def _ref(ref: str) -> None:
        if ref:
            out.append(ref)
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
