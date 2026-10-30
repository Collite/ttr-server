# SPDX-License-Identifier: Apache-2.0
"""PEP 249 exceptions carrying the edge's refusal code and its one sentence (BI C-11).

The edge refuses as `"<code>: <one sentence>"` (`translator_rejected: Object 'warehouse' not found within 'er' — did
you mean: warehouse, warehouse_id?`). Every exception here keeps both halves, `code` and `message`, and prints them
the way the edge said them. Which PEP 249 class a code becomes follows what the code means: a statement the door will
not run is a `ProgrammingError`, an answer withheld on access a plain `DatabaseError`, a dependency that did not answer
(or a login that failed) an `OperationalError`, the door's own failure an `InternalError`.
"""

from __future__ import annotations

import builtins


class Error(Exception):
    """The base of every error this driver raises."""

    def __init__(self, code: str = "", message: str = ""):
        self.code = code
        self.message = message
        super().__init__(f"{code}: {message}" if code else message)


class Warning(builtins.Warning):
    """PEP 249's `Warning` — not an `Error`; this driver raises none (a capped answer is `Cursor.notices`)."""


class InterfaceError(Error):
    pass


class DatabaseError(Error):
    pass


class DataError(DatabaseError):
    pass


class OperationalError(DatabaseError):
    pass


class IntegrityError(DatabaseError):
    pass


class InternalError(DatabaseError):
    pass


class ProgrammingError(DatabaseError):
    pass


class NotSupportedError(DatabaseError):
    pass


#: Refusals on access: the statement is sound, the caller may not read (all of) its answer.
ACCESS_CODES = frozenset({"access_denied", "column_denied", "permission_denied", "world_mismatch"})

#: The call never reached a door that would run it: no or a bad credential, a world not served.
LOGIN_CODES = frozenset(
    {
        "missing_credentials",
        "invalid_token",
        "password_refused",
        "unknown_world",
        "world_unspecified",
        "unauthenticated",
    }
)

#: The door's own failures.
INTERNAL_CODES = frozenset({"query_failed", "cache_corrupted", "translator_failed", "internal"})


def error_class(code: str) -> type[DatabaseError]:
    if code in ACCESS_CODES:
        return DatabaseError
    if code in LOGIN_CODES or code.endswith("_unavailable") or code in ("unavailable", "timeout", "cancelled"):
        return OperationalError
    if code in INTERNAL_CODES:
        return InternalError
    if code == "unsupported":
        return NotSupportedError
    return ProgrammingError
