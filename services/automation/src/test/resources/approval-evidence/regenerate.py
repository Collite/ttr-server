#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Regenerate this fixture with the installed, published ttrp 0.18.9 CLI."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile
import sys

parser = argparse.ArgumentParser()
parser.add_argument("cli", type=Path)
args = parser.parse_args()
cli = args.cli.resolve()
if subprocess.check_output([str(cli), "--version"], text=True).strip() != "ttrp 0.18.9":
    sys.exit("fixture regeneration requires the published ttrp 0.18.9 CLI")
resource = Path(__file__).resolve().parent
fixture = resource.parent / "bundles/hartland"
with tempfile.TemporaryDirectory(prefix="approval-evidence-") as directory:
    root = Path(directory)
    # Non-approval compiler resolution still uses the conventional producer folder layout.
    shutil.copytree(fixture / "model", root / "models/hartland")
    shutil.copytree(fixture / "automation", root / "models/hartland/automation",
                    ignore=shutil.ignore_patterns("release_order.bundle"))
    for name in ("db.ttrm", "binding.ttrm"):
        shutil.copyfile(resource / name, root / "models/hartland" / name)
    shutil.copyfile(resource / "automation.ttrm", root / "models/hartland/automation/automation.ttrm")
    shutil.copyfile(resource / "release_order.ttrp", root / "release_order.ttrp")
    (root / "modeler.toml").write_text('[ttrp]\nworld = "hartland.worlds.demo"\n')
    subprocess.run([str(cli), "build", "release_order.ttrp"], cwd=root, check=True)
    output = resource / "release_order.bundle"
    generated = root / "release_order.bundle"
    if not (generated / "manifest.json").is_file():
        sys.exit("CLI did not produce a complete bundle; preserving the existing fixture")
    if output.exists():
        shutil.rmtree(output)
    shutil.copytree(generated, output)
