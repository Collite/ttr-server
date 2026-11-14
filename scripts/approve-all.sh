#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# approve-all.sh — dev / bp-dsk approvals seeding (AU-server S2.3 T4; D-184, contracts Amendment 2026-11-12).
#
# Approves every served object's CURRENT fingerprints as the caller, through the automation service's dev route
# `POST /automation/dev/approvals` (mounted only with AUTOMATION_DEV_ROUTES=true; the caller needs role.ops in the
# object's worlds). It goes through the service's own approve(), so D-89 holds: a recipe with no golden case
# (tests/) answers `409 golden-required` and stays unapproved — its deployments stay stale, as they should.
# Never a production tool: production approvals are the ops console's (P4).
#
# Per served recipe: one `recipe` and one `values` approval; per program and per deployment: one approval of its fp.
# Re-running is harmless (an identical approval is answered as is).
#
# Usage:
#   AUTOMATION_URL=https://automation.example AUTOMATION_TOKEN=<bearer> scripts/approve-all.sh [--note TEXT]
#   scripts/approve-all.sh --dry-run [--descriptor FILE]   # print the request bodies, send nothing
#
# --descriptor FILE  read the `GET /automation/` descriptor from FILE instead of the service
# Exit: 0 all approved · 1 at least one approval refused (listed) · 2 usage / environment error.
set -euo pipefail

usage() { sed -n '3,19p' "$0" | sed 's/^# \{0,1\}//'; }

url=${AUTOMATION_URL:-}
url=${url%/}
token=${AUTOMATION_TOKEN:-}
note="approve-all.sh (dev seeding, D-184)"
dry=0
descriptor=""
while [ $# -gt 0 ]; do
  case "$1" in
    --note) note=${2:?--note needs a value}; shift 2 ;;
    --dry-run) dry=1; shift ;;
    --descriptor) descriptor=${2:?--descriptor needs a file}; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) echo "approve-all: unknown argument $1" >&2; exit 2 ;;
  esac
done
command -v jq >/dev/null || { echo "approve-all: jq is required" >&2; exit 2; }
if [ "$dry" -eq 0 ] || [ -z "$descriptor" ]; then
  if [ -z "$url" ] || [ -z "$token" ]; then
    echo "approve-all: set AUTOMATION_URL and AUTOMATION_TOKEN (or use --dry-run --descriptor FILE)" >&2
    exit 2
  fi
fi

if [ -n "$descriptor" ]; then
  desc=$(cat -- "$descriptor")
else
  desc=$(curl -fsS -H "Authorization: Bearer $token" "$url/automation/") || {
    echo "approve-all: GET $url/automation/ failed" >&2
    exit 2
  }
fi

bodies=$(jq -c --arg note "$note" '
  (
    (.recipes // [])[]
    | {objectKind: "recipe", object: .name, fps: {text: .fingerprints.text, values: .fingerprints.values}} as $r
    | ($r + {kind: "recipe"}), ($r + {kind: "values"})
  ),
  ((.programs // [])[] | {objectKind: "program", object: .name, kind: "program", fps: {fp: .fingerprint}}),
  ((.deployments // [])[] | {objectKind: "deployment", object: .name, kind: "deployment", fps: {fp: .fingerprint}})
  | . + {note: $note}' <<<"$desc") || {
  echo "approve-all: the descriptor is not the GET /automation/ shape" >&2
  exit 2
}

if [ -z "$bodies" ]; then
  echo "approve-all: nothing is served"
  exit 0
fi

if [ "$dry" -eq 1 ]; then
  printf '%s\n' "$bodies"
  exit 0
fi

response=$(mktemp)
trap 'rm -f "$response"' EXIT
approved=0
refused=()
while IFS= read -r body; do
  what=$(jq -r '"\(.objectKind) \(.object) (\(.kind))"' <<<"$body")
  status=$(curl -sS -o "$response" -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    --data "$body" "$url/automation/dev/approvals") || status=000
  if [ "$status" = 201 ]; then
    approved=$((approved + 1))
    echo "approved  $what  $(jq -r '.id // ""' "$response" 2>/dev/null || true)"
  else
    code=$(jq -r '.error.code // empty' "$response" 2>/dev/null || true)
    case "$status" in
      404) reason="404 — the dev routes are off (AUTOMATION_DEV_ROUTES=true is needed)" ;;
      000) reason="no answer from $url" ;;
      *) reason="$status ${code:-$(head -c 200 "$response")}" ;;
    esac
    refused+=("$what: $reason")
    echo "REFUSED   $what  $reason"
    # 404 and 403 are the same for every object: stop instead of repeating them.
    if [ "$status" = 404 ] || [ "$status" = 403 ] || [ "$status" = 401 ]; then break; fi
  fi
done <<<"$bodies"

echo "approve-all: $approved approved, ${#refused[@]} refused"
if [ "${#refused[@]}" -gt 0 ]; then
  printf '  %s\n' "${refused[@]}"
  exit 1
fi
