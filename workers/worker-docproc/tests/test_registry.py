# SPDX-License-Identifier: Apache-2.0
from pathlib import Path
from typing import Any

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


def test_registry_exact_version_and_manager_schema_drift() -> None:
    import copy

    from tests.fakes import PACK

    manager = PackManager()
    original = manager.registry
    manager.registry = lambda version: {**original(version), "version": "other"}  # type: ignore[method-assign]
    with pytest.raises(ValueError, match="version-mismatch"):
        TypeRegistryClient(manager).load("1")
    manager.registry = original  # type: ignore[method-assign]
    body = copy.deepcopy(original("1"))
    body["types"][0]["schemaJson"] = '{"type":"object"}'
    manager.registry = lambda version: body  # type: ignore[method-assign]
    with pytest.raises(ValueError, match="manager-schema-drift"):
        TypeRegistryClient(manager, str(PACK)).load("1")


def test_hosted_budget_required() -> None:
    import yaml

    registry = KindRegistry()
    registry.register("classify", Noop())
    p = next(p for p in PackManager().processors.values() if p.kind == "classify")
    manifest = yaml.safe_load(p.manifest_yaml)
    del manifest["budget"]["retries"]
    p.manifest_yaml = yaml.safe_dump(manifest)
    with pytest.raises(ValueError, match="manifest/budget-required"):
        registry.manifest(p)


def test_registry_formats_checked(monkeypatch: pytest.MonkeyPatch) -> None:
    from jsonschema import FormatChecker

    manager = PackManager()
    registry = TypeRegistryClient(manager)
    registry.load("1")
    record_schema = registry.schema_for("Record.review")
    from docproc.kinds.consolidate import Consolidator
    from tests.extraction import complete_subject
    from tests.fakes import PACK

    ctx, warehouse = complete_subject()
    record = next(
        a
        for a in Consolidator(warehouse, registry, str(PACK)).consolidate(ctx).annotations
        if a.type == "Record.review"
    )
    payload = record.payload()
    payload["attributes"]["recorded_at"] = "not-a-date"
    with pytest.raises(ValidationError) as caught:
        record_schema.validate(payload)
    assert caught.value.validator == "format"
    monkeypatch.delitem(FormatChecker.checkers, "uri", raising=False)
    manager.extra_types = [{"key": "Uri", "schema": {"format": "uri"}}]
    with pytest.raises(ValueError, match="registry/format-unchecked: uri"):
        TypeRegistryClient(manager).load("2")


def test_same_registry_version_is_compiled_once(monkeypatch: pytest.MonkeyPatch) -> None:
    from docproc.runtime.model_hash import verify_pack
    from tests.fakes import PACK

    manager = PackManager()
    original = manager.registry
    calls: list[str] = []
    verifies: list[str] = []

    def counted(version: str) -> dict[str, Any]:
        calls.append(version)
        return original(version)

    def verified(pack: Path) -> None:
        verifies.append(str(pack))
        verify_pack(pack)

    monkeypatch.setattr(manager, "registry", counted)
    monkeypatch.setattr("docproc.runtime.model_hash.verify_pack", verified)
    registry = TypeRegistryClient(manager, str(PACK))
    registry.verify_pack()
    registry.load("v2")
    schema = registry.schema_for("Record.review")
    registry.load("v2")
    assert calls == ["v2"] and verifies == [str(PACK)]
    assert registry.schema_for("Record.review") is schema
