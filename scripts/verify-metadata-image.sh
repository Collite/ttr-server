#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Run against the disposable helm-install cluster, with reflection enabled by its overlay.
set -euo pipefail

namespace="${1:-ttr-umbrella}"
work_dir="$(mktemp -d)"
forward_pid=""
cleanup() {
  if [ -n "$forward_pid" ]; then
    kill "$forward_pid" 2>/dev/null || true
    wait "$forward_pid" 2>/dev/null || true
  fi
  rm -rf "$work_dir"
}
trap cleanup EXIT

deployment="$(kubectl -n "$namespace" get deployment -l app.kubernetes.io/name=metadata -o jsonpath='{.items[0].metadata.name}')"
image="$(kubectl -n "$namespace" get deployment "$deployment" -o jsonpath='{.spec.template.spec.containers[0].image}')"
case "$image" in
  ghcr.io/collite/metadata:*) ;;
  *) echo "Unexpected metadata image: $image" >&2; exit 1 ;;
esac

# An empty credential directory proves public access even on a logged-in runner.
mkdir "$work_dir/docker"
DOCKER_CONFIG="$work_dir/docker" docker manifest inspect "$image" > "$work_dir/manifest.json"
DOCKER_CONFIG="$work_dir/docker" docker pull "$image"

kubectl -n "$namespace" port-forward --address 127.0.0.1 "deployment/$deployment" 17261:7261 > "$work_dir/port-forward.log" 2>&1 &
forward_pid=$!
ready=false
for attempt in {1..30}; do
  if grpcurl -plaintext -connect-timeout 2 -max-time 5 127.0.0.1:17261 list > "$work_dir/services.txt" 2> "$work_dir/grpcurl.log"; then
    ready=true
    break
  fi
  if ! kill -0 "$forward_pid" 2>/dev/null; then
    cat "$work_dir/port-forward.log" >&2
    exit 1
  fi
  sleep 1
done
if [ "$ready" != true ]; then
  cat "$work_dir/port-forward.log" "$work_dir/grpcurl.log" >&2
  exit 1
fi
cat "$work_dir/services.txt"
grep -Fx 'org.tatrman.meta.v1.MetadataService' "$work_dir/services.txt"
grpcurl -plaintext -max-time 15 -d '{}' 127.0.0.1:17261 org.tatrman.meta.v1.MetadataService/GetStatus
echo "Published metadata image verified: $image"
