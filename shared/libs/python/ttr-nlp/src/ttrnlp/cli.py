# SPDX-License-Identifier: Apache-2.0
"""``ttr-nlp`` — the command line (NL-15).

The command that matters is ``ttr-nlp validate``, and its whole point is that it
runs the *same* ``ttrnlp.packs.validate`` code path as the service boot and
``ReloadPacks``: a pack that validates on a laptop validates identically in the
cluster, and the DFP model-validator wraps this rather than reimplementing it.

Written with stdlib ``argparse`` and nothing else. A CLI framework would be a
mandatory dependency for every consumer that only wants the library, and this
surface is one subcommand and three options.

Exit codes (contracts §9)::

    0   no ERROR diagnostics — these sources would load
    1   validation errors — the diagnostics are the output
    2   usage or I/O failure — the command could not be run as asked

The 1/2 split is about *whose* mistake it is, and it is drawn here rather than
inside the shared path. A path that does not exist is this command's argument
being wrong (2); the same missing directory in the *service's* config is a
volume that failed to mount, which must come back as a diagnostic so the service
can serve NOT_READY and say why (see ``loader``'s docstring). One reader, two
audiences, and the difference is handled where the audience is known.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Sequence
from pathlib import Path

from ttrnlp import __version__
from ttrnlp.packs.diag import SEVERITY_ERROR, Diagnostic
from ttrnlp.packs.validate import validate_sources
from ttrnlp.stages import StageError

EXIT_OK = 0
EXIT_VALIDATION_ERRORS = 1
EXIT_USAGE = 2


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="ttr-nlp",
        description="Tatrman NLP suite — rule-pack and gazetteer-list tooling.",
    )
    parser.add_argument("--version", action="version", version=f"ttr-nlp {__version__}")
    sub = parser.add_subparsers(dest="command", required=True)

    validate = sub.add_parser(
        "validate",
        help="validate rule packs and gazetteer lists",
        description=(
            "Validate each PATH (a list or stage source file, or a directory of "
            "them) through the stages installed in the `ttrnlp.stages` group — "
            "the rule engine owns `*.pack.yaml`. This runs the same code path as "
            "the nlp service's boot-time load and its ReloadPacks RPC, so a "
            "source that passes here passes there."
        ),
    )
    validate.add_argument("paths", nargs="+", metavar="PATH")
    validate.add_argument(
        "--model",
        metavar="DIR",
        help="also cross-check QueryPattern query ids and parameter names "
        "against a TTR-M model (contracts §5)",
    )
    validate.add_argument(
        "--json",
        action="store_true",
        dest="as_json",
        help="emit diagnostics as JSON for machine consumption",
    )
    return parser


def _unreadable(paths: Sequence[str]) -> list[str]:
    """Arguments that are neither an existing path nor an ``http(s)`` URL.

    A URL is not checked here — a server that is down is not a usage error, and
    finding out costs a request the shared path is going to make anyway.
    """
    return [
        path
        for path in paths
        if not path.startswith(("http://", "https://")) and not Path(path).exists()
    ]


def _as_json(diagnostics: Sequence[Diagnostic]) -> str:
    """The wire shape, field for field (contracts §2.3 ``PackDiagnostic``).

    Same names as the proto message, so a wrapper parsing CLI output and a
    wrapper reading a ``ReloadPacks`` response need one parser between them.
    """
    return json.dumps(
        [
            {
                "source": d.source,
                "pack": d.pack,
                "severity": d.severity,
                "code": d.code,
                "message": d.message,
            }
            for d in diagnostics
        ],
        indent=2,
        ensure_ascii=False,
    )


def _report(diagnostics: Sequence[Diagnostic], paths: Sequence[str]) -> None:
    """One line per diagnostic, then a summary line (contracts §9)."""
    for d in diagnostics:
        where = ":".join(part for part in (d.source, d.pack) if part) or "?"
        print(f"{d.severity} {d.code} {where} — {d.message}")

    errors = sum(1 for d in diagnostics if d.severity == SEVERITY_ERROR)
    other = len(diagnostics) - errors
    scope = ", ".join(paths)
    if errors:
        print(
            f"\n{errors} error(s){f' and {other} note(s)' if other else ''} "
            f"in {scope} — nothing would load (fail-all)."
        )
    else:
        print(f"OK — {scope} would load{f' ({other} note(s))' if other else ''}.")


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)

    if args.command != "validate":  # pragma: no cover — argparse rejects others
        return EXIT_USAGE

    missing = _unreadable(args.paths)
    if missing:
        print(
            f"ttr-nlp validate: no such file or directory: {', '.join(missing)}",
            file=sys.stderr,
        )
        return EXIT_USAGE

    if args.model is not None and not Path(args.model).exists():
        print(
            f"ttr-nlp validate: --model directory does not exist: {args.model}",
            file=sys.stderr,
        )
        return EXIT_USAGE

    try:
        diagnostics = validate_sources(args.paths, model=args.model)
    except StageError as exc:
        # The environment, not the sources: two installed stages clash.
        print(f"ttr-nlp validate: {exc}", file=sys.stderr)
        return EXIT_USAGE

    if args.as_json:
        print(_as_json(diagnostics))
    else:
        _report(diagnostics, args.paths)

    return (
        EXIT_VALIDATION_ERRORS
        if any(d.severity == SEVERITY_ERROR for d in diagnostics)
        else EXIT_OK
    )


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
