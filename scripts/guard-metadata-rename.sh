#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Reject the old model-graph service name; history keeps formerly `veles` (MR-8).
# Scan tracked and untracked, non-ignored files, including binary contents and paths.
set -euo pipefail
cd "$(dirname "$0")/.."
old_name='ve''les'
place='Ve''leslavín'
history_marker='formerly `ve''les`'
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
unexpected="$work/unexpected"
: > "$unexpected"

# No path exemptions: even a clean-content file or a history path must use the new name.
git ls-files --cached --others --exclude-standard -z > "$work/files"
while IFS= read -r -d '' path; do
  if printf '%s\n' "$path" | grep -qi "$old_name"; then
    printf 'Legacy name in path: %s\n' "$path" >> "$unexpected"
  fi
done < "$work/files"

# -a treats binaries as text so frozen descriptors cannot hide a new service reference.
rc=0
git grep --untracked -a -l -z -i "$old_name" -- . > "$work/hits" || rc=$?
if (( rc > 1 )); then
  exit "$rc"
fi
while IFS= read -r -d '' path; do
  case "$path" in
    docs/*|*/docs/*|CHANGELOG.md|*/CHANGELOG.md) continue ;; # Preserved history (MR-8).
    services/metadata/src/test/resources/wire-golden/get_object_table.bin) continue ;; # Frozen old-wire message source path.
    services/metadata/src/test/resources/wire-golden/list_objects.bin) continue ;; # Frozen old-wire message source paths.
    shared/proto/compat/fuzzy-v1-pre-lp.desc) continue ;; # Frozen pre-LP contract, including the old service name.
    shared/proto/compat/resolver-v1-pre-lattice.desc) continue ;; # Frozen pre-lattice contract, including the old service name.
  esac
  grep -a -n -i "$old_name" "$path" > "$work/lines"
  rc=0
  grep -vF "$history_marker" "$work/lines" > "$work/current" || rc=$?
  if (( rc > 1 )); then exit "$rc"; fi
  if [[ "$path" == shared/libs/python/ttr-morph/lexicon/cs/cac-freq.tsv ]]; then
    # Only this unrelated Czech place-name row is allowed, not the whole corpus.
    rc=0
    grep -vE "^[0-9]+:${place}[[:space:]]1$" "$work/current" > "$work/filtered" || rc=$?
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
