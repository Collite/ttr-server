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

# BI (C-6, review-123 R4) — receipts are best effort: their password Secret is optional, so a missing one costs
# receipts, not the door (a required secretKeyRef holds the container in CreateContainerConfigError).
receipts=$(helm template tatrman-server "$CHART" --namespace ttr-server -f "$CHART/fixtures/values-minimal.yaml" \
  --set query.flight.enabled=true --set query.flight.auth.issuer=https://keycloak.example.com/realms/r \
  --set query.flight.receipts.db.host=pg --set query.flight.receipts.db.passwordSecret.name=pg-query \
  | grep -A6 'name: QUERY_RECEIPTS_DB_PASSWORD' || true)
if grep -q 'optional: true' <<<"$receipts"; then
  echo "✓ query: the receipts password Secret is optional"
else
  echo "✗ query: the receipts password secretKeyRef is required — a missing Secret stops the door:" >&2
  echo "$receipts" >&2
  rc=1
fi

# #261 / review-184 — the world requirement must remain an opt-in chart setting.
worlds_default=$(helm template tatrman-server "$CHART" --namespace ttr-server \
  -f "$CHART/fixtures/values-minimal.yaml")
worlds_on=$(helm template tatrman-server "$CHART" --namespace ttr-server \
  -f "$CHART/fixtures/values-minimal.yaml" --set query.requireWorld=true)
if ! grep -q 'name: QUERY_REQUIRE_WORLD' <<<"$worlds_default" && \
  grep -A1 'name: QUERY_REQUIRE_WORLD' <<<"$worlds_on" | grep -q 'value: "true"'; then
  echo "✓ query: requireWorld is off by default and emits true only when opted in"
else
  echo "✗ query: requireWorld environment does not match the opt-in setting" >&2
  rc=1
fi

exit $rc
