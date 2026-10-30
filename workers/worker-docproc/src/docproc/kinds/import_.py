# SPDX-License-Identifier: Apache-2.0
"""Recorded connector facts, retaining connector identity rather than worker identity."""

from __future__ import annotations

import re

from jsonschema import ValidationError

from docproc.kinds.common import verdict
from docproc.runtime.registry import KindResult, RunContext, TypeRegistryClient
from docproc.runtime.validators import span_within_unit
from docproc.sdk.models import payload_text
from docproc.sdk.pipeline import Manager


class Import:
    def __init__(self, manager: Manager, registry: TypeRegistryClient) -> None:
        self.manager, self.registry = manager, registry

    def run(self, ctx: RunContext) -> KindResult:
        if not ctx.item.bundle_ref:
            raise ValueError("import/bundle-ref-required")
        result = KindResult()
        allowed = ctx.manifest["engine"].get("connectors", [])
        for source in self.manager.bundle(ctx.item.bundle_ref):
            try:
                if not re.fullmatch(r"import:[^@\s]+@[^\s]+", source.producer):
                    raise ValueError("bundle fact has no connector/version identity")
                connector = source.producer.removeprefix("import:")
                if allowed and connector not in allowed:
                    raise ValueError("connector not allowed by manifest")
                original = source.payload()
                if not isinstance(original, dict):
                    raise ValueError("bundle payload must be an object")
                payload = {**original, "source": connector.rsplit("@", 1)[0], "recorded": True}

                output = source.model_copy(
                    update={"id": 0, "run_id": "", "producer_version": "", "payload_json": payload_text(payload)},
                    deep=True,
                )
                span_within_unit(output, ctx.units)
                self.registry.schema_for(output.type).validate(payload)
                result.annotations.append(output)
            except (ValueError, KeyError, ValidationError) as exc:
                result.verdicts.append(
                    verdict(
                        source.unit_id if source.unit_id in ctx.units else next(iter(ctx.units)),
                        "import/rejected",
                        f"{exc.validator} at {exc.json_path}" if isinstance(exc, ValidationError) else str(exc),
                    )
                )
        return result
