#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Runs inside the actual release image, on the disposable compose network.
set -eu
umask 077
printf '%s\n' "$OM_TOKEN" > /tmp/bot-token
for pass in 1 2; do
    ttr-connect-openmetadata sync --export-dir /export --world northline \
        --om-url http://openmetadata-server:8585/api --om-token-file /tmp/bot-token \
        --studio-url https://studio.example --state-file /tmp/om-state.json \
        > "/tmp/summary-$pass.json"
    cat "/tmp/summary-$pass.json"
done
python - <<'PYTHON'
import json
from pathlib import Path
summary = json.loads(Path('/tmp/summary-2.json').read_text())
assert summary['snapshot'] == 'fixture-1'
assert all(summary[k] == 0 for k in ('created', 'updated', 'deprecated', 'linked', 'warnings')), summary
PYTHON
