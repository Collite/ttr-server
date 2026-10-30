# SPDX-License-Identifier: Apache-2.0
"""Generate private NLP/fuzzy gRPC clients from shared proto truth (locked grpcio-tools)."""

from pathlib import Path

from grpc_tools import protoc

ROOT = Path(__file__).resolve().parents[1]
PROTO = ROOT.parents[1] / "shared/proto/src/main/proto"
OUT = ROOT / "src/docproc/_generated"


def main() -> int:
    names = sorted(
        str(p.relative_to(PROTO))
        for group in ("nlp", "fuzzy", "common")
        for p in (PROTO / f"org/tatrman/{group}/v1").glob("*.proto")
    )
    OUT.mkdir(parents=True, exist_ok=True)
    result = protoc.main(
        [
            "grpc_tools.protoc",
            f"-I{PROTO}",
            f"--python_out={OUT}",
            f"--pyi_out={OUT}",
            f"--grpc_python_out={OUT}",
            *names,
        ]
    )
    if result:
        return result
    # Keep this worker's stubs private: never shadow another service's `org` package.
    for path in list(OUT.rglob("*.py")) + list(OUT.rglob("*.pyi")):
        path.write_text(path.read_text().replace("from org.", "from docproc._generated.org."))
    for directory in [OUT, *(p for p in OUT.rglob("*") if p.is_dir())]:
        (directory / "__init__.py").write_text(
            "# SPDX-License-Identifier: Apache-2.0\n# Generated namespace package.\n"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
