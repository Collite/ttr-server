# SPDX-License-Identifier: Apache-2.0
import shutil
from pathlib import Path

import pytest

from docproc.runtime.model_hash import model_content_hash, verify_pack
from tests.fakes import PACK


def test_px_hash_and_generated_files(tmp_path: Path) -> None:
    verify_pack(PACK)
    assert (
        model_content_hash(PACK / "model") == "sha256:cac34228f17c5e9b5961dd0ff41cf911a70aec077396b8d12f01b804583d7071"
    )
    target = tmp_path / "pack"
    shutil.copytree(PACK, target)
    (target / "model/ignore.layout").write_text("ignored")
    verify_pack(target)
    (target / "types/generated/Record.review.schema.json").write_text("{}")
    with pytest.raises(ValueError, match="generated-drift"):
        verify_pack(target)
    shutil.copyfile(
        PACK / "types/generated/Record.review.schema.json", target / "types/generated/Record.review.schema.json"
    )
    (target / "model/er.ttrm").write_text("changed")
    with pytest.raises(ValueError, match="model-drift"):
        verify_pack(target)
