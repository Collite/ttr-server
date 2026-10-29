# SPDX-License-Identifier: Apache-2.0
"""Closed, typed DX predicates. A field predicate is existential over matching annotations."""

from __future__ import annotations

import json
import re
from typing import Any

from docproc.sdk.models import Annotation


class PredicateError(ValueError):
    pass


_TOKEN = re.compile(
    r"""\s*(?:(?P<string>"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|(?P<number>-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)|(?P<word>[A-Za-z_][A-Za-z0-9_-]*)|(?P<op>!=|>=|<=|[=><().,\[\]]))"""
)
_MISSING = object()


def matches_type(annotation: Annotation, selector: str) -> bool:
    if annotation.type == selector:
        return True
    m = re.fullmatch(r"(.+)\[([^]]+)\]", selector)
    return bool(m and annotation.type == m[1] and annotation.payload().get("kind") == m[2])


def path_value(value: Any, path: str) -> Any:
    for key in path.split("."):
        if not isinstance(value, dict) or key not in value:
            return _MISSING
        value = value[key]
    return value


class Predicate:
    def __init__(self, expression: str) -> None:
        self.tokens: list[tuple[str, str]] = []
        pos = 0
        while pos < len(expression.rstrip()):
            m = _TOKEN.match(expression, pos)
            if m is None:
                raise PredicateError(f"predicate/syntax at {pos}")
            assert m.lastgroup
            self.tokens.append((m.lastgroup, m[m.lastgroup]))
            pos = m.end()
        self.pos = 0
        self.tree = self._or() if self.tokens else ("constant", True)
        if self.pos != len(self.tokens):
            raise PredicateError("predicate/syntax: trailing input")

    def _peek(self, token: str) -> bool:
        return self.pos < len(self.tokens) and self.tokens[self.pos][1] == token

    def _take(self, token: str) -> bool:
        if self._peek(token):
            self.pos += 1
            return True
        return False

    def _expect(self, token: str) -> None:
        if not self._take(token):
            raise PredicateError(f"predicate/syntax: expected {token}")

    def _word(self) -> str:
        if self.pos >= len(self.tokens) or self.tokens[self.pos][0] != "word":
            raise PredicateError("predicate/syntax: expected identifier")
        word = self.tokens[self.pos][1]
        self.pos += 1
        return word

    def _name(self) -> str:
        name = self._word()
        while self._take("."):
            name += "." + self._word()
        if self._take("["):
            name += "[" + self._word()
            self._expect("]")
            name += "]"
            while self._take("."):
                name += "." + self._word()
        if not name[0].isupper():
            raise PredicateError("predicate/syntax: type must start uppercase")
        return name

    def _literal(self) -> Any:
        if self.pos >= len(self.tokens):
            raise PredicateError("predicate/syntax: expected literal")
        kind, value = self.tokens[self.pos]
        self.pos += 1
        if kind == "string":
            if value.startswith('"'):
                return json.loads(value)
            # Single-quoted literals support escaped quote/backslash, not Python escapes/code.
            inner = value[1:-1]
            return re.sub(r"\\(['\\])", r"\1", inner)
        if kind == "number":
            return json.loads(value)
        if kind == "word" and value not in ("NaN", "Infinity", "and", "or", "not", "exists", "in"):
            return {"true": True, "false": False, "null": None}.get(value, value)
        raise PredicateError("predicate/syntax: expected literal")

    def _or(self) -> tuple[Any, ...]:
        left = self._and()
        while self._take("or"):
            left = ("or", left, self._and())
        return left

    def _and(self) -> tuple[Any, ...]:
        left = self._unary()
        while self._take("and"):
            left = ("and", left, self._unary())
        return left

    def _unary(self) -> tuple[Any, ...]:
        if self._take("not"):
            return ("not", self._unary())
        if self._take("("):
            value = self._or()
            self._expect(")")
            return value
        if self._take("exists"):
            self._expect("(")
            name = self._name()
            self._expect(")")
            return ("exists", name)
        name = self._name()
        negate = self._take("not")
        if self._take("in"):
            self._expect("[")
            values = []
            if not self._peek("]"):
                values.append(self._literal())
                while self._take(","):
                    values.append(self._literal())
            self._expect("]")
            return ("compare", name, "not in" if negate else "in", values)
        if negate or self.pos >= len(self.tokens) or self.tokens[self.pos][1] not in ("=", "!=", "<", ">", "<=", ">="):
            raise PredicateError("predicate/syntax: expected operator")
        op = self.tokens[self.pos][1]
        self.pos += 1
        return ("compare", name, op, self._literal())

    def evaluate(self, annotations: list[Annotation]) -> bool:
        def equal(a: Any, b: Any) -> bool:
            # JSON booleans are not numbers; numeric int/float equality is allowed.
            return (type(a) is type(b) or type(a) in (int, float) and type(b) in (int, float)) and a == b

        def compare(a: Any, op: str, b: Any) -> bool:
            if a is _MISSING:
                return False
            if op in ("in", "not in"):
                found = any(equal(a, v) for v in b)
                return not found if op == "not in" else found
            if op in ("=", "!="):
                return equal(a, b) if op == "=" else not equal(a, b)
            if not (type(a) in (int, float) and type(b) in (int, float) or type(a) is str and type(b) is str):
                return False
            return bool({"<": lambda: a < b, ">": lambda: a > b, "<=": lambda: a <= b, ">=": lambda: a >= b}[op]())

        def run(node: tuple[Any, ...]) -> bool:
            tag = node[0]
            if tag == "constant":
                return bool(node[1])
            if tag == "not":
                return not run(node[1])
            if tag == "and":
                return run(node[1]) and run(node[2])
            if tag == "or":
                return run(node[1]) or run(node[2])
            if tag == "exists":
                return any(matches_type(a, node[1]) for a in annotations)
            name, op, value = node[1:]
            for a in annotations:
                # Longest matching type prefix preserves Import.review and exact Statement[review].
                candidates = [a.type]
                kind = a.payload().get("kind")
                if kind:
                    candidates.insert(0, f"{a.type}[{kind}]")
                for selector in candidates:
                    if name == selector:
                        field = "kind"
                    elif name.startswith(selector + "."):
                        field = name[len(selector) + 1 :]
                    else:
                        continue
                    if compare(path_value(a.payload(), field), op, value):
                        return True
            return False

        return run(self.tree)
