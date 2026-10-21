# SPDX-License-Identifier: Apache-2.0
"""Identifier quoting for the door's SQL (BI C-15).

The door reads identifiers in `"double quotes"` (its parser is Calcite's, ANSI-quoted) and reserves Calcite's words, a
list that names columns models do have (`count`, `value`, `year`). So every identifier is quoted, aliases included,
and a dotted schema is two names, each quoted: `"er"."entity"."warehouse"`, never `"er.entity"."warehouse"`.
"""

from __future__ import annotations

from sqlalchemy.sql import compiler


class TatrmanIdentifierPreparer(compiler.IdentifierPreparer):
    def __init__(self, dialect, **kw):
        super().__init__(dialect, initial_quote='"', final_quote='"', **kw)

    def _requires_quotes(self, value: str) -> bool:
        return True

    def _requires_quotes_illegal_chars(self, value: str) -> bool:
        return True

    def quote_schema(self, schema: str, force=None) -> str:
        return ".".join(self.quote(part) for part in schema.split("."))

    def format_schema(self, name: str) -> str:
        return self.quote_schema(name)
