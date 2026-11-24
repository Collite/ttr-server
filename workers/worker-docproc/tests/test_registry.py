# SPDX-License-Identifier: Apache-2.0
from pathlib import Path
from typing import Any

import pytest
import yaml
from jsonschema import ValidationError
from jsonschema.exceptions import best_match

from docproc.kinds.noop import Noop
from docproc.runtime.registry import KindRegistry, TypeRegistryClient, UnsupportedKind
from docproc.sdk.models import Processor
from tests.fakes import PackManager

VALID_SAMPLE = {"rate": 0.05, "floor": ["novel", "low_confidence"]}
OPEN_KINDS = ["llm-classify", "decide", "rerank", "compile", "diff", "import", "lemmatize", "link-pick"]


def test_all_pack_manifests_validate() -> None:
    registry = KindRegistry()
    for processor in PackManager().processors.values():
        registry.manifest(processor, hosted=False)


@pytest.fixture
def llm_mentions() -> Processor:
    return next(p for p in PackManager().processors.values() if p.family == "llm-mentions")


@pytest.mark.parametrize("role", ["mentor", "auditor"])
def test_sampling_is_optional_for_llm_extract_shadow_roles(llm_mentions: Processor, role: str) -> None:
    registry = KindRegistry()
    manifest = yaml.safe_load(llm_mentions.manifest_yaml)
    manifest["role"] = role
    legacy = llm_mentions.model_copy(update={"manifest_yaml": yaml.safe_dump(manifest)})
    assert "sample" not in registry.manifest(legacy, hosted=False)["engine"]

    manifest["engine"]["sample"] = VALID_SAMPLE
    sampled = llm_mentions.model_copy(update={"manifest_yaml": yaml.safe_dump(manifest)})
    assert registry.manifest(sampled, hosted=False)["engine"]["sample"] == VALID_SAMPLE


@pytest.mark.parametrize(
    ("sample", "path", "validator"),
    [
        pytest.param({**VALID_SAMPLE, "rate": 1.1}, ["engine", "sample", "rate"], "maximum", id="rate-outside-range"),
        pytest.param(
            {**VALID_SAMPLE, "floor": ["novel", "novel"]},
            ["engine", "sample", "floor"],
            "uniqueItems",
            id="repeated-floor",
        ),
        pytest.param(
            {**VALID_SAMPLE, "floor": ["novel"]},
            ["engine", "sample", "floor"],
            "minItems",
            id="floor-too-short",
        ),
        pytest.param(
            {**VALID_SAMPLE, "floor": ["novel", "other"]},
            ["engine", "sample", "floor", 1],
            "enum",
            id="floor-unknown",
        ),
        pytest.param({**VALID_SAMPLE, "rate": -0.1}, ["engine", "sample", "rate"], "minimum", id="rate-negative"),
        pytest.param({**VALID_SAMPLE, "rate": "0.05"}, ["engine", "sample", "rate"], "type", id="rate-string"),
        pytest.param({"floor": VALID_SAMPLE["floor"]}, ["engine", "sample"], "required", id="missing-rate"),
        pytest.param({"rate": VALID_SAMPLE["rate"]}, ["engine", "sample"], "required", id="missing-floor"),
        pytest.param({**VALID_SAMPLE, "extra": True}, ["engine", "sample"], "additionalProperties", id="extra-key"),
    ],
)
def test_invalid_sampling_values_are_refused(
    llm_mentions: Processor,
    sample: dict[str, Any],
    path: list[str | int],
    validator: str,
) -> None:
    manifest = yaml.safe_load(llm_mentions.manifest_yaml)
    manifest["engine"]["sample"] = sample
    sampled = llm_mentions.model_copy(update={"manifest_yaml": yaml.safe_dump(manifest)})
    with pytest.raises(ValidationError) as error:
        KindRegistry().manifest(sampled, hosted=False)
    assert list(error.value.absolute_path) == path
    assert error.value.validator == validator


@pytest.mark.parametrize(("rate", "accepted"), [("5e-2", False), ("5.0e-2", True)])
def test_sampling_rate_yaml_number_forms(llm_mentions: Processor, rate: str, accepted: bool) -> None:
    engine_line = "\nengine:\n"
    assert llm_mentions.manifest_yaml.count(engine_line) == 1
    yaml_text = llm_mentions.manifest_yaml.replace(
        engine_line,
        f"{engine_line}  sample: {{rate: {rate}, floor: [novel, low_confidence]}}\n",
    )
    sampled = llm_mentions.model_copy(update={"manifest_yaml": yaml_text})
    registry = KindRegistry()
    if accepted:
        assert registry.manifest(sampled, hosted=False)["engine"]["sample"]["rate"] == 0.05
    else:
        with pytest.raises(ValidationError) as error:
            registry.manifest(sampled, hosted=False)
        assert list(error.value.absolute_path) == ["engine", "sample", "rate"]
        assert error.value.validator == "type"


@pytest.mark.parametrize("role", ["primary", "residual", "retired", None])
def test_sampling_requires_explicit_shadow_role(llm_mentions: Processor, role: str | None) -> None:
    manifest = yaml.safe_load(llm_mentions.manifest_yaml)
    manifest["engine"]["sample"] = VALID_SAMPLE
    if role is None:
        del manifest["role"]
    else:
        manifest["role"] = role
    sampled = llm_mentions.model_copy(update={"manifest_yaml": yaml.safe_dump(manifest)})
    with pytest.raises(ValidationError) as error:
        KindRegistry().manifest(sampled, hosted=False)
    if role is None:
        assert error.value.validator == "required"
        assert "role" in error.value.message
    else:
        assert list(error.value.absolute_path) == ["role"]


@pytest.mark.parametrize("role", ["mentor", "auditor"])
@pytest.mark.parametrize("kind", OPEN_KINDS)
def test_sampling_is_refused_on_open_engine_kinds(llm_mentions: Processor, kind: str, role: str) -> None:
    registry = KindRegistry()
    manifest = yaml.safe_load(llm_mentions.manifest_yaml)
    manifest.update(kind=kind, role=role, engine={})
    legacy = llm_mentions.model_copy(update={"kind": kind, "manifest_yaml": yaml.safe_dump(manifest)})
    assert registry.manifest(legacy, hosted=False)["engine"] == {}

    manifest["engine"]["sample"] = VALID_SAMPLE
    sampled = legacy.model_copy(update={"manifest_yaml": yaml.safe_dump(manifest)})
    with pytest.raises(ValidationError) as error:
        registry.manifest(sampled, hosted=False)
    assert list(error.value.absolute_path) == ["kind"]
    assert error.value.validator == "const"


def test_sampling_is_refused_on_another_closed_engine() -> None:
    processor = next(p for p in PackManager().processors.values() if p.family == "cz-mentions")
    manifest = yaml.safe_load(processor.manifest_yaml)
    manifest["role"] = "auditor"
    manifest["engine"]["sample"] = VALID_SAMPLE
    sampled = processor.model_copy(update={"manifest_yaml": yaml.safe_dump(manifest)})
    registry = KindRegistry()
    with pytest.raises(ValidationError):
        registry.manifest(sampled, hosted=False)

    violations = list(registry._manifest.iter_errors(manifest))
    # The registry raises the first violation. Pin the best match from the full set.
    preferred = best_match(violations)
    assert preferred is not None
    assert list(preferred.absolute_path) == ["kind"]
    assert sorted((tuple(violation.absolute_path), violation.validator) for violation in violations) == [
        (("engine",), "additionalProperties"),
        (("kind",), "const"),
    ]


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
