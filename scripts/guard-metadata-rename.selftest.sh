#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Exercise the guard with arbitrary bytes on the host grep implementation.
set -euo pipefail
export LC_ALL=C
source_root=$(cd "$(dirname "$0")/.." && pwd)
guard_source=${1:-"$source_root/scripts/guard-metadata-rename.sh"}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
old_name='ve''les'

# Use a tiny disposable Git repo so probes never modify the user's checkout.
# Probe files stay untracked and non-ignored, exercising the production --untracked pass.
git init -q "$work/repo"
cd "$work/repo"
mkdir -p scripts
cp "$guard_source" scripts/guard-metadata-rename.sh
for path in \
  services/metadata/src/test/resources/wire-golden/get_object_table.bin \
  services/metadata/src/test/resources/wire-golden/list_objects.bin \
  shared/proto/compat/fuzzy-v1-pre-lp.desc \
  shared/proto/compat/resolver-v1-pre-lattice.desc; do
  mkdir -p "$(dirname "$path")"
  cp "$source_root/$path" "$path"
done
git add .

expect_clean() {
  if ! /bin/bash scripts/guard-metadata-rename.sh > "$work/output" 2>&1; then
    cat "$work/output" >&2
    echo 'Guard self-test: clean baseline failed.' >&2
    exit 1
  fi
}
expect_rejected() {
  local label="$1" rc=0
  /bin/bash scripts/guard-metadata-rename.sh > "$work/output" 2>&1 || rc=$?
  if [[ "$rc" != 1 ]]; then
    cat "$work/output" >&2
    printf 'Guard self-test: %s returned %s, expected 1.\n' "$label" "$rc" >&2
    exit 1
  fi
  printf 'PASS: %s\n' "$label"
}
expect_clean
printf 'a\0b %s c\n' "$old_name" > probe.bin
expect_rejected 'NUL-containing binary'
rm probe.bin
printf 'host = "%s" # caf\351\n' "$old_name" > probe.txt
expect_rejected 'Latin-1 text'
rm probe.txt
mkdir "${old_name}-probe"
printf 'clean content\n' > "${old_name}-probe/file.txt"
expect_rejected 'clean-content legacy path'
rm -r "${old_name}-probe"
mkdir docs
printf 'host = "%s"\n' "$old_name" > docs/probe.md
expect_rejected 'living docs regression'
rm -r docs
printf 'host = "%s" # formerly `%s`\n' "$old_name" "$old_name" > probe.txt
expect_rejected 'legacy content beside an allowed marker'
rm probe.txt
printf 'history: formerly `%s`\n' "$old_name" > probe.txt
expect_clean
rm probe.txt

# Exercise the second filtering grep against both binary/encoding hazards, too.
corpus=shared/libs/python/ttr-morph/lexicon/cs/cac-freq.tsv
mkdir -p "$(dirname "$corpus")"
printf 'a\0b %s c\n' "$old_name" > "$corpus"
expect_rejected 'NUL in the corpus filtering pass'
printf 'host = "%s" # caf\351\n' "$old_name" > "$corpus"
expect_rejected 'Latin-1 in the corpus filtering pass'
rm "$corpus"

# Every exempt fixture must retain its exact hash, even without a matching new name.
for path in \
  services/metadata/src/test/resources/wire-golden/get_object_table.bin \
  services/metadata/src/test/resources/wire-golden/list_objects.bin \
  shared/proto/compat/fuzzy-v1-pre-lp.desc \
  shared/proto/compat/resolver-v1-pre-lattice.desc; do
  cp "$path" "$work/original"
  printf '\0changed' >> "$path"
  expect_rejected "frozen hash: $path"
  cp "$work/original" "$path"
done
expect_clean
echo 'Guard self-test: all byte, path, marker, docs and frozen-hash probes passed.'
