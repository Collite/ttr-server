#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Reject the old model-graph service name; history keeps formerly `veles` (MR-8).
# Scan tracked and untracked, non-ignored files, including binary contents and paths.
set -euo pipefail
export LC_ALL=C
cd "$(dirname "$0")/.."
old_name='ve''les'
place='Ve''leslavín'
history_marker='formerly `ve''les`'
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
unexpected="$work/unexpected"
: > "$unexpected"

# These exact old-wire files are exempt from content scanning only while byte-identical.
check_frozen() {
  local path="$1" expected="$2" actual
  if command -v sha256sum >/dev/null 2>&1; then
    actual=$(sha256sum "$path")
  else
    actual=$(shasum -a 256 "$path")
  fi
  if [[ "${actual%% *}" != "$expected" ]]; then
    printf 'Frozen compatibility bytes changed: %s\n' "$path" >> "$unexpected"
  fi
}
# Frozen pre-semantics message; its historical source path is part of the wire bytes.
check_frozen services/metadata/src/test/resources/wire-golden/get_object_table.bin \
  951d78b8e60f0934c94adac6e64cf36be71a2f256d13b2a68d5d2f3f92ed0d8d
# Frozen pre-semantics message listing, including historical source paths.
check_frozen services/metadata/src/test/resources/wire-golden/list_objects.bin \
  dec5f503c5d9c28bd363fcb7ddedd83b5748504ae6a0ffe45d08d15c08906f34
# Frozen pre-LP contract, including the former service name.
check_frozen shared/proto/compat/fuzzy-v1-pre-lp.desc \
  baa60f2c000ff000e1a8d4ee21fb43ace4300e5fd3beb3bd117b33e1acbc21fe
# Frozen pre-lattice contract, including the former service name.
check_frozen shared/proto/compat/resolver-v1-pre-lattice.desc \
  38378e7d81c5a2a62c953980f7cb6359a5e98d32cebdd2b561d0f55aa8530ea6

# No path exemptions, including history paths. Check the file list in one pass.
git ls-files --cached --others --exclude-standard -z > "$work/files"
rc=0
tr '\0' '\n' < "$work/files" | grep -a -i "$old_name" > "$work/paths" || rc=$?
if (( rc > 1 )); then exit "$rc"; fi
if [[ -s "$work/paths" ]]; then
  echo 'Legacy name in paths:' >> "$unexpected"
  cat "$work/paths" >> "$unexpected"
fi

# Every content/filter pass treats arbitrary bytes as text. Frozen files were hashed above.
rc=0
git grep --untracked -a -l -z -i "$old_name" -- . > "$work/hits" || rc=$?
if (( rc > 1 )); then exit "$rc"; fi
while IFS= read -r -d '' path; do
  case "$path" in
    CHANGELOG.md|*/CHANGELOG.md) continue ;; # Published history (MR-8).
    services/metadata/src/test/resources/wire-golden/get_object_table.bin|\
    services/metadata/src/test/resources/wire-golden/list_objects.bin|\
    shared/proto/compat/fuzzy-v1-pre-lp.desc|\
    shared/proto/compat/resolver-v1-pre-lattice.desc) continue ;; # Hash-checked frozen bytes.
  esac
  grep -a -n -i "$old_name" "$path" > "$work/lines"
  # Only the marker substring is allowed; another occurrence on the same line still fails.
  sed "s/$history_marker//g" "$work/lines" > "$work/without-marker"
  rc=0
  grep -a -i "$old_name" "$work/without-marker" > "$work/current" || rc=$?
  if (( rc > 1 )); then exit "$rc"; fi
  if [[ "$path" == shared/libs/python/ttr-morph/lexicon/cs/cac-freq.tsv ]]; then
    # Only this unrelated Czech place-name row is allowed, not the whole corpus.
    rc=0
    grep -a -vE "^[0-9]+:${place}[[:space:]]1$" "$work/current" > "$work/filtered" || rc=$?
    if (( rc > 1 )); then exit "$rc"; fi
    mv "$work/filtered" "$work/current"
  fi
  if [[ -s "$work/current" ]]; then
    printf '%s:\n' "$path" >> "$unexpected"
    cat "$work/current" >> "$unexpected"
  fi
done < "$work/hits"

if [[ -s "$unexpected" ]]; then
  cat "$unexpected" >&2
  echo 'Unexpected legacy model-graph service names remain.' >&2
  exit 1
fi
echo 'No unexpected legacy model-graph service names.'
