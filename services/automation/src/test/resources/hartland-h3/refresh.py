#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verify/restore the pinned source blobs in SOURCE.json; no compiler or working-tree input."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('repository', type=Path)
parser.add_argument('--check', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parent
source = json.loads((root / 'SOURCE.json').read_text())
blobs = {}
for name, digest in source['sha256'].items():
    path = Path(name)
    if path.is_absolute() or '..' in path.parts:
        parser.error('source paths must stay within this fixture')
    data = subprocess.check_output(['git', '-C', str(args.repository), 'show', f'{source["commit"]}:{name}'])
    if hashlib.sha256(data).hexdigest() != digest:
        parser.error(f'pinned source hash differs: {name}')
    blobs[path] = data
if args.check:
    actual = {path.relative_to(root) for path in root.rglob('*') if path.is_file()}
    expected = set(blobs) | {Path('README.md'), Path('SOURCE.json'), Path('refresh.py')}
    if actual != expected:
        parser.error(f'fixture file set differs: extra={sorted(map(str, actual - expected))}, missing={sorted(map(str, expected - actual))}')
for path, data in blobs.items():
    destination = root / path
    if args.check:
        if not destination.is_file() or destination.read_bytes() != data:
            parser.error(f'fixture differs from pinned source: {path}')
    else:
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
print(f'{len(blobs)} pinned source blobs verified')
