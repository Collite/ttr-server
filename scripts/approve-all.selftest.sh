#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Self-test for scripts/approve-all.sh (S2.3 T4, D-184): the request bodies it derives from a `GET /automation/`
# descriptor, and how it reports the dev route's answers — through a fake `curl` on PATH, no service needed.
set -euo pipefail

here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
script="$here/approve-all.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
fail() { echo "approve-all.selftest: FAIL — $*" >&2; exit 1; }

cat >"$work/descriptor.json" <<'EOF'
{
  "recipes": [
    {"name": "release_order", "fingerprints": {"text": "t1", "values": "v1", "labels": "l1"}},
    {"name": "no_golden", "fingerprints": {"text": "t2", "values": "v2", "labels": ""}}
  ],
  "programs": [{"name": "release_on_placement", "fingerprint": "p1"}],
  "deployments": [{"name": "release_native_event", "fingerprint": "d1"}]
}
EOF

# 1. --dry-run: two approvals per recipe (recipe + values), one per program and deployment; nothing sent.
dry=$(bash "$script" --dry-run --descriptor "$work/descriptor.json" --note seed)
[ "$(wc -l <<<"$dry" | tr -d ' ')" = 6 ] || fail "dry run: expected 6 bodies, got: $dry"
grep -qx '{"objectKind":"recipe","object":"release_order","fps":{"text":"t1","values":"v1"},"kind":"recipe","note":"seed"}' <<<"$dry" ||
  fail "dry run: recipe body"
grep -qx '{"objectKind":"recipe","object":"release_order","fps":{"text":"t1","values":"v1"},"kind":"values","note":"seed"}' <<<"$dry" ||
  fail "dry run: values body"
grep -qx '{"objectKind":"program","object":"release_on_placement","kind":"program","fps":{"fp":"p1"},"note":"seed"}' <<<"$dry" ||
  fail "dry run: program body"
grep -qx '{"objectKind":"deployment","object":"release_native_event","kind":"deployment","fps":{"fp":"d1"},"note":"seed"}' <<<"$dry" ||
  fail "dry run: deployment body"

# A fake curl: GET /automation/ serves the descriptor; the POST answers 201, or 409 golden-required for `no_golden`
# (kind recipe), or $FAKE_STATUS for every POST when set. Every POST body is logged.
mkdir -p "$work/bin"
cat >"$work/bin/curl" <<'EOF'
#!/usr/bin/env bash
out=""; data=""; post=0; url=""
while [ $# -gt 0 ]; do
  case "$1" in
    -o) out=$2; shift 2 ;;
    -w | -H) shift 2 ;;
    --data) data=$2; shift 2 ;;
    -X) [ "$2" = POST ] && post=1; shift 2 ;;
    -*) shift ;;
    *) url=$1; shift ;;
  esac
done
if [ "$post" = 0 ]; then cat "$FAKE_DESCRIPTOR"; exit 0; fi
echo "$data" >>"$FAKE_LOG"
case "$url" in */automation/dev/approvals) ;; *) echo "unexpected url $url" >&2; exit 9 ;; esac
if [ -n "${FAKE_STATUS:-}" ]; then echo '{}' >"$out"; printf '%s' "$FAKE_STATUS"; exit 0; fi
if [[ "$data" == *'"object":"no_golden"'*'"kind":"recipe"'* ]]; then
  echo '{"error":{"code":"golden-required","message":"no golden"}}' >"$out"; printf 409
else
  echo '{"id":"00000000-0000-0000-0000-000000000001"}' >"$out"; printf 201
fi
EOF
chmod +x "$work/bin/curl"
export FAKE_DESCRIPTOR="$work/descriptor.json" FAKE_LOG="$work/posts.log"
export AUTOMATION_URL="https://automation.test/" AUTOMATION_TOKEN="tok"

# 2. Live: five approved, `no_golden`'s recipe approval refused 409 golden-required; exit 1 lists it.
: >"$FAKE_LOG"
set +e
out=$(PATH="$work/bin:$PATH" bash "$script" 2>&1)
rc=$?
set -e
[ "$rc" = 1 ] || fail "golden-required: expected exit 1, got $rc: $out"
[ "$(wc -l <"$FAKE_LOG" | tr -d ' ')" = 6 ] || fail "golden-required: expected 6 POSTs"
grep -q 'approve-all: 5 approved, 1 refused' <<<"$out" || fail "golden-required: summary: $out"
grep -q 'recipe no_golden (recipe): 409 golden-required' <<<"$out" || fail "golden-required: refusal line: $out"

# 3. Dev routes off: the first 404 stops the run (one POST), exit 1, and says why.
: >"$FAKE_LOG"
set +e
out=$(FAKE_STATUS=404 PATH="$work/bin:$PATH" bash "$script" 2>&1)
rc=$?
set -e
[ "$rc" = 1 ] || fail "404: expected exit 1, got $rc"
[ "$(wc -l <"$FAKE_LOG" | tr -d ' ')" = 1 ] || fail "404: expected one POST before stopping"
grep -q 'AUTOMATION_DEV_ROUTES=true is needed' <<<"$out" || fail "404: reason: $out"

# 4. Without URL/token (and not a dry run from a file) it refuses with exit 2 and sends nothing.
set +e
AUTOMATION_URL="" AUTOMATION_TOKEN="" PATH="$work/bin:$PATH" bash "$script" >/dev/null 2>&1
rc=$?
set -e
[ "$rc" = 2 ] || fail "no environment: expected exit 2, got $rc"

echo "approve-all.selftest: PASS"
