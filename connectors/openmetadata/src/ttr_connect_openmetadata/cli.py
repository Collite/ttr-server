# SPDX-License-Identifier: Apache-2.0
"""C-14 command surface; check/sync are deliberately deferred to S2.2."""

import argparse
import sys
from pathlib import Path

from pydantic import ValidationError

from .export_reader import DirSource, ExportError, NotModified, UrlSource
from .mapping import MappingError, deprecations, json_lines, plan_from_export
from .state import read_state


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="ttr-connect-openmetadata")
    commands = parser.add_subparsers(dest="command", required=True)
    plan = commands.add_parser("plan", help="print operations as JSON lines; never write")
    source = plan.add_mutually_exclusive_group(required=True)
    source.add_argument("--export-dir", type=Path)
    source.add_argument("--export-url", help="full /model/export route URL")
    plan.add_argument("--world", help="defaults to the export world; must match it")
    plan.add_argument("--studio-url")
    plan.add_argument("--state-file", type=Path)
    plan.add_argument("--deprecate-missing", action=argparse.BooleanOptionalAction, default=True)
    for name in ("check", "sync"):
        commands.add_parser(name, help="implemented in XM-P2 S2.2")
    args = parser.parse_args(argv)
    if args.command != "plan":
        raise NotImplementedError(f"{args.command} is implemented in XM-P2 S2.2")
    try:
        state = read_state(args.state_file) if args.state_file else None
        export = (
            DirSource(args.export_dir).read(state)
            if args.export_dir
            else UrlSource(args.export_url).read(state)
        )
        if isinstance(export, NotModified):
            return 0
    except (ExportError, OSError, ValidationError) as exc:
        print(f"export error: {exc}", file=sys.stderr)
        return 2
    try:
        result = plan_from_export(export, args.world, args.studio_url)
        if args.deprecate_missing:
            current_ids = {n.id for n in export.graph.nodes if not n.external}
            current_ids |= {e.id for e in export.graph.edges if e.id}
            result.operations.extend(
                deprecations(
                    set(state.ids) if state else set(),
                    current_ids,
                    export.graph.delta,
                )
            )
        for warning in result.warnings:
            print(f"WARN {warning}", file=sys.stderr)
        print(json_lines(result), end="")
        return 0
    except (MappingError, ValidationError) as exc:
        print(f"mapping error: {exc}", file=sys.stderr)
        return 3


if __name__ == "__main__":
    sys.exit(main())
