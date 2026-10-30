# SPDX-License-Identifier: Apache-2.0
"""A closed function registry. A manifest cannot import or execute an arbitrary symbol."""

from __future__ import annotations

from collections.abc import Callable
from typing import TYPE_CHECKING

from docproc.runtime.registry import KindResult, RunContext

if TYPE_CHECKING:
    from docproc.kinds.consolidate import Consolidator

_FUNCTIONS: dict[str, Callable[[RunContext, Consolidator], KindResult]] = {}


def rule_fn(
    name: str,
) -> Callable[[Callable[[RunContext, Consolidator], KindResult]], Callable[[RunContext, Consolidator], KindResult]]:
    def register(
        fn: Callable[[RunContext, Consolidator], KindResult],
    ) -> Callable[[RunContext, Consolidator], KindResult]:
        if name in _FUNCTIONS:
            raise ValueError("rule/function-duplicate")
        _FUNCTIONS[name] = fn
        return fn

    return register


@rule_fn("consolidate")
def consolidate(ctx: RunContext, engine: Consolidator) -> KindResult:
    return engine.consolidate(ctx)


@rule_fn("normalize")
def normalize(ctx: RunContext, engine: Consolidator) -> KindResult:
    return engine.normalize(ctx)


def run(name: str, ctx: RunContext, engine: Consolidator) -> KindResult:
    prefix = "docproc.kinds.rule_fns."
    if not name.startswith(prefix) or name[len(prefix) :] not in _FUNCTIONS:
        raise ValueError("rule/function-unregistered")
    return _FUNCTIONS[name[len(prefix) :]](ctx, engine)


def registered(name: str) -> bool:
    prefix = "docproc.kinds.rule_fns."
    return name.startswith(prefix) and name[len(prefix) :] in _FUNCTIONS
