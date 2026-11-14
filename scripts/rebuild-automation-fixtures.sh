#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Rebuild the AU fixture bundles on bp-dsk using the published grammar CLI.
set -euo pipefail

cli="${1:?usage: scripts/rebuild-automation-fixtures.sh /path/to/ttrp-cli}"
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
fixture="$repo_root/services/automation/src/test/resources/bundles/hartland"
grammar_version="$(awk -F '"' '/^tatrman-grammar[[:space:]]*=/ { print $2 }' "$repo_root/gradle/libs.versions.toml")"
if [ "$("$cli" --version)" != "ttrp $grammar_version" ]; then
    echo "The fixture compiler must match the grammar pin $grammar_version" >&2
    exit 1
fi
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
# review-247 N1: every bundle is built under $scratch, the guards run on that output, and only then is the repo
# changed — in one install step. A failing guard leaves bundles/ and approval-evidence/ untouched.
built=()   # pairs: <built bundle dir> <repo bundle dir it replaces>

mkdir -p "$scratch/models/hartland/automation"
cp -R "$fixture/model/." "$scratch/models/hartland/"
cp "$fixture/automation/"*.ttrm "$scratch/models/hartland/automation/"
printf '[ttrp]\nworld = "hartland.worlds.demo"\n' > "$scratch/modeler.toml"

for recipe in release_order decide_return monitor_inbound settle; do
    cp "$fixture/automation/$recipe/$recipe.ttrp" "$scratch/$recipe.ttrp"
    "$cli" build "$scratch/$recipe.ttrp" --out "$scratch/out/$recipe"
    built+=("$scratch/out/$recipe/$recipe.bundle" "$fixture/automation/$recipe/$recipe.bundle")
done

# Evidence-bearing writer cases use their own ER, binding and database slice.
evidence="$fixture/../../approval-evidence"
for variant in base generated-ids obligations conflict fulfilment unqualified; do
    resource="$evidence/$variant"
    [ "$variant" != base ] || resource="$evidence"
    [ "$variant" != conflict ] || resource="$evidence/obligations"
    input="$scratch/$variant"
    mkdir -p "$input/models/hartland/automation"
    cp -R "$fixture/model/." "$input/models/hartland/"
    rm -rf "$input/models/hartland/er" "$input/models/hartland/binding" "$input/models/hartland/db"
    mkdir -p "$input/models/hartland/er" "$input/models/hartland/binding" "$input/models/hartland/db"
    cp "$evidence/model.ttrm" "$input/models/hartland/er/orders.ttrm"
    cp "$evidence/binding.ttrm" "$input/models/hartland/binding/er2db.ttrm"
    cp "$evidence/db.ttrm" "$input/models/hartland/db/orders.ttrm"
    cp "$fixture/automation/"*.ttrm "$input/models/hartland/automation/"
    cp "$resource/automation.ttrm" "$input/models/hartland/automation/automation.ttrm"
    if [ "$variant" = generated-ids ]; then
        cp "$resource/proposal.ttrm" "$input/models/hartland/er/proposal.ttrm"
        cp "$resource/binding.ttrm" "$input/models/hartland/binding/proposal.ttrm"
        cp "$resource/db.ttrm" "$input/models/hartland/db/proposal.ttrm"
    fi
    source="$resource/release_order.ttrp"
    [ "$variant" != unqualified ] || source="$evidence/release_order.ttrp"
    [ "$variant" != conflict ] || resource="$resource/conflict"
    [ "$variant" != conflict ] || source="$resource/release_order.ttrp"
    cp "$source" "$input/release_order.ttrp"
    cp "$scratch/modeler.toml" "$input/modeler.toml"
    "$cli" build "$input/release_order.ttrp" --out "$input/out"
    built+=("$input/out/release_order.bundle" "$resource/release_order.bundle")
done

# The durable approval fixture must approve this exact compiler output; retain its superseded negative case.
manifest="$scratch/fulfilment/out/release_order.bundle/manifest.json"
approvals="$evidence/fulfilment/approvals.json"
text_fp="$(awk -F '"' '/"text": "sha256:/ { print $4; exit }' "$manifest")"
values_fp="$(awk -F '"' '/"values": "sha256:/ { print $4; exit }' "$manifest")"
superseded="$(awk -F '"' '/"supersededText"/ { print $4 }' "$approvals")"
[ -n "$text_fp" ] || { echo "no text fingerprint in $manifest" >&2; exit 1; }
[ -n "$values_fp" ] || { echo "no values fingerprint in $manifest" >&2; exit 1; }
[ -n "$superseded" ] || { echo "no superseded text fingerprint in $approvals" >&2; exit 1; }
[ "$text_fp" != "$superseded" ] || { echo "text fingerprint is still superseded in $manifest" >&2; exit 1; }
cat > "$scratch/approvals.json" <<EOF
{
  "recipe": "release_order",
  "text": "$text_fp",
  "values": "$values_fp",
  "supersededText": "$superseded"
}
EOF

# Every guard passed: install the rebuilt bundles and the approval fingerprints together.
for ((i = 0; i < ${#built[@]}; i += 2)); do
    rm -rf "${built[i + 1]}"
    cp -R "${built[i]}" "${built[i + 1]}"
done
cp "$scratch/approvals.json" "$approvals"
