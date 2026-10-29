# SPDX-License-Identifier: Apache-2.0
import pytest
from jsonschema import ValidationError

from docproc.kinds.noop import Noop
from docproc.runtime.registry import KindRegistry, TypeRegistryClient, UnsupportedKind
from tests.fakes import PackManager


def test_all_pack_manifests_validate() -> None:
    registry = KindRegistry()
    for processor in PackManager().processors.values():
        registry.manifest(processor, hosted=False)


def test_wrong_host_unknown_key_and_unknown_kind_refused() -> None:
    processor = next(p for p in PackManager().processors.values() if p.kind == "chunk")
    registry = KindRegistry()
    with pytest.raises(UnsupportedKind, match="kind/unsupported"):
        registry.manifest(processor)
    registry.register("chunk", Noop())
    bad = processor.model_copy(
        update={"host": "pipeline", "manifest_yaml": processor.manifest_yaml.replace("host: docproc", "host: pipeline")}
    )
    with pytest.raises(UnsupportedKind):
        registry.manifest(bad)
    bad = processor.model_copy(update={"manifest_yaml": processor.manifest_yaml + "typo: true\n"})
    with pytest.raises(ValidationError):
        registry.manifest(bad)


def test_registry_parameterized_and_import_resolution() -> None:
    manager = PackManager()
    manager.extra_types = [{"key": key, "schema": {"type": "object"}} for key in ("Mention", "Import")]
    registry = TypeRegistryClient(manager)
    registry.load("1")
    assert registry.loaded
    assert registry.schema_for("Mention[item]") is registry.schema_for("Mention")
    assert registry.schema_for("Import.review") is registry.schema_for("Import")
    schema = registry.schema_for("Statement[review]").schema
    assert isinstance(schema, dict)
    assert schema["$id"] == "ttr:types/Statement.review"


@pytest.mark.parametrize("strategy", ["semantic", "summary-prefix"])
def test_costly_chunk_strategies_are_not_hosted_in_p3(strategy: str) -> None:
    import yaml

    from docproc.kinds.noop import Noop
    from tests.fakes import PackManager

    registry = KindRegistry()
    registry.register("chunk", Noop())
    processor = next(p for p in PackManager().processors.values() if p.family == "chunk-recursive")
    manifest = yaml.safe_load(processor.manifest_yaml)
    manifest["engine"]["strategy"] = strategy
    processor.manifest_yaml = yaml.safe_dump(manifest)
    with pytest.raises(UnsupportedKind, match="chunk/strategy-not-in-this-release"):
        registry.manifest(processor)
