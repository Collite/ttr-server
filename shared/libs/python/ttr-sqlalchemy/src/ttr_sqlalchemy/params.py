# SPDX-License-Identifier: Apache-2.0
"""`:name` parameters, bound as SQL literals before the statement leaves the driver.

The edge takes no bound parameters (BI C-1: no `DoPut`, nothing prepared with values), so a `:name` the caller binds
is written into the statement as a literal: strings quoted with `'` doubled, dates and times as typed literals
(`DATE '2026-10-01'`), `None` as `NULL`. Only `:name` outside string literals, quoted identifiers and comments is a
parameter; a value without a safe literal (bytes, a float that is not finite, a datetime with a time zone) is refused
rather than guessed.
"""

from __future__ import annotations

import datetime as dt
import decimal
import math
import re
from collections.abc import Mapping

from .errors import NotSupportedError, ProgrammingError

_NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def bind(sql: str, parameters: object) -> str:
    """[sql] with every `:name` replaced by the literal of `parameters[name]`; [sql] itself when there is nothing to
    bind."""
    if parameters is None:
        return sql
    if not isinstance(parameters, Mapping):
        if isinstance(parameters, (list, tuple)) and not parameters:
            return sql
        raise ProgrammingError(
            "parameter_style", "Parameters are bound by name (:name) from a mapping, not by position."
        )
    if not parameters:
        return sql
    out: list[str] = []
    missing: list[str] = []
    i = 0
    n = len(sql)
    while i < n:
        c = sql[i]
        if c == "'" or c == '"':
            j = _closing(sql, i, c)
            out.append(sql[i:j])
            i = j
        elif sql.startswith("--", i):
            j = sql.find("\n", i)
            j = n if j < 0 else j
            out.append(sql[i:j])
            i = j
        elif sql.startswith("/*", i):
            j = sql.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append(sql[i:j])
            i = j
        elif c == ":" and (i + 1 < n and sql[i + 1] == ":"):
            out.append("::")
            i += 2
        elif c == ":" and (m := _NAME.match(sql, i + 1)) is not None and not _after_word(sql, i):
            name = m.group(0)
            if name in parameters:
                out.append(literal(parameters[name]))
            elif name not in missing:
                missing.append(name)
            i = m.end()
        else:
            out.append(c)
            i += 1
    if missing:
        names = ", ".join(f"'{m}'" for m in missing)
        if len(missing) == 1:
            message = f"Parameter {names} has no value; bind it, or send an explicit NULL (None)."
        else:
            message = f"Parameters {names} have no value; bind them, or send an explicit NULL (None)."
        raise ProgrammingError("parameter_unbound", message)
    return "".join(out)


def literal(value: object) -> str:
    """The SQL literal for one Python value."""
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        if not math.isfinite(value):
            raise NotSupportedError("parameter_type", f"{value!r} has no SQL literal.")
        return repr(value)
    if isinstance(value, decimal.Decimal):
        if not value.is_finite():
            raise NotSupportedError("parameter_type", f"{value!r} has no SQL literal.")
        return format(value, "f")
    if isinstance(value, str):
        if "\x00" in value:
            raise NotSupportedError("parameter_type", "A string with a NUL character has no SQL literal.")
        return "'" + value.replace("'", "''") + "'"
    if isinstance(value, dt.datetime):
        if value.tzinfo is not None:
            raise NotSupportedError(
                "parameter_type", "A datetime with a time zone has no SQL literal here; send it naive (in UTC)."
            )
        return f"TIMESTAMP '{value.isoformat(sep=' ')}'"
    if isinstance(value, dt.date):
        return f"DATE '{value.isoformat()}'"
    if isinstance(value, dt.time):
        if value.tzinfo is not None:
            raise NotSupportedError("parameter_type", "A time with a time zone has no SQL literal here.")
        return f"TIME '{value.isoformat()}'"
    raise NotSupportedError("parameter_type", f"A {type(value).__name__} has no SQL literal.")


def _closing(sql: str, start: int, quote: str) -> int:
    """The index just past the quote closing the one at [start] (a doubled quote is an escaped one)."""
    j = start + 1
    while j < len(sql):
        if sql[j] == quote:
            if j + 1 < len(sql) and sql[j + 1] == quote:
                j += 2
                continue
            return j + 1
        j += 1
    return len(sql)


def _after_word(sql: str, i: int) -> bool:
    """`a:b` is not a parameter: the colon follows a word character."""
    return i > 0 and (sql[i - 1].isalnum() or sql[i - 1] == "_")
