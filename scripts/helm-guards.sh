#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Render guards the golden fixtures cannot show: values a chart must REFUSE to render. Each case renders the
# umbrella with one bad setting and expects `helm template` to fail with the chart's own sentence; the default
# renders are the golden check's (scripts/helm-golden.sh).
#
#   scripts/helm-guards.sh       (CI: the `helm` job, after the golden check)
set -euo pipefail

cd "$(dirname "$0")/.."
CHART="helm/tatrman-server"
scripts/helm-deps.sh >/dev/null

rc=0
# expect_refusal <name> <expected message fragment> <helm --set args...>
expect_refusal() {
  local name="$1" expected="$2"
  shift 2
  local out
  if out=$(helm template tatrman-server "$CHART" --namespace ttr-server -f "$CHART/fixtures/values-minimal.yaml" "$@" 2>&1); then
    echo "✗ $name: rendered, but the chart should refuse it" >&2
    rc=1
  elif grep -qF "$expected" <<<"$out"; then
    echo "✓ $name refused"
  else
    echo "✗ $name: refused for another reason:" >&2
    echo "$out" >&2
    rc=1
  fi
}

# BI (C-1) — the Flight edge keeps a statement's ticket in the pod that started it.
expect_refusal "query: Flight edge on two replicas" "the Flight edge is single-replica" \
  --set query.flight.enabled=true --set query.flight.auth.issuer=https://keycloak.example.com/realms/r \
  --set query.replicaCount=2
# …and one replica with the edge on renders.
helm template tatrman-server "$CHART" --namespace ttr-server -f "$CHART/fixtures/values-minimal.yaml" \
  --set query.flight.enabled=true --set query.flight.auth.issuer=https://keycloak.example.com/realms/r \
  >/dev/null && echo "✓ query: Flight edge on one replica renders" || rc=1

exit $rc
