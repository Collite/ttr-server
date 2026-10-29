# SPDX-License-Identifier: Apache-2.0
import httpx
import pytest
import respx

from docproc.sdk.models import Processor, ProcessorRole
from docproc.sdk.pipeline import PipelineClient


@respx.mock
def test_immutable_processor_and_registry_cached() -> None:
    p = Processor(
        family="chunk",
        version="1",
        version_hash="sha256:test",
        kind="chunk",
        host="docproc",
        role=ProcessorRole.ROLE_PRIMARY,
    )
    route = respx.get("http://pipeline/v1/processors/chunk", params={"versionHash": "sha256:test"}).mock(
        return_value=httpx.Response(200, json=p.dump())
    )
    registry = respx.get("http://pipeline/v1/registry/1").mock(return_value=httpx.Response(200, json={"types": []}))
    listing = respx.get("http://pipeline/v1/processors").mock(
        return_value=httpx.Response(200, json={"processors": [p.dump(), {"host": "pipeline"}]})
    )
    client = PipelineClient("http://pipeline")
    assert client.processor("chunk", "sha256:test") == p
    client.processor("chunk", "sha256:test").family = "mutation"
    assert client.processor("chunk", "sha256:test").family == "chunk" and route.call_count == 1
    client.registry("1")["types"].append("mutation")
    assert client.registry("1") == {"types": []} and registry.call_count == 1
    assert client.hosted_processors() == [p] and listing.called


@respx.mock
def test_wrong_hash_refused() -> None:
    respx.get("http://pipeline/v1/processors/chunk").mock(return_value=httpx.Response(200, json={"family": "chunk"}))
    with pytest.raises(ValueError, match="immutable hash"):
        PipelineClient("http://pipeline").processor("chunk", "hash")
