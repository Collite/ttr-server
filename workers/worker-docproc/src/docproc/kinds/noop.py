# SPDX-License-Identifier: Apache-2.0
"""Test-only kind; never registered by the production bootstrap."""

from docproc.runtime.registry import KindResult, RunContext
from docproc.sdk.models import Annotation, payload_text


class Noop:
    def run(self, ctx: RunContext) -> KindResult:
        return KindResult(
            annotations=[
                Annotation(unit_id=u.id, type="Verdict", payload_json=payload_text({"rule": "noop", "ok": True}))
                for u in ctx.units.values()
            ]
        )
