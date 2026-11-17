# SPDX-License-Identifier: Apache-2.0
"""Diagnostics — the one shape everything reports failures in.

The CLI, the pack loader, the service's boot-time load and ``ReloadPacks`` all
emit these, and the proto ``PackDiagnostic`` (contracts §2.3) has exactly these
five fields. Keeping the dataclass field-for-field identical to the wire message
is deliberate: a pack author reading a CLI error and an operator reading a
``ReloadPacks`` response are looking at the same text, and neither has to
translate.

Codes are contracts §8:

===================  ===========================================================
``NLS-PACK-001``     the pack does not parse — schema violation, or a structural
                     rule the schema cannot express (``repeat.min > max``)
``NLS-PACK-002``     the pack parses but cannot compile — a type not in the
                     phase's ``input:``, an RHS reference to an unbound name, a
                     duplicated ``bind:``
``NLS-PACK-003``     a list file is invalid
``NLS-PACK-004``     a pipeline references a pack, phase or list that is not there
``NLS-PACK-005``     CLI ``--model`` only: a query id or parameter name the model
                     does not declare
``NLS-PACK-006``     a pipeline names a stage that is not installed in this image
                     (OL-C8)
``NLS-PACK-010``     a reload was refused; the previous snapshot still serves
===================  ===========================================================

Why 001 covers the model-validator failures too: from a pack author's side there
is no interesting difference between "the schema rejected this" and "the schema
could not express it, so the loader did" — both mean the file is malformed and
neither can be reached by a rule at runtime. 002 is genuinely different: the
file is well-formed and every error in it is about how the parts refer to each
other.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Literal

from pydantic import ValidationError

SEVERITY_INFO = "INFO"
SEVERITY_ERROR = "ERROR"

NLS_PACK_001 = "NLS-PACK-001"  # schema / parse
NLS_PACK_002 = "NLS-PACK-002"  # compile-time cross-checks
NLS_PACK_003 = "NLS-PACK-003"  # list file invalid
NLS_PACK_004 = "NLS-PACK-004"  # pipeline reference unresolved
NLS_PACK_005 = "NLS-PACK-005"  # model cross-check (CLI lane)
NLS_PACK_006 = "NLS-PACK-006"  # pipeline names a stage that is not installed
NLS_PACK_010 = "NLS-PACK-010"  # reload refused


@dataclass(frozen=True)
class Diagnostic:
    """One problem, in the shape the wire uses.

    ``message`` leads with the JSON path of the offending node (``$.phases[0].
    control``) because "which of the 300 lines" is the first question every
    reader has. The path is not a separate field: the proto has five fields and
    a sixth here would have to be dropped at the boundary, leaving the CLI and
    the service saying different things about the same pack.
    """

    severity: Literal["INFO", "ERROR"]
    code: str
    message: str
    source: str = ""
    pack: str = ""

    def __str__(self) -> str:
        where = self.pack or self.source or "?"
        return f"[{self.severity}] {self.code} {where}: {self.message}"


def error(code: str, message: str, *, source: str = "", pack: str = "") -> Diagnostic:
    return Diagnostic(
        severity=SEVERITY_ERROR, code=code, message=message, source=source, pack=pack
    )


def info(code: str, message: str, *, source: str = "", pack: str = "") -> Diagnostic:
    return Diagnostic(
        severity=SEVERITY_INFO, code=code, message=message, source=source, pack=pack
    )


class PackError(Exception):
    """A pack could not be loaded or compiled.

    Carries every diagnostic found, not just the first. A pack author fixing six
    typos should need one round trip, not six.
    """

    def __init__(self, diagnostics: list[Diagnostic]):
        self.diagnostics: list[Diagnostic] = list(diagnostics)
        summary = "; ".join(d.message for d in self.diagnostics)
        super().__init__(summary or "invalid pack")

    @property
    def codes(self) -> list[str]:
        return [d.code for d in self.diagnostics]


@dataclass
class DiagnosticCollector:
    """Accumulates diagnostics and raises once, at the end."""

    source: str = ""
    pack: str = ""
    items: list[Diagnostic] = field(default_factory=list)

    def error(self, code: str, message: str) -> None:
        self.items.append(error(code, message, source=self.source, pack=self.pack))

    def raise_if_any(self) -> None:
        if self.items:
            raise PackError(self.items)


# ── pydantic -> Diagnostic ───────────────────────────────────────────────────
#
# Packs (NLS-PACK-001) and gazetteer lists (NLS-PACK-003) are both pydantic
# models behind a YAML file, so both need the same translation from pydantic's
# error shape to an author-readable one. It lives here, next to the shape it
# produces, because two copies would drift and the whole point of `Diagnostic` is
# that every surface says the same thing the same way.


def pydantic_path(loc: tuple[Any, ...]) -> str:
    """Render a pydantic error location the way the schema errors render theirs.

    Pydantic splices its own machinery into `loc` — validator wrappers like
    ``function-after[_min_le_max(), RepeatModel]`` and union-member tags. Those
    describe *our* implementation, not the author's document, and a pack author
    reading `$.phases[0]…repeat.function-after[…]` learns nothing from the tail.
    Drop those segments and use the same `$.a[0].b` shape jsonschema produces, so
    both halves of validation point at a file the same way.
    """
    path = "$"
    for part in loc:
        if isinstance(part, int):
            path += f"[{part}]"
        elif "[" in part:
            continue  # pydantic internals
        else:
            path += f".{part}"
    return path


#: Trailing `loc` segments pydantic adds to name which arm of a union failed.
_UNION_TAGS = frozenset(
    {"str", "int", "float", "bool", "none", "NoneType", "function-after"}
)


def _strip_union_tag(loc: tuple[Any, ...]) -> tuple[Any, ...]:
    if loc and isinstance(loc[-1], str) and loc[-1] in _UNION_TAGS:
        return loc[:-1]
    return loc


def diagnostics_from_pydantic(
    exc: ValidationError, *, code: str, source: str = "", pack: str = ""
) -> list[Diagnostic]:
    """Turn pydantic's errors into diagnostics an author can act on.

    Two pieces of noise get removed. A union field reports once per arm, so
    ``repeat: {min: 5, max: 2}`` yields both "min is greater than max" and
    "Input should be a valid string" — the second is pydantic explaining that
    the value is not the `*`/`+`/`?` sugar, which the author did not attempt.
    Where a location has a message from one of our own validators, that message
    is the answer and the generic type complaints beside it are dropped.
    """
    grouped: dict[str, list[tuple[str, str]]] = {}
    for err in exc.errors():
        path = pydantic_path(_strip_union_tag(err["loc"]))
        # Pydantic prefixes custom ValueErrors with "Value error, ".
        message = err["msg"].removeprefix("Value error, ")
        grouped.setdefault(path, []).append((err["type"], message))

    diagnostics = []
    for path, entries in grouped.items():
        authored = [msg for kind, msg in entries if kind == "value_error"]
        for message in authored or [msg for _, msg in entries]:
            # A validator that already located itself should not be located twice.
            text = message if message.startswith("$") else f"{path}: {message}"
            diagnostics.append(error(code, text, source=source, pack=pack))
    return diagnostics


__all__ = [
    "NLS_PACK_001",
    "NLS_PACK_002",
    "NLS_PACK_003",
    "NLS_PACK_004",
    "NLS_PACK_005",
    "NLS_PACK_006",
    "NLS_PACK_010",
    "SEVERITY_ERROR",
    "SEVERITY_INFO",
    "Diagnostic",
    "DiagnosticCollector",
    "PackError",
    "diagnostics_from_pydantic",
    "error",
    "info",
    "pydantic_path",
]
