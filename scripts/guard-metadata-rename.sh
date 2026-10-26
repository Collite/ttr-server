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
source scripts/metadata-rename-frozen.sh
for i in "${!FROZEN_PATHS[@]}"; do
  check_frozen "${FROZEN_PATHS[$i]}" "${FROZEN_HASHES[$i]}"
done

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
  esac
  for frozen in "${FROZEN_PATHS[@]}"; do
    if [[ "$path" == "$frozen" ]]; then continue 2; fi # Hash-checked bytes only.
  done
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
