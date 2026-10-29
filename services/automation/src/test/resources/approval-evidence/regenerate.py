#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Regenerate released fixtures: fulfilment uses 0.18.16; historical variants stay on 0.18.9."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile
import sys

parser = argparse.ArgumentParser()
parser.add_argument("cli", type=Path)
parser.add_argument("--check", action="store_true", help="Refuse any byte or file-set drift without writing")
variants = parser.add_mutually_exclusive_group()
variants.add_argument("--unqualified", action="store_true")
variants.add_argument("--generated-ids", action="store_true")
variants.add_argument("--obligations", action="store_true")
variants.add_argument("--fulfilment", action="store_true")
variants.add_argument("--obligation-conflict", action="store_true")
args = parser.parse_args()
cli = args.cli.resolve()
version = "0.18.16" if args.fulfilment else "0.18.9"
if subprocess.check_output([str(cli), "--version"], text=True).strip() != f"ttrp {version}":
    sys.exit(f"fixture regeneration requires the published ttrp {version} CLI")
resource = Path(__file__).resolve().parent
fixture = resource.parent / "bundles/hartland"
source = (resource / "generated-ids" if args.generated_ids else
          resource / "fulfilment" if args.fulfilment else
          resource / "obligations" if args.obligations or args.obligation_conflict else resource)
recipe_source = source / "conflict" if args.obligation_conflict else source
with tempfile.TemporaryDirectory(prefix="approval-evidence-") as directory:
    root = Path(directory)
    # Current fulfilment proves declared-package resolution in a flat checkout. Historical 0.18.9
    # variants retain their original producer layout and compiler pin.
    if args.fulfilment:
        models = root / "models"
        models.mkdir()
        files = sorted((fixture / "model").rglob("*.ttrm"))
        files += sorted(p for p in (fixture / "automation").rglob("*.ttrm")
                        if not any(part.endswith(".bundle") for part in p.parts))
        files += [resource / "db.ttrm", resource / "binding.ttrm"]
        for n, file in enumerate(files):
            text = (source / "automation.ttrm").read_text() if file.name == "automation.ttrm" else file.read_text()
            (models / f"metadata-{n}.ttrm").write_text(text)
    else:
        shutil.copytree(fixture / "model", root / "models/hartland")
        shutil.copytree(fixture / "automation", root / "models/hartland/automation",
                        ignore=shutil.ignore_patterns("release_order.bundle"))
        for name in ("db.ttrm", "binding.ttrm"):
            shutil.copyfile(resource / name, root / "models/hartland" / name)
    if args.generated_ids:
        shutil.copyfile(source / "proposal.ttrm", root / "models/hartland/er/proposal.ttrm")
    action_text = (source / "automation.ttrm").read_text()
    if args.unqualified:
        action_text = action_text.replace("hartland.order.", "order.")
    if not args.fulfilment:
        (root / "models/hartland/automation/automation.ttrm").write_text(action_text)
    shutil.copyfile(recipe_source / "release_order.ttrp", root / "release_order.ttrp")
    (root / "modeler.toml").write_text('[ttrp]\nworld = "hartland.worlds.demo"\n')
    if args.fulfilment:
        subprocess.run([str(cli), "check", "release_order.ttrp"], cwd=root, check=True)
    subprocess.run([str(cli), "build", "release_order.ttrp"], cwd=root, check=True)
    destination = resource / "unqualified" if args.unqualified else recipe_source
    output = destination / "release_order.bundle"
    generated = root / "release_order.bundle"
    if not (generated / "manifest.json").is_file():
        sys.exit("CLI did not produce a complete bundle; preserving the existing fixture")
    if args.check:
        def contents(folder):
            return {p.relative_to(folder).as_posix(): p.read_bytes() for p in folder.rglob("*") if p.is_file()}
        if contents(output) != contents(generated):
            sys.exit("fixture differs from released CLI output (including file set)")
        print("fixture is byte-identical to released CLI output")
        sys.exit(0)
    destination.mkdir(exist_ok=True)
    if output.exists():
        shutil.rmtree(output)
    shutil.copytree(generated, output)
    if args.unqualified:
        (destination / "automation.ttrm").write_text(action_text)
