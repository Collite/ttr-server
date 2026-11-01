#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# S1.5: standalone/umbrella CN mappings and legacy overrides. Called after helm-deps by helm-guards.
set -euo pipefail
cd "$(dirname "$0")/.."

# Every existing CN setting; values are deliberately nondefault so wrong wiring is visible.
cn='{"db":{"host":"pg-cn","port":5433,"name":"concepts","user":"steward","passwordSecret":{"name":"cn-db","key":"db-pass"}},"auth":{"issuer":"https://identity.example/realm","jwksUri":"https://identity.example/keys","audience":"cn-api"},"stewardRole":"editor","importerRole":"loader","producers":"kleio=service-kleio","promotion":{"source":"models","inboxDir":"/data/inbox","github":{"repository":"example/model","apiUrl":"https://git.example/api","tokenSecret":{"name":"cn-pr","key":"pr-token"}}},"notifyUrl":"https://notify.example/cn"}'
expected=(
  METADATA_CN_DB_HOST=pg-cn METADATA_CN_DB_PORT=5433 METADATA_CN_DB_NAME=concepts METADATA_CN_DB_USER=steward
  METADATA_CN_AUTH_ISSUER=https://identity.example/realm METADATA_CN_AUTH_JWKS_URI=https://identity.example/keys
  METADATA_CN_AUTH_AUDIENCE=cn-api METADATA_CN_STEWARD_ROLE=editor METADATA_CN_IMPORTER_ROLE=loader
  METADATA_CN_PRODUCERS=kleio=service-kleio METADATA_CN_PROMOTION_SOURCE=models METADATA_CN_INBOX_DIR=/data/inbox
  METADATA_CN_PR_REPOSITORY=example/model METADATA_CN_PR_API_URL=https://git.example/api METADATA_CN_NOTIFY_URL=https://notify.example/cn
)
cn_names=("${expected[@]%%=*}" METADATA_CN_DB_PASSWORD METADATA_CN_PR_TOKEN)

render() {
  local mode="$1"; shift
  if [ "$mode" = standalone ]; then
    helm template cn services/metadata/k8s --show-only templates/deployment.yaml "$@"
  else
    helm template cn helm/tatrman-server -f helm/tatrman-server/fixtures/values-minimal.yaml \
      --show-only charts/metadata/templates/deployment.yaml "$@"
  fi
}
# The deployment's env is intentionally compared as rendered YAML, including quoted strings and Secret refs.
env_block() { sed -n '/^          env:$/,/^          readinessProbe:$/p' <<<"$1"; }
value_is() {
  local line
  line=$(grep -A1 -F -- "- name: $2" <<<"$1" | tail -1)
  line="${line#"${line%%[![:space:]]*}"}"
  [ "$line" = "value: \"$3\"" ] || [ "$line" = "value: $3" ]
}
ref_is() {
  local block
  block=$(grep -A6 -F -- "- name: $2" <<<"$1")
  grep -qF "name: \"$3\"" <<<"$block" && grep -qF "key: \"$4\"" <<<"$block" && grep -q 'optional: true' <<<"$block"
}
unique_names() {
  local name
  for name in "${cn_names[@]}"; do
    [ "$(grep -c -- "- name: $name$" <<<"$1")" = 1 ] || { echo "CN env duplicate/missing: $name" >&2; return 1; }
  done
}

standalone=$(render standalone --set-json "cn=$cn")
umbrella=$(render umbrella --set-json "metadata.cn=$cn")
[ "$(env_block "$standalone")" = "$(env_block "$umbrella")" ]
for item in "${expected[@]}"; do value_is "$umbrella" "${item%%=*}" "${item#*=}"; done
unique_names "$umbrella"
ref_is "$umbrella" METADATA_CN_DB_PASSWORD cn-db db-pass
ref_is "$umbrella" METADATA_CN_PR_TOKEN cn-pr pr-token
echo '✓ metadata: all 17 CN settings render identically standalone/umbrella; both secrets are optional refs'

for mode in standalone umbrella; do
  prefix=''; [ "$mode" = umbrella ] && prefix=metadata.
  off=$(render "$mode")
  ! grep -q 'name: METADATA_CN_' <<<"$off"
  partial=$(render "$mode" --set "${prefix}cn.auth.issuer=https://identity.example/realm")
  value_is "$partial" METADATA_CN_AUTH_ISSUER https://identity.example/realm
  ! grep -q 'name: METADATA_CN_DB_' <<<"$partial"
  # Every generated field/reference must yield to either legacy env mechanism, without duplicates.
  extra=$(python3 -c 'import json,sys; print(json.dumps([{"name":n,"value":"legacy"} for n in sys.argv[1:]]))' "${cn_names[@]}")
  overridden=$(render "$mode" --set-json "${prefix}cn=$cn" --set-json "${prefix}extraEnv=$extra")
  unique_names "$overridden"
  for name in "${cn_names[@]}"; do value_is "$overridden" "$name" legacy; done
  secrets=$(python3 -c 'import json,sys; print(json.dumps([{"name":n,"secretName":"legacy-cn","secretKey":n,"optional":True} for n in sys.argv[1:]]))' "${cn_names[@]}")
  overridden=$(render "$mode" --set-json "${prefix}cn=$cn" --set-json "${prefix}secretEnv=$secrets")
  unique_names "$overridden"
  for name in "${cn_names[@]}"; do
    # Legacy secretEnv preserves its pre-existing unquoted name/key rendering.
    block=$(grep -A6 -F -- "- name: $name" <<<"$overridden")
    grep -q 'name: legacy-cn$' <<<"$block"
    grep -q "key: $name$" <<<"$block"
    grep -q 'optional: true' <<<"$block"
  done
  echo "✓ metadata ($mode): default off; partial config; all legacy extraEnv/secretEnv overrides unique"
done
