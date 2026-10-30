# SPDX-License-Identifier: Apache-2.0
"""Rule DSL or registered deterministic functions."""

from docproc.kinds import rule_fns
from docproc.kinds.consolidate import Consolidator
from docproc.kinds.rule_dsl import RuleDsl
from docproc.runtime.registry import KindResult, RunContext, TypeRegistryClient
from docproc.sdk.store import Warehouse


class Rule:
    def __init__(self, warehouse: Warehouse, registry: TypeRegistryClient, pack_path: str = "") -> None:
        self.dsl = RuleDsl(registry, pack_path)
        self.consolidator = Consolidator(warehouse, registry, pack_path)

    def run(self, ctx: RunContext) -> KindResult:
        engine = ctx.manifest["engine"]
        if ("rules" in engine) == ("function" in engine):
            raise ValueError("rule/exactly-one-engine-required")
        if "rules" in engine:
            return self.dsl.run(ctx, engine["rules"])
        return rule_fns.run(engine["function"], ctx, self.consolidator)
