# SPDX-License-Identifier: Apache-2.0
"""C-14 CLI: offline plan, registry check, and successful-sync state boundary."""

import argparse
import sys
from pathlib import Path

from pydantic import ValidationError

from .export_reader import DirSource, ExportError, NotModified, UrlSource
from .mapping import MappingError, deprecations, json_lines, plan_from_export
from .state import State, read_state, write_state


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="ttr-connect-openmetadata")
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("plan", "sync", "check"):
        command = commands.add_parser(name)
        if name != "check":
            source = command.add_mutually_exclusive_group(required=True)
            source.add_argument("--export-dir", type=Path)
            source.add_argument("--export-url", help="full /model/export route URL")
            command.add_argument("--world", help="defaults to the export world; must match it")
            command.add_argument("--studio-url")
            command.add_argument("--state-file", type=Path)
            command.add_argument(
                "--deprecate-missing", action=argparse.BooleanOptionalAction, default=True
            )
        if name != "plan":
            # Optional here so sync --dry-run remains independent of OM credentials.
            command.add_argument("--om-url")
            command.add_argument("--om-token-file", type=Path)
        if name == "sync":
            command.add_argument("--cn-path", choices=("owl", "glossary"), default="owl")
            command.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    dry = args.command == "plan" or (args.command == "sync" and args.dry_run)
    if not dry and (not args.om_url or not args.om_token_file):
        parser.error("check/sync require --om-url and --om-token-file (except sync --dry-run)")
    if args.command != "check":
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
            current_ids = {n.id for n in export.graph.nodes if not n.external}
            current_ids |= {e.id for e in export.graph.edges if e.id}
            if args.deprecate_missing:
                result.operations.extend(
                    deprecations(
                        set(state.ids) if state else set(), current_ids, export.graph.delta
                    )
                )
        except (MappingError, ValidationError) as exc:
            print(f"mapping error: {exc}", file=sys.stderr)
            return 3
        if dry:
            for warning in result.warnings:
                print(f"WARN {warning}", file=sys.stderr)
            print(json_lines(result), end="")
            return 0
    # Lazy import: plan/dry-run do not initialize the SDK, read credentials, or contact OM.
    from .om_client import OmClient, OmError

    client: OmClient | None = None
    try:
        token = args.om_token_file.read_text(encoding="utf-8").strip()
        client = OmClient(args.om_url, token)
        if args.command == "check":
            client.check()
            print(client.summary.model_dump_json())
        else:
            summary = client.sync(result, args.cn_path)
            if args.state_file:
                write_state(
                    args.state_file,
                    State(
                        snapshot=export.snapshot, ids=sorted(current_ids), om_version=client.version
                    ),
                )
            print(summary.model_dump_json())
        return 0
    except (OmError, OSError, ValidationError) as exc:
        print(f"OM error: {exc}", file=sys.stderr)
        return 1
    finally:
        if client is not None:
            for warning in client.warnings:
                print(f"WARN {warning}", file=sys.stderr)
            client.close()


if __name__ == "__main__":
    sys.exit(main())
