# SPDX-License-Identifier: Apache-2.0
import json
from pathlib import Path

import pytest

from ttr_connect_openmetadata.export_reader import DirSource
from ttr_connect_openmetadata.model import Export

FIXTURES = Path(__file__).parent / "fixtures"


def pytest_addoption(parser: pytest.Parser) -> None:
    parser.addoption("--update-goldens", action="store_true", help="update the full plan oracle")


@pytest.fixture
def export() -> Export:
    return DirSource(FIXTURES / "export").read()


def export_with_world(tmp_path: Path, world: str) -> Path:
    changed = tmp_path / "changed-export"
    changed.mkdir()
    matched = False
    for name in ("graph.json", "defs.json", "cn.ttl", ".snapshot"):
        text = (FIXTURES / "export" / name).read_text()
        if name.endswith(".json"):
            before = '"world":"northline"'
            matched |= before in text
            text = text.replace(before, '"world":' + json.dumps(world))
        (changed / name).write_text(text)
    assert matched, "export fixture world replacement did not match"
    return changed
