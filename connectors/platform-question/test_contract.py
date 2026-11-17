# SPDX-License-Identifier: Apache-2.0
"""Offline publication checks; run with unittest discover from the repo root."""

import copy
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator


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
        for category, expected in (("valid", True), ("invalid", False)):
            files = sorted((ROOT / "examples" / category).glob("*.json"))
            self.assertEqual(len(files), 3)
            for path in files:
                with self.subTest(example=path.name, category=category):
                    config = json.loads(path.read_text(encoding="utf-8"))
                    self.assertEqual(self.validator.is_valid(config), expected)

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

    def test_required_variable_keys(self):
        for kind, missing in (("aggregate", "column"), ("row_count", "over"),
                              ("scalar", "sql"), ("verdict", "over")):
            with self.subTest(kind=kind, missing=missing):
                variable = {"kind": kind, "over": "items", "agg": "sum", "column": "amount"}
                variable.pop(missing, None)
                config = {"core": {"items": {"entity": "item"}}, "variables": {"v": variable}}
                self.assertFalse(self.validator.is_valid(config))


if __name__ == "__main__":
    unittest.main()
