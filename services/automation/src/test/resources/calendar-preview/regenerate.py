#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Regenerate the calendar fixture with the published 0.18.14 CLI; --check refuses any drift."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("cli", type=Path)
parser.add_argument("--check", action="store_true")
parser.add_argument("--date-difference", action="store_true", help="Regenerate the bound-date-minus-column variant")
args = parser.parse_args()
cli = args.cli.resolve()
assert subprocess.check_output([str(cli), "--version"], text=True).strip() == "ttrp 0.18.14"
resource = Path(__file__).resolve().parent
variant = resource / "date-difference" if args.date_difference else resource
with tempfile.TemporaryDirectory(prefix="calendar-preview-") as directory:
    root = Path(directory) / "unrelated-checkout"
    shutil.copytree(resource.parent / "bundles/hartland", root)
    for source in (resource / "model").rglob("*.ttrm"):
        destination = root / source.relative_to(resource)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
    for name in ("db.ttrm", "binding.ttrm"):
        shutil.copyfile(resource.parent / "approval-evidence" / name, root / "model" / name)
    shutil.copyfile(variant / "release_order.ttrp", root / "release_order.ttrp")
    (root / "modeler.toml").write_text('[ttrp]\nworld = "hartland.worlds.demo"\nextra-model-roots = ["model", "automation"]\n')
    subprocess.run([str(cli), "check", "release_order.ttrp"], cwd=root, check=True)
    subprocess.run([str(cli), "build", "release_order.ttrp"], cwd=root, check=True)
    generated = root / "release_order.bundle"
    assert (generated / "manifest.json").is_file(), "CLI did not produce a complete bundle; preserving existing fixture"
    destination = variant / "release_order.bundle"
    if args.check:
        def contents(folder):
            return {p.relative_to(folder): p.read_bytes() for p in folder.rglob("*") if p.is_file()}
        assert contents(generated) == contents(destination), "calendar fixture drift (including extra files)"
    else:
        if destination.exists():
            shutil.rmtree(destination)
        shutil.copytree(generated, destination)
