# SPDX-License-Identifier: Apache-2.0
"""Generate golem-py's Python proto stubs (messages + gRPC service stubs).

golem-py owns its `org.tatrman.*.v1` stubs rather than consuming the `shared-proto`
Python package, for the reason nlp already records: that package's Python lane emits
message types only (no gRPC service stub). The canonical source stays the shared
`.proto` tree — this script compiles the files golem-py needs with grpcio-tools'
bundled protoc + grpc_python plugin.

⚑ Clean-room note (PROVENANCE.md): generating stubs from a SHARED proto is contract
conformance, not code reuse. The proto IS the interface both sides implement, and it
is on the MAY-consult list.

**`plan.v1` is not generated here (OL-C12).** Its `.proto` files belong to
`org.tatrman:ttr-plan-proto`, whose Python lane is the published `ttr-plan-proto` wheel
pinned in `pyproject.toml`. The wheel ships `_pb2` modules only, so protoc reads those
files' descriptors from the INSTALLED wheel (`--descriptor_set_in`) and the generated
stubs import `org.tatrman.plan.v1.*_pb2` from it at run time. For that import to work,
`generated/org` and `generated/org/tatrman` stay PEP 420 namespace packages — an
`__init__.py` at either level would make this tree shadow the wheel's (the same rule
`shared/proto`'s Python lane follows).

Run:  uv run python scripts/gen_proto.py
Output: services/golem-py/generated/org/tatrman/{resolver,nlp,common,meta,worker,
        validate,llm}/v1/*.py(i)
"""

from __future__ import annotations

import importlib
import sys
import tempfile
from pathlib import Path

import grpc_tools
from google.protobuf import descriptor_pb2
from grpc_tools import protoc

SERVICE_DIR = Path(__file__).resolve().parent.parent
# services/golem-py -> repo root (tatrman-server)
REPO_ROOT = SERVICE_DIR.parent.parent
PROTO_ROOT = REPO_ROOT / "shared" / "proto" / "src" / "main" / "proto"
# `llm.v1` lives in its own Gradle module (`:shared:proto-llm`), so its own root.
LLM_PROTO_ROOT = REPO_ROOT / "shared" / "proto-llm" / "src" / "main" / "proto"
# `google/protobuf/struct.proto` (imported by llm_gateway.proto) ships with grpcio-tools.
WELL_KNOWN_ROOT = Path(grpc_tools.__file__).resolve().parent / "_proto"
OUT_DIR = SERVICE_DIR / "generated"

# resolver.proto is the door (`Resolve` + the RV-P2.4 `Gate` sibling); it imports
# nlp.proto for the parse passthrough, which in turn imports common. Imported files
# must be generated too — protoc only emits for the files it is given.
#
# OL W1c·S1: `meta.v1` (the model slice, OL-C14), `llm.v1` (the gateway, OL-C13) and
# `worker.v1` (+ its `validate.v1` import) — the `ResultBatch` the query door streams
# (OL-C12). ⚑ `query.v1` itself is NOT here: query.proto imports
# `translate/v1/translator.proto`, which no published `ttr-plan-proto` wheel carries
# (see the stage list's "Found while doing").
PROTOS = [
    "org/tatrman/resolver/v1/resolver.proto",
    "org/tatrman/nlp/v1/nlp.proto",
    "org/tatrman/common/v1/response_message.proto",
    "org/tatrman/meta/v1/meta.proto",
    "org/tatrman/validate/v1/validate.proto",
    "org/tatrman/worker/v1/worker.proto",
    "org/tatrman/llm/v1/llm_gateway.proto",
]

# The wheel's modules, dependencies before dependants (protoc reads the set in order).
PLAN_MODULES = [
    "org.tatrman.plan.v1.parameters_pb2",
    "org.tatrman.plan.v1.plan_pb2",
    "org.tatrman.plan.v1.context_pb2",
]

# Levels shared with the `ttr-plan-proto` wheel: namespace packages, never regular ones.
_NAMESPACE_LEVELS = 2  # org, org/tatrman


def _clear_namespace_inits(out_dir: Path) -> None:
    """A tree generated before OL W1c·S1 has `__init__.py` at `org/` and
    `org/tatrman/`; left in place they shadow the wheel's `org.tatrman.plan`."""
    for level in (out_dir / "org", out_dir / "org" / "tatrman"):
        init = level / "__init__.py"
        if init.exists():
            init.unlink()
    importlib.invalidate_caches()


def _ensure_package_inits(out_dir: Path) -> None:
    """protoc emits per-package dirs but no `__init__.py`; add them below the shared
    namespace levels so the `org.tatrman.*.v1` packages import cleanly under pytest."""
    for proto in PROTOS:
        parts = Path(proto).parent.parts  # ('org','tatrman','resolver','v1')
        cur = out_dir
        for depth, part in enumerate(parts, start=1):
            cur = cur / part
            if depth <= _NAMESPACE_LEVELS:
                continue
            init = cur / "__init__.py"
            if not init.exists():
                init.write_text("# generated namespace package\n", encoding="utf-8")


def _plan_descriptor_set() -> bytes:
    """The installed wheel's `plan.v1` files as a `FileDescriptorSet` for protoc."""
    fds = descriptor_pb2.FileDescriptorSet()
    for name in PLAN_MODULES:
        module = importlib.import_module(name)
        proto = descriptor_pb2.FileDescriptorProto()
        module.DESCRIPTOR.CopyToProto(proto)
        fds.file.append(proto)
    return fds.SerializeToString()


def main() -> int:
    for root in (PROTO_ROOT, LLM_PROTO_ROOT):
        if not root.exists():
            print(f"proto root not found: {root}", file=sys.stderr)
            return 2
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    _clear_namespace_inits(OUT_DIR)

    try:
        plan_set = _plan_descriptor_set()
    except ImportError as exc:
        print(
            f"the ttr-plan-proto wheel is not importable ({exc}) — run `uv sync`",
            file=sys.stderr,
        )
        return 3

    with tempfile.TemporaryDirectory() as tmp:
        plan_set_path = Path(tmp) / "ttr-plan-proto.pb"
        plan_set_path.write_bytes(plan_set)
        args = [
            "grpc_tools.protoc",
            f"-I{PROTO_ROOT}",
            f"-I{LLM_PROTO_ROOT}",
            f"-I{WELL_KNOWN_ROOT}",
            f"--descriptor_set_in={plan_set_path}",
            f"--python_out={OUT_DIR}",
            f"--pyi_out={OUT_DIR}",
            f"--grpc_python_out={OUT_DIR}",
            *PROTOS,
        ]
        rc: int = protoc.main(args)
    if rc != 0:
        print(f"protoc failed with code {rc}", file=sys.stderr)
        return rc
    _ensure_package_inits(OUT_DIR)
    importlib.invalidate_caches()
    print(f"generated proto stubs → {OUT_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
