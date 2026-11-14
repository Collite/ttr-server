#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Exercise the actual rebuild script with malformed copies of a released manifest.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
probe="$(mktemp -d)"
trap 'rm -rf "$probe"' EXIT
resources=services/automation/src/test/resources
mkdir -p "$probe/scripts" "$probe/gradle" "$probe/$resources/bundles"
cp "$repo_root/scripts/rebuild-automation-fixtures.sh" "$probe/scripts/"
cp "$repo_root/gradle/libs.versions.toml" "$probe/gradle/"
cp -R "$repo_root/$resources/bundles/hartland" "$probe/$resources/bundles/"
cp -R "$repo_root/$resources/approval-evidence" "$probe/$resources/"
# review-247 N1: the probe is a git repo, so a failing run must leave `git status` clean on both fixture trees.
git -C "$probe" init -q
git -C "$probe" add -A
git -C "$probe" -c user.name=selftest -c user.email=selftest@invalid commit -q -m probe
export D3_TEST_MANIFEST="$repo_root/$resources/approval-evidence/fulfilment/release_order.bundle/manifest.json"
export D3_TEST_VERSION
D3_TEST_VERSION="$(awk -F '"' '/^tatrman-grammar[[:space:]]*=/ { print $2 }' "$probe/gradle/libs.versions.toml")"
approvals="$probe/$resources/approval-evidence/fulfilment/approvals.json"
cp "$approvals" "$probe/original.json"
export D3_TEST_SUPERSEDED
D3_TEST_SUPERSEDED="$(awk -F '"' '/"supersededText"/ { print $4 }' "$approvals")"
cat > "$probe/cli" <<'CLI'
#!/usr/bin/env bash
set -euo pipefail
if [ "$1" = --version ]; then
    echo "ttrp $D3_TEST_VERSION"
    exit 0
fi
recipe="$(basename "$2" .ttrp)"
output="$4/$recipe.bundle"
mkdir -p "$output"
cp "$D3_TEST_MANIFEST" "$output/manifest.json"
if [[ "$4" == */fulfilment/out ]]; then
    case "$D3_TEST_FAILURE" in
        text|values)
            awk -v key="\"$D3_TEST_FAILURE\": \"sha256:" 'index($0, key) == 0' "$output/manifest.json" > "$output/changed.json"
            mv "$output/changed.json" "$output/manifest.json"
            ;;
        superseded)
            sed -E "s/(\"text\": \")sha256:[a-f0-9]+/\1$D3_TEST_SUPERSEDED/" "$output/manifest.json" > "$output/changed.json"
            mv "$output/changed.json" "$output/manifest.json"
            ;;
    esac
fi
CLI
chmod +x "$probe/cli"
for failure in text values superseded; do
    export D3_TEST_FAILURE="$failure"
    if bash "$probe/scripts/rebuild-automation-fixtures.sh" "$probe/cli" > "$probe/output" 2>&1; then
        echo "FAIL: $failure fingerprint guard did not stop the rebuild" >&2
        exit 1
    fi
    cmp "$probe/original.json" "$approvals"
    dirty="$(git -C "$probe" status --porcelain -- "$resources/bundles" "$resources/approval-evidence")"
    [ -z "$dirty" ] || { echo "FAIL: $failure guard left the fixture trees changed:" >&2; echo "$dirty" >&2; exit 1; }
    case "$failure" in
        text|values) grep -q "no $failure fingerprint" "$probe/output" ;;
        superseded) grep -q 'text fingerprint is still superseded' "$probe/output" ;;
    esac
    echo "PASS: $failure guard exits non-zero; approvals.json byte-identical; fixture trees untouched"
done
export D3_TEST_FAILURE=none
# A positive control proves valid manifests still replace stale approval fingerprints.
sed 's/"text": "sha256:[a-f0-9]*"/"text": "stale"/' "$probe/original.json" > "$approvals"
bash "$probe/scripts/rebuild-automation-fixtures.sh" "$probe/cli"
cmp "$probe/original.json" "$approvals"
echo 'PASS: valid manifest refreshes approval fingerprints'
