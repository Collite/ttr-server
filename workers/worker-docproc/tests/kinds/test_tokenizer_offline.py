# SPDX-License-Identifier: Apache-2.0
import hashlib
from importlib.resources import files
from pathlib import Path

import pytest

from docproc.kinds.chunk.tokens import ENCODING_FILE, ENCODING_SHA256, Tokenizer, encoding
from tests.conftest import WORKER


def test_vendored_encoding_is_the_packaged_runtime_data() -> None:
    expected = (WORKER / "tests/fixtures/tiktoken" / ENCODING_FILE).read_bytes()
    assert files("docproc").joinpath("_data/tiktoken", ENCODING_FILE).read_bytes() == expected
    assert hashlib.sha256(expected).hexdigest() == ENCODING_SHA256
    assert Tokenizer({}).count("Hello world") == 2


def test_missing_or_corrupt_cache_fails_without_attempting_network(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import urllib.request

    def forbidden(*args: object, **kwargs: object) -> None:
        raise AssertionError("tokenizer attempted network access")

    monkeypatch.setattr(urllib.request, "urlopen", forbidden)
    monkeypatch.setenv("TIKTOKEN_CACHE_DIR", str(tmp_path))
    encoding.cache_clear()
    try:
        for corrupt in (False, True):
            if corrupt:
                (tmp_path / ENCODING_FILE).write_text("corrupt")
            with pytest.raises(ValueError, match="tokenizer-data-missing-or-corrupt"):
                Tokenizer({})
    finally:
        encoding.cache_clear()
