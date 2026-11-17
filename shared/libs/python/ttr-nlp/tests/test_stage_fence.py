# SPDX-License-Identifier: Apache-2.0
"""OL-C10 — the fence around the rule engine.

Nothing outside `ttrnlp/rules/` and the pack half of `ttrnlp/packs/` imports
`ttrnlp.rules`; the loader, the CLI and the stage group reach it through
`ttrnlp.stages` only. The pack half (OL-C32) is the model cross-check in
`packs/validate.py`, which leaves with `rules/` at W4. A source check, so a
type-only import counts too: at W4 the module is gone and it would not resolve.
"""

from __future__ import annotations

import ast
import pathlib
import tomllib

SRC = pathlib.Path(__file__).resolve().parents[1] / "src" / "ttrnlp"
PYPROJECT = pathlib.Path(__file__).resolve().parents[1] / "pyproject.toml"

#: Relative to `src/ttrnlp`: the pack half of `ttrnlp.packs` (OL-C32).
PACK_HALF = {"packs/validate.py"}


def imports_of(path: pathlib.Path) -> list[tuple[int, str]]:
    found: list[tuple[int, str]] = []
    for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
        if isinstance(node, ast.Import):
            found.extend((node.lineno, alias.name) for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.level == 0 and node.module:
            found.append((node.lineno, node.module))
            found.extend((node.lineno, f"{node.module}.{a.name}") for a in node.names)
    return found


def test_nothing_outside_the_rule_engine_imports_it():
    offenders = []
    for path in sorted(SRC.rglob("*.py")):
        rel = path.relative_to(SRC).as_posix()
        if rel.startswith("rules/") or rel in PACK_HALF:
            continue
        for line, name in imports_of(path):
            if name == "ttrnlp.rules" or name.startswith("ttrnlp.rules."):
                offenders.append(f"{rel}:{line} imports {name}")
    assert offenders == [], (
        "reach the rule engine through ttrnlp.stages:\n" + "\n".join(offenders)
    )


def test_the_fence_sees_an_import_when_there_is_one():
    """The guard must be able to fail: the loader on master imported it."""
    planted = ast.parse(
        "from ttrnlp.rules import build_pack\nimport ttrnlp.rules.compiler\n"
    )
    names = {n.module for n in ast.walk(planted) if isinstance(n, ast.ImportFrom)} | {
        a.name for n in ast.walk(planted) if isinstance(n, ast.Import) for a in n.names
    }
    assert names == {"ttrnlp.rules", "ttrnlp.rules.compiler"}


def test_the_rule_engine_is_registered_through_the_group():
    eps = tomllib.loads(PYPROJECT.read_text())["project"]["entry-points"]
    assert eps["ttrnlp.stages"] == {"rules": "ttrnlp.rules.stage:RulesStage"}
