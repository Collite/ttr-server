# SPDX-License-Identifier: Apache-2.0
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
