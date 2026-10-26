#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The caller-key seed (values `keySeed`): the Job cannot wedge an ArgoCD sync, and a team whose vault key is missing
# fails at once, named. Renders the chart with helm, and runs files/key-seed.sh against a fake psql.
#
#   services/llm-gateway/k8s/tests/key-seed-test.sh      (CI: the `helm` job)
set -euo pipefail

cd "$(dirname "$0")/.."
rc=0
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
pass() { echo "✓ $1"; }
fail() { echo "✗ $1" >&2; rc=1; }

# --- the rendered Job: a deadline, and the key Secret mounted optional ---------------------------------------------
helm dependency build . >/dev/null 2>&1 || true
rendered=$(helm template gw . --set keySeed.enabled=true --set keySeed.secretStore.name=vault \
  --set 'keySeed.teams[0].team=golem' --set 'keySeed.teams[0].vaultKey=ttrk-golem' \
  --set 'keySeed.teams[1].team=docwh-pipeline' --set 'keySeed.teams[1].vaultKey=ttrk-docwh-pipeline')
job=$(awk '/^kind: Job/{f=1} f' <<<"$rendered")
grep -q 'activeDeadlineSeconds: 600' <<<"$job" && pass "the Job has a deadline (600 s)" || fail "the Job has no activeDeadlineSeconds"
grep -q 'optional: true' <<<"$job" && pass "the caller-keys Secret is mounted optional" || fail "the Secret volume is not optional"
grep -q 'value: "golem=ttrk-golem docwh-pipeline=ttrk-docwh-pipeline"' <<<"$job" && pass "the Job knows each team's vault key" ||
  fail "VAULT_KEYS is not rendered"
custom=$(helm template gw . --set keySeed.enabled=true --set keySeed.secretStore.name=vault \
  --set 'keySeed.teams[0].team=golem' --set 'keySeed.teams[0].vaultKey=ttrk-golem' --set keySeed.deadlineSeconds=120)
grep -q 'activeDeadlineSeconds: 120' <<<"$custom" && pass "the deadline is a value" || fail "keySeed.deadlineSeconds is ignored"

# --- the script: a missing key fails at once, naming the team ------------------------------------------------------
mkdir -p "$work/bin" "$work/keys"
cat >"$work/bin/psql" <<'PSQL'
#!/usr/bin/env bash
# a gateway whose governance knows only golem: virtual_keys exists, docwh-pipeline is not a team yet
args="$*"
case "$args" in
  *string_agg*) echo "golem" ;;
  *to_regclass*) echo "f" ;;
  *) echo "psql must not be reached: $args" >&2; exit 3 ;;
esac
PSQL
chmod +x "$work/bin/psql"
printf 'ttrk-%s' "golemsecret" >"$work/keys/golem"

run() { PATH="$work/bin:$PATH" KEYS_DIR="$work/keys" TEAMS="golem docwh-pipeline" VAULT_KEYS="golem=ttrk-golem docwh-pipeline=ttrk-docwh-pipeline" \
  REVOKE_STALE=true WAIT_TRIES=2 WAIT_SECONDS=0 timeout 20 bash files/key-seed.sh 2>&1; }

out=$(run) && fail "a missing key file exited 0" || true
grep -q "no key for team docwh-pipeline (vault key ttrk-docwh-pipeline)" <<<"$out" && pass "a missing key fails at once, naming the team and its vault key" ||
  fail "the missing-key message: $out"
grep -q "waiting for virtual_keys" <<<"$out" && fail "it waited for the gateway before checking the keys" || true

printf 'ttrk-%s' "docwhpipelinesecret" >"$work/keys/docwh-pipeline"
out=$(run) && fail "a team missing from governance exited 0" || true
grep -q "teams missing from governance: docwh-pipeline" <<<"$out" && pass "the bounded wait fails naming the missing team" ||
  fail "the missing-team message: $out"

exit $rc
