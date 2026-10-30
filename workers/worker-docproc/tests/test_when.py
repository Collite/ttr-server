# SPDX-License-Identifier: Apache-2.0
import json
from typing import Any

import pytest

from docproc.runtime.predicate import Predicate, PredicateError
from docproc.sdk.models import Annotation, payload_text
from tests.conftest import CONTRACTS

CASES = json.loads((CONTRACTS / "when-cases.json").read_text())


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["expr"])
def test_shared_when_cases(case: dict[str, Any]) -> None:
    annotations = [Annotation(type=a["type"], payload_json=payload_text(a["payload"])) for a in case["annotations"]]
    if "error" in case:
        with pytest.raises(PredicateError):
            Predicate(case["expr"])
    else:
        assert Predicate(case["expr"]).evaluate(annotations) is case["expected"]
