# SPDX-License-Identifier: Apache-2.0
"""Offline publication checks; run with unittest discover from the repo root."""

import copy
import hashlib
import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

from jsonschema import Draft7Validator, Draft202012Validator


ROOT = Path(__file__).resolve().parent


def load(relative):
    return json.loads((ROOT / relative).read_text(encoding="utf-8"))


class ConfigurationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        schema = load("config.schema.json")
        Draft202012Validator.check_schema(schema)
        cls.validator = Draft202012Validator(schema)

    def test_examples(self):
        valid = sorted((ROOT / "examples/valid").glob("*.json"))
        self.assertEqual(len(valid), 4)
        for path in valid:
            with self.subTest(example=path.name):
                self.validator.validate(json.loads(path.read_text(encoding="utf-8")))
        reasons = {
            "mixed_lanes.json": ("oneOf", "$.core"),
            "reserved_variable.json": ("pattern", "$.variables"),
            "too_many_cores.json": ("maxProperties", "$.core"),
            "reserved_prefix.json": ("pattern", "$.variables.selected.prefix"),
            "bad_prefix.json": ("pattern", "$.variables.selected.prefix"),
            "empty_columns.json": ("minItems", "$.core.item.columns"),
            "duplicate_columns.json": ("uniqueItems", "$.core.item.columns"),
        }

        def flatten(errors):
            for error in errors:
                yield error
                yield from flatten(error.context)

        invalid = sorted((ROOT / "examples/invalid").glob("*.json"))
        self.assertEqual({p.name for p in invalid}, set(reasons))
        for path in invalid:
            with self.subTest(example=path.name):
                config = json.loads(path.read_text(encoding="utf-8"))
                keyword, prefix = reasons[path.name]
                errors = list(flatten(self.validator.iter_errors(config)))
                self.assertTrue(any(e.validator == keyword and e.json_path.startswith(prefix)
                                    for e in errors), [(e.validator, e.json_path) for e in errors])
                if path.name == "mixed_lanes.json":
                    # A misspelled source must not masquerade as the lane-exclusivity failure.
                    for core in config["core"].values():
                        self.validator.validate({"core": {"one": core}})

    def test_core_boundaries(self):
        for size in (0, 1, 8, 9):
            with self.subTest(size=size):
                config = {"core": {f"item_{i}": {"entity": "item"} for i in range(size)}}
                self.assertEqual(self.validator.is_valid(config), 1 <= size <= 8)

    def test_exactly_one_source_per_core(self):
        for core in ({}, {"entity": "item", "sql": "SELECT 1"}, {"query": "bare_name"}):
            with self.subTest(core=core):
                self.assertFalse(self.validator.is_valid({"core": {"items": core}}))

    def test_golem_is_alone(self):
        config = load("examples/valid/golem.json")
        config["core"]["second"] = copy.deepcopy(config["core"]["answer"])
        self.assertFalse(self.validator.is_valid(config))

    def test_core_and_variable_names(self):
        for name in ("Item", "1item", "item-name", "položky"):
            with self.subTest(core=name):
                self.assertFalse(self.validator.is_valid({"core": {name: {"entity": "item"}}}))
        for name in ("tatrman_status", "1item", "item-name", "položky"):
            with self.subTest(variable=name):
                config = {"core": {"items": {"entity": "item"}}, "variables": {
                    name: {"kind": "exists", "over": "items"}}}
                self.assertFalse(self.validator.is_valid(config))

    def test_distinct_types_are_kind_specific(self):
        config = load("examples/valid/data.json")
        for name, value in (("n", True), ("ids", "id")):
            with self.subTest(variable=name):
                changed = copy.deepcopy(config)
                changed["variables"][name]["distinct"] = value
                self.assertFalse(self.validator.is_valid(changed))

    def test_row_empty_or_absent_prefix(self):
        config = load("examples/valid/single_row.json")
        self.validator.validate(config)
        del config["variables"]["selected"]["prefix"]
        self.validator.validate(config)
        config["variables"]["selected"]["prefix"] = "item_"
        self.validator.validate(config)

    def test_required_variable_keys(self):
        for example in ("data", "single_row", "golem"):
            original = load(f"examples/valid/{example}.json")
            for name, variable in original["variables"].items():
                for key in ("kind", "over", "column", "agg", "sql"):
                    if key not in variable:
                        continue
                    with self.subTest(kind=variable["kind"], missing=key):
                        config = copy.deepcopy(original)
                        del config["variables"][name][key]
                        self.assertFalse(self.validator.is_valid(config))


class TemplateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        schema = load("vendor/camunda-element-template.schema.json")
        Draft7Validator.check_schema(schema)
        cls.validator = Draft7Validator(schema)

    def test_vendored_schema_checksum(self):
        raw = (ROOT / "vendor/camunda-element-template.schema.json").read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(),
                         "df9a6d3dcacc8f4e1cdf4b3cad4265c08dee39ebc63fd24304102037c2abdfd4")

    def test_both_templates_validate(self):
        for name in ("tatrman-question", "tatrman-dotaz"):
            with self.subTest(template=name):
                template = load(f"camunda7/{name}.json")
                self.assertEqual(template["id"], name)
                self.validator.validate(template)

    def test_schema_rejects_invalid_connector_binding(self):
        template = load("camunda7/tatrman-question.json")
        template["scopes"][0]["properties"][1]["binding"]["type"] = "invalid"
        self.assertFalse(self.validator.is_valid(template))

    def test_template_localisations_keep_execution_bindings(self):
        english = load("camunda7/tatrman-question.json")
        czech = load("camunda7/tatrman-dotaz.json")
        for template in (english, czech):
            template.pop("id")
            template.pop("name")
            for prop in template["properties"]:
                prop.pop("label", None)
                prop.pop("description", None)
        # Only this engine diagnostic is translated inside the scripts.
        czech["scopes"][0]["properties"][-1]["binding"]["source"] = (
            czech["scopes"][0]["properties"][-1]["binding"]["source"]
            .replace("odpověď HTTP ", "HTTP response ")
            .replace(" není JSON služby", " is not service JSON"))
        self.assertEqual(english, czech)

    def test_endpoint_and_credential_are_engine_properties(self):
        for name in ("tatrman-question", "tatrman-dotaz"):
            with self.subTest(template=name):
                template = load(f"camunda7/{name}.json")
                props = {p["binding"].get("name"): p for p in template["scopes"][0]["properties"]}
                self.assertIn("System.getProperty('tatrman.url')", props["url"]["value"])
                self.assertIn("System.getProperty('tatrman.token')", props["headers"]["value"])
                for prop, variable in (("url", "url"), ("headers", "token")):
                    script = props[prop]["value"]
                    guard = f"if ({variable} == null || {variable}.trim().isEmpty())"
                    incident = f"throw new IllegalStateException('engine property tatrman.{variable} is not set')"
                    self.assertIn(guard, script)
                    self.assertIn(incident, script)
                    self.assertLess(script.index(guard), script.index(incident))

    def test_decimal_conversion_requires_exact_round_trip_or_java_object(self):
        # A CI regression guard; actual persistence is proven on the embedded engine.
        for name in ("tatrman-question", "tatrman-dotaz"):
            with self.subTest(template=name):
                script = load(f"camunda7/{name}.json")["properties"][-1]["value"]
                guard = ("value instanceof BigDecimal && Double.isFinite(value.doubleValue()) &&\n"
                         "      new BigDecimal(value.doubleValue().toString()).compareTo(value) == 0")
                self.assertIn(guard, script)
                self.assertIn(guard + ") {\n"
                              "    // Use a native Double only when its decimal text round-trips unchanged.\n"
                              "    typed = value.doubleValue()", script)
                self.assertEqual(script.count("typed = value.doubleValue()"), 1)
                fallback = script.split("} else if (value instanceof BigDecimal) {", 1)[1]
                fallback = fallback.split("} else if", 1)[0]
                self.assertIn("Variables.objectValue(value)", fallback)
                self.assertIn(".serializationDataFormat('application/x-java-serialized-object')", fallback)

    def test_export_guard_protects_reserved_and_invalid_names(self):
        for name in ("tatrman-question", "tatrman-dotaz"):
            with self.subTest(template=name):
                script = load(f"camunda7/{name}.json")["properties"][-1]["value"]
                guard = "if (!(name instanceof String) || !(name ==~ /^[A-Za-z_][A-Za-z0-9_]*$/) || name.startsWith('tatrman_'))"
                self.assertIn(guard, script)
                guarded = script.split(guard, 1)[1].split("def typed = value", 1)[0]
                self.assertIn(".warning(", guarded)
                self.assertIn("return", guarded)
                self.assertLess(script.index(guard), script.index("execution.setVariable(name, typed)"))

    def test_sample_carries_template_scripts_and_literal_configuration(self):
        ns = {"b": "http://www.omg.org/spec/BPMN/20100524/MODEL",
              "c": "http://camunda.org/schema/1.0/bpmn"}
        root = ET.parse(ROOT / "samples/question.bpmn").getroot()
        task = root.find(".//b:serviceTask", ns)
        self.assertIsNotNone(task)
        self.assertEqual(task.get("{" + ns["c"] + "}modelerTemplate"), "tatrman-question")
        self.assertEqual(task.get("{" + ns["c"] + "}asyncBefore"), "true")
        field = task.find("b:extensionElements/c:field[@name='tatrman_config']/c:string", ns)
        self.assertIn("id = {item_id}", field.text)
        self.assertIn("kind: row_count", field.text)
        template = load("camunda7/tatrman-question.json")
        end = task.find(".//c:executionListener[@event='end']/c:script", ns)
        self.assertEqual(end.text, template["properties"][-1]["value"])
        for prop in template["scopes"][0]["properties"]:
            binding = prop["binding"]
            if binding["type"] == "property":
                self.assertEqual(task.find(".//c:connectorId", ns).text, prop["value"])
                continue
            output = binding["type"] == "camunda:outputParameter"
            kind = "outputParameter" if output else "inputParameter"
            name = prop["value"] if output else binding["name"]
            parameter = task.find(f".//c:connector/c:inputOutput/c:{kind}[@name='{name}']", ns)
            self.assertIsNotNone(parameter)
            actual = parameter.find("c:script", ns) if "scriptFormat" in binding else parameter
            self.assertEqual(actual.text, binding["source"] if output else prop["value"])


if __name__ == "__main__":
    unittest.main()
