#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Reject the old model-graph service name; history keeps formerly `veles` (MR-8).
# The two frozen pre-semantics protobuf messages retain their original source
# paths: changing them would erase the old-wire compatibility evidence.
# The Czech frequency corpus also contains a place name, unrelated to a service.
set -euo pipefail
cd "$(dirname "$0")/.."
old_name='ve''les'
hits=$(mktemp)
trap 'rm -f "$hits"' EXIT
rc=0
grep -rIin "$old_name" --exclude-dir=build --exclude-dir=.venv \
  --exclude-dir=node_modules --exclude-dir=.git --exclude-dir=.idea \
  --exclude=CHANGELOG.md --exclude-dir=docs . > "$hits" || rc=$?
if (( rc > 1 )); then
  exit "$rc"
fi
# Exact frozen fixture paths, not a general exemption for binary/resource files.
if grep -v 'formerly `veles`' "$hits" \
  | grep -vE '^\./services/metadata/src/test/resources/wire-golden/(get_object_table|list_objects)\.bin:' \
  | grep -vE "^\./shared/libs/python/ttr-morph/lexicon/cs/cac-freq\.tsv:[0-9]+:${old_name^}lavín[[:space:]]1$" \
  > "$hits.unexpected"; then
  cat "$hits.unexpected" >&2
  rm -f "$hits.unexpected"
  echo 'Unexpected legacy model-graph service names remain.' >&2
  exit 1
fi
rm -f "$hits.unexpected"
echo 'No unexpected legacy model-graph service names.'
