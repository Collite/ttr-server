# SPDX-License-Identifier: Apache-2.0
"""C-14 CLI: offline plan, registry check, and successful-sync state boundary."""

import argparse
import json
import sys
from importlib.metadata import version
from pathlib import Path
from typing import TYPE_CHECKING

from pydantic import ValidationError

from .export_reader import DirSource, ExportError, NotModified, UrlSource
from .mapping import (
    MappingError,
    deprecations,
    json_lines,
    plan_from_export,
    resolve_studio_workspace,
)
from .model import EnsureGlossary, Export, ImportRdf
from .state import State, options_fingerprint, read_state, write_state
from .urls import http_origin

if TYPE_CHECKING:
    from .om_client import OmClient


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
            command.add_argument(
                "--studio-workspace", help="modeler.toml project.name; defaults to the export world"
            )
            command.add_argument("--cn-path", choices=("owl", "glossary"), default="glossary")
            command.add_argument("--cn-name-mode", choices=("label", "qname"), default="label")
            command.add_argument("--state-file", type=Path)
            command.add_argument(
                "--deprecate-missing", action=argparse.BooleanOptionalAction, default=True
            )
        if name != "plan":
            # Optional here so sync --dry-run remains independent of OM credentials.
            command.add_argument("--om-url")
            command.add_argument("--om-token-file", type=Path)
        if name == "sync":
            command.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    if args.command != "check" and args.studio_workspace is not None and not args.studio_url:
        parser.error("--studio-workspace requires --studio-url")
    dry = args.command == "plan" or (args.command == "sync" and args.dry_run)
    if not dry and (not args.om_url or not args.om_token_file):
        parser.error("check/sync require --om-url and --om-token-file (except sync --dry-run)")
    # Offline commands never initialize the SDK or read credentials.
    if dry:
        return run(args, dry, None)
    from .om_client import OmClient, OmError

    client: OmClient | None = None
    try:
        token = args.om_token_file.read_text(encoding="utf-8").strip()
        client = OmClient(args.om_url, token)
        if args.command != "check":
            client.read_version()
        return run(args, dry, client)
    except (OmError, OSError, ValidationError) as exc:
        print(f"OM error: {exc}", file=sys.stderr)
        return 1
    finally:
        if client is not None:
            for warning in client.warnings:
                print(f"WARN {warning}", file=sys.stderr)
            client.close()


def run(args: argparse.Namespace, dry: bool, client: "OmClient | None") -> int:
    if args.command != "check":
        try:
            studio_origin = (
                http_origin(args.studio_url, allow_path=False) if args.studio_url else None
            )
            workspace = (
                resolve_studio_workspace("", args.studio_workspace)
                if studio_origin and args.studio_workspace is not None
                else None
            )
        except (ValueError, MappingError) as exc:
            print(f"mapping error: studio URL/workspace: {exc}", file=sys.stderr)
            return 3

        def fingerprint_for(world: str | None) -> str | None:
            return (
                options_fingerprint(
                    world,
                    studio_origin,
                    args.cn_path,
                    args.cn_name_mode,
                    args.deprecate_missing,
                    version("ttr-connect-openmetadata"),
                    client.version,
                    workspace,
                )
                if client is not None
                else None
            )

        try:
            state = read_state(args.state_file) if args.state_file else None
            known_world = args.world or (state.world if state else None)
            if studio_origin and workspace is None and known_world is not None:
                workspace = resolve_studio_workspace(known_world, None)
            fingerprint = fingerprint_for(known_world)
            # Keep old ids for deprecations, but never send an incompatible ETag.
            cached = state if client is None or (state and state.options == fingerprint) else None
            export = (
                DirSource(args.export_dir).read(cached)
                if args.export_dir
                else UrlSource(args.export_url).read(cached)
            )
            if isinstance(export, NotModified):
                if not dry:
                    print(
                        json.dumps(
                            {
                                "snapshot": export.snapshot,
                                "created": 0,
                                "updated": 0,
                                "deprecated": 0,
                                "linked": 0,
                                "warnings": 0,
                                "not_modified": True,
                            }
                        )
                    )
                return 0
        except (ExportError, OSError, ValidationError) as exc:
            print(f"export error: {exc}", file=sys.stderr)
            return 2
        try:
            if studio_origin and workspace is None:
                workspace = resolve_studio_workspace(export.graph.world, None)
            fingerprint = fingerprint_for(args.world or export.graph.world)
            result = plan_from_export(
                export,
                args.world,
                cn_name_mode=args.cn_name_mode,
                studio_base=f"{studio_origin}/m/{workspace}/s/" if studio_origin else None,
            )
            if args.cn_path == "glossary":
                result.operations = [
                    op for op in result.operations if not isinstance(op, ImportRdf)
                ]
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
            if result.studio_links:
                glossaries = ", ".join(
                    op.name for op in result.operations if isinstance(op, EnsureGlossary)
                )
                print(
                    "INFO sync also normalizes Studio links on out-of-plan, "
                    f"non-deprecated terms in {glossaries}",
                    file=sys.stderr,
                )
            for warning in result.warnings:
                print(f"WARN {warning}", file=sys.stderr)
            print(json_lines(result), end="")
            return 0
    assert client is not None
    if args.command == "check":
        client.check()
        print(client.summary.model_dump_json())
    else:
        assert isinstance(export, Export)
        summary = client.sync(result, args.cn_path)
        # Exhaustive warning classification: get_table, set_column_tags and _asset
        # skip unscanned assets; plan.warnings omit unmappable assets. RDF import
        # messages and successful glossary fallback do not omit operations.
        incomplete = bool(client.skipped_tables or client.skipped_columns or result.warnings)
        if incomplete:
            print(
                f"WARN state not advanced: {len(client.skipped_tables)} tables not scanned; "
                f"{len(client.skipped_columns)} columns not scanned; "
                f"{len(result.warnings)} mapping skips",
                file=sys.stderr,
            )
        elif args.state_file:
            write_state(
                args.state_file,
                State(
                    snapshot=export.snapshot,
                    ids=sorted(current_ids),
                    om_version=client.version,
                    world=export.graph.world,
                    options=fingerprint,
                ),
            )
        print(summary.model_dump_json())
    return 0


if __name__ == "__main__":
    sys.exit(main())
