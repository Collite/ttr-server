# SPDX-License-Identifier: Apache-2.0
"""The frozen Kotlin/Python seam: file names and bytes, plus canonical content."""

import json
import unicodedata
from pathlib import Path

import pytest

from docproc.sdk.models import Processor, ProcessorRole, canonical
from tests.conftest import WORKER, kantheon_dir

FIXTURES = WORKER / "tests/fixtures"


def files(root: Path) -> dict[str, bytes]:
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in root.rglob("*") if p.is_file()}


@pytest.mark.parametrize(
    "name", ["packs/hartland-mini", "schemas", "types", "when-cases.json", "chunk-validators.json"]
)
def test_peer_identity(name: str) -> None:
    peer = kantheon_dir()
    if peer is None:
        pytest.skip("no kantheon checkout")
    source = peer / "shared/proto/fixtures/dx" / name
    if not source.exists():
        pytest.skip(f"P1 has not supplied shared/proto/fixtures/dx/{name}")
    target = FIXTURES / (name if source.is_dir() else f"contracts/{name}")
    a = files(source) if source.is_dir() else {name: source.read_bytes()}
    b = files(target) if target.is_dir() else {name: target.read_bytes()}
    assert a == b, f"fixture drift: {sorted(k for k in a.keys() | b.keys() if a.get(k) != b.get(k))}"
    if name == "types":
        assert b == files(WORKER / "src/docproc/_data/types"), "packaged engine registry drift"


def test_pack_canonical_and_nfc() -> None:
    pack = FIXTURES / "packs/hartland-mini"
    for path in pack.rglob("*.json"):
        assert path.read_text() == canonical(json.loads(path.read_text())), path
    for path in (pack / "documents").iterdir():
        assert unicodedata.is_normalized("NFC", path.read_text()), path


def test_packaged_schemas_identical() -> None:
    assert files(FIXTURES / "schemas") == files(WORKER / "src/docproc/_data/schemas")


def test_processor_strict_roundtrip() -> None:
    import yaml

    text = (FIXTURES / "packs/hartland-mini/processors/chunk-recursive.yaml").read_text()
    manifest = yaml.safe_load(text)
    p = Processor(
        family=manifest["family"],
        version=str(manifest["version"]),
        version_hash="sha256:test",
        kind=manifest["kind"],
        host=manifest["host"],
        role=ProcessorRole.ROLE_PRIMARY,
        manifest_yaml=text,
    )
    assert Processor.strict(p.dump()) == p
