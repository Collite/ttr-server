# SPDX-License-Identifier: Apache-2.0
"""golem-py's entry point — one turn, from the command line.

⚑ The OS Golem has **no server surface of its own** at RV-P4, and that is deliberate
rather than unfinished: the conversation door is the platform's (Iris ⇄ the Kotlin
Golem), and what P4 ships is the LOOP — the thing both shells run. A gRPC/HTTP front
here would be a second door nobody has specified.

So the image is a CLI: it drives one turn against a live core and prints the outcome as
JSON. That is exactly what the P4.4 live drill needs, and it keeps the service honest
about what it is.

    uv run python src/main.py --question "Zobraz tržby za rok 2025" --core localhost:7276

**The LLM lane (OL W1c).** The lane's settings come from the `GOLEM_*` environment
(`golem_py.settings.GolemSettings.from_env`): the door (`GOLEM_QUERY_TARGET`,
`GOLEM_QUERY_WORLD`), the gateway (`GOLEM_LLM_TARGET`, `GOLEM_LLM_MODEL`,
`GOLEM_LLM_MODEL_CLASS`), the model slice (`GOLEM_META_TARGET`, `GOLEM_META_PACKAGES`) and
the switch (`GOLEM_LLM_LANE_ENABLED`, default on). The lane is on only when the switch is
on and a gateway and a door are configured (OL-C11). With none of them set, the CLI answers
exactly as it did before W1c. When the lane is on, its configuration is checked BEFORE the
turn (`check_lane_config`), and a bad one exits 2 naming what is wrong.

`--json` prints the envelope rather than the whole outcome. For an answer, that is contracts
§6 plus the lane's fields: SQL, provenance, rows, `llm_handled`. For a refusal or an ask, it
is the outcome without the lattice.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
from dataclasses import asdict
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "generated"))

from golem_py.core_client import LiveCore  # noqa: E402
from golem_py.deps import Deps  # noqa: E402
from golem_py.door_client import LiveSqlDoor  # noqa: E402
from golem_py.errors import ConfigInvalid  # noqa: E402
from golem_py.gate_client import LiveGate  # noqa: E402
from golem_py.graph import run_turn  # noqa: E402
from golem_py.ladder import LadderConfig, load_default  # noqa: E402
from golem_py.lane import check_lane_config, lane_on  # noqa: E402
from golem_py.llm_client import LiveLlmGateway  # noqa: E402
from golem_py.model_slice import LiveModelSlice  # noqa: E402
from golem_py.outputs import Answer, Ask, RefusalWithGaps, TurnOutput  # noqa: E402
from golem_py.settings import GolemSettings  # noqa: E402
from golem_py.state import ResolutionState  # noqa: E402

# Exit code for a configuration that must not run a turn (the `ConfigInvalid` convention).
EXIT_CONFIG = 2


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="golem-py", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--question", required=True, help="the user's question")
    parser.add_argument("--locale", default="cs")
    parser.add_argument(
        "--core", default=None, help="resolver host:port (default: GOLEM_CORE_TARGET)"
    )
    parser.add_argument("--conversation-id", default="golem-py-cli")
    parser.add_argument("--profile", default=None, help="budget profile (default: GOLEM_PROFILE)")
    parser.add_argument("--subject", default="", help="OBO caller subject")
    parser.add_argument(
        "--ladder",
        default=None,
        help="path to a golem-ladder/v1 file (default: GOLEM_LADDER_CONFIG, else the shipped "
        "zero-rung config)",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="print the answer's envelope (or the refusal / ask without its lattice)",
    )
    parser.add_argument("--verbose", action="store_true")
    return parser.parse_args(argv)


def build_deps(settings: GolemSettings, ladder: LadderConfig, core_target: str) -> Deps:
    """The live ports. A lane leg whose target is empty stays None, and with no gateway or
    no door the lane is off (OL-C11). The model slice is a leg of its own: a lane that is on
    with no slice refuses as `LANE_UNAVAILABLE`, rather than answer without the model."""
    return Deps(
        core=LiveCore(core_target),
        gate=LiveGate(core_target),
        ladder=ladder,
        settings=settings,
        door=(
            LiveSqlDoor(settings.query_target, timeout_s=settings.query_timeout_s)
            if settings.query_target
            else None
        ),
        llm=(
            LiveLlmGateway(
                settings.llm_target,
                model=settings.llm_model,
                model_class=settings.llm_model_class,
            )
            if settings.llm_target
            else None
        ),
        model_slice=(
            LiveModelSlice(
                settings.meta_target, settings.meta_packages, timeout_s=settings.meta_timeout_s
            )
            if settings.meta_target and settings.meta_packages
            else None
        ),
    )


def check_startup(deps: Deps) -> None:
    """What must hold before the first turn, or `ConfigInvalid`. A lane that is on needs a
    world for its queries, a model slice, and the config `check_lane_config` reads
    (review-329 F3/F4). Without any of these, every lane turn would refuse in the same way,
    and nothing would say why."""
    if not lane_on(deps):
        return
    if not deps.settings.query_world:
        raise ConfigInvalid("the LLM lane is on and GOLEM_QUERY_WORLD is empty")
    if deps.model_slice is None:
        raise ConfigInvalid(
            "the LLM lane is on and has no model slice: set GOLEM_META_TARGET and "
            "GOLEM_META_PACKAGES, or switch the lane off with GOLEM_LLM_LANE_ENABLED=false"
        )
    check_lane_config(deps.ladder)


def envelope_json(output: TurnOutput) -> dict[str, Any]:
    """`--json`: the envelope of an answer; a refusal or an ask without its lattice."""
    if isinstance(output, Answer):
        out: dict[str, Any] = {"type": "Answer", "llm_invocations": output.llm_invocations}
        if output.envelope is not None:
            env = asdict(output.envelope)
            result = env.pop("result", None) or {}
            env["row_count"] = len(result.get("rows", []))
            env["columns"] = result.get("columns", [])
            env["rows"] = result.get("rows", [])
            env["truncated"] = result.get("truncated", False)
            out |= env
        else:
            out |= {"content": output.content, "gaps_carried": output.gaps_carried}
        return out
    if isinstance(output, RefusalWithGaps):
        return {
            "type": "RefusalWithGaps",
            "reason": output.reason,
            "explanation": output.explanation,
            "gaps": output.gaps,
            "composable_residue": output.composable_residue,
            "llm_invocations": output.lattice.llm_invocations,
        }
    assert isinstance(output, Ask)
    return {"type": "Ask"} | output.model_dump(mode="json", exclude={"lattice"})


def _default(value: Any) -> Any:
    """JSON for what pydantic models and Arrow rows carry: models, dates, decimals."""
    dump = getattr(value, "model_dump", None)
    if callable(dump):
        return dump(mode="json")
    return str(value)


async def _run(args: argparse.Namespace) -> int:
    try:
        settings = GolemSettings.from_env()
        ladder = LadderConfig.load(args.ladder) if args.ladder else load_default()
        deps = build_deps(settings, ladder, args.core or settings.core_target)
        check_startup(deps)
    except ConfigInvalid as exc:
        print(f"golem-py: configuration invalid: {exc}", file=sys.stderr)
        return EXIT_CONFIG
    state = ResolutionState(
        question=args.question,
        locale=args.locale,
        conversation_id=args.conversation_id,
        profile=args.profile or settings.profile,
        caller_subject=args.subject,
    )
    output = await run_turn(state, deps)
    if args.json:
        doc = envelope_json(output)
    else:
        doc = {"type": type(output).__name__, **output.model_dump(mode="json")}
    print(json.dumps(doc, indent=2, ensure_ascii=False, default=_default))
    return 0


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(levelname)s %(message)s",
    )
    return asyncio.run(_run(args))


if __name__ == "__main__":
    raise SystemExit(main())
