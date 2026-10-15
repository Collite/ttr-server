#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Golden-template test for the umbrella chart (SV-P4·S2·T1). Renders the chart
# against three pinned values fixtures and compares to committed golden output —
# the "recompute by hand first" drift gate (RG style).
#
#   scripts/helm-golden.sh            regenerate the golden files (after an
#                                     intended chart change; review the diff)
#   scripts/helm-golden.sh --check    fail if any render drifts from golden (CI)
#
# Determinism: fixed release name + namespace, no cluster access (`helm template`),
# and the chart uses no time/random template funcs. Golden output is HELM-VERSION
# SENSITIVE — regenerate with the pinned helm v4.2.1 (the version CI installs, see
# .github/workflows/ci.yml `helm` job) so a version skew doesn't masquerade as drift.
set -euo pipefail

cd "$(dirname "$0")/.."
CHART="helm/tatrman-server"
FIXTURES="$CHART/fixtures"
GOLDEN="$CHART/golden"
RELEASE="tatrman-server"
NAMESPACE="ttr-server"
FIXTURE_NAMES=(minimal full dark-geo lexicon config-fragment lexicon-multi)

MODE="regen"
[ "${1:-}" = "--check" ] && MODE="check"

# Ensure subchart deps are vendored (offline, idempotent).
scripts/helm-deps.sh

render() {
  local name="$1"
  helm template "$RELEASE" "$CHART" \
    --namespace "$NAMESPACE" \
    -f "$FIXTURES/values-$name.yaml"
}

mkdir -p "$GOLDEN"
rc=0
for name in "${FIXTURE_NAMES[@]}"; do
  golden_file="$GOLDEN/$name.yaml"
  if [ "$MODE" = "regen" ]; then
    render "$name" > "$golden_file"
    echo "✓ wrote $golden_file"
  else
    if [ ! -f "$golden_file" ]; then
      echo "✗ missing golden: $golden_file (run scripts/helm-golden.sh to create)" >&2
      rc=1
      continue
    fi
    if diff -u "$golden_file" <(render "$name") > /tmp/helm-golden-$name.diff 2>&1; then
      echo "✓ $name matches golden"
    else
      echo "✗ $name drifted from golden:" >&2
      cat /tmp/helm-golden-$name.diff >&2
      rc=1
    fi
  fi
done

exit $rc
