# SPDX-License-Identifier: Apache-2.0
"""Ensure the generated proto stubs exist before test collection.

The `org.tatrman.*.v1` Python stubs live under `generated/` and are gitignored
(regenerated from the shared `.proto` source). Generating them here keeps
`uv run pytest` self-contained — the same bootstrap nlp uses.

⛑ TWO things here are load-bearing on a CLEAN checkout, and it took a red CI run to
find both. They pass forever on a dev machine, where the stubs were generated once.

1. **`gen_proto.py` ends in `raise SystemExit(main())`**, so `runpy.run_path(...,
   run_name="__main__")` raises `SystemExit` even on SUCCESS. Anything written after
   that call inside the same `try` never runs — which is why the invalidation below
   sits in a `finally`.
2. **pytest's `pythonpath` ini option puts `generated/` on `sys.path` BEFORE this
   conftest is imported.** On a clean checkout that directory does not exist yet, so
   CPython caches a null importer for it in `sys.path_importer_cache`, and creating the
   directory a moment later does NOT invalidate that cache. Every subsequent
   `from org.tatrman…` then fails with `ModuleNotFoundError: No module named 'org'` on
   a machine where the files are plainly there.

⚑ `services/nlp/conftest.py` has the same shape and therefore the same latent bug. It
has never been hit because nothing ever ran that suite in CI — see the `golem-py` job's
recon note in `.github/workflows/ci.yml`.
"""

from __future__ import annotations

import importlib
from pathlib import Path

_SERVICE_DIR = Path(__file__).resolve().parent
# The NEWEST stub the generator emits (OL W1c·S1's `query.v1`): a tree generated before it
# lacks this file and has the `org/__init__.py` that shadows the `ttr-plan-proto` wheel, so
# it must regenerate rather than pass the check on an older marker. (The `llm.v1` stubs that
# were the marker until OL W1c·S3a are no longer generated: the gateway is HTTP, D-43.)
_MARKER = _SERVICE_DIR / "generated" / "org" / "tatrman" / "query" / "v1" / "query_pb2_grpc.py"


def _generate() -> None:
    import runpy

    try:
        runpy.run_path(str(_SERVICE_DIR / "scripts" / "gen_proto.py"), run_name="__main__")
    except SystemExit as exc:  # protoc reports success AND failure this way
        # ⛑ …so the CODE is the only thing that separates them, and swallowing it turned a
        # protoc failure into `ModuleNotFoundError: No module named 'org'` — precisely the
        # symptom this bootstrap already cost one red CI run to diagnose, arriving from a
        # different cause. `gen_proto.main()` already prints why; let it through.
        if exc.code:
            raise
    finally:
        # See (1) and (2) above: this MUST run even though the call always raises.
        importlib.invalidate_caches()


if not _MARKER.exists():  # pragma: no cover - one-time bootstrap
    _generate()
