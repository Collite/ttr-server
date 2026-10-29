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


@respx.mock
def test_bundle_reference_is_escaped_and_facts_decoded() -> None:
    from docproc.sdk.models import Annotation

    route = respx.get("http://pipeline/v1/bundles/a%2Fb%3Ac").mock(
        return_value=httpx.Response(
            200,
            json={
                "annotations": [
                    {
                        "unitId": 2,
                        "type": "Import",
                        "producer": "import:json@1",
                        "payloadJson": '{"source":"json","recorded":true}',
                    }
                ]
            },
        )
    )
    with httpx.Client(base_url="http://pipeline") as http:
        result = PipelineClient("http://pipeline", client=http).bundle("a/b:c")
    assert route.called and result[0] == Annotation(
        unit_id=2, type="Import", producer="import:json@1", payload_json='{"source":"json","recorded":true}'
    )


@respx.mock
def test_plan_pinned_graph_registry_version() -> None:
    route = respx.get("http://pipeline/v1/plans/a%2Fb/graph").mock(
        return_value=httpx.Response(200, json={"registryVersion": "v2"})
    )
    assert PipelineClient("http://pipeline").graph("a/b") == {"registryVersion": "v2"}
    assert route.called
