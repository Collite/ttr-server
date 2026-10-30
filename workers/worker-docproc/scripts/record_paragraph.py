# SPDX-License-Identifier: Apache-2.0
"""Project a real Kotlin ParagraphSplitter run onto PX code-point offsets.

--classpath names an oracle jar (main DxP3ParagraphKt) and Kotlin stdlib. The
oracle reads base64 text lines and emits pipe-separated base64 paragraph texts,
using the unchanged splitIntoParagraphs(text, 30, 200) from kantheon 4f0b263b.
The jar is a local build tool, never needed by the worker or CI.
"""

from __future__ import annotations

import argparse
import base64
import re
import subprocess

from docproc.sdk.models import canonical
from tests.conftest import WORKER
from tests.pack import DOCS, units_of


def main() -> None:
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--classpath", required=True)
    args = parser.parse_args()
    units = [(doc, unit) for doc in DOCS for unit in units_of(doc)[1:]]
    lines = "\n".join(base64.b64encode(unit.content_text.encode()).decode() for _, unit in units) + "\n"
    result = subprocess.run(
        ["java", "-cp", args.classpath, "org.tatrman.docwh.store.ingestion.DxP3ParagraphKt"],
        input=lines,
        text=True,
        capture_output=True,
        check=True,
    )
    documents = {}
    for (doc, unit), line in zip(units, result.stdout.splitlines(), strict=True):
        words = list(re.finditer(r"\S+", unit.content_text))
        cursor, spans = 0, []
        for encoded in line.split("|") if line else []:
            text = base64.b64decode(encoded).decode()
            count = len(text.split())
            group = words[cursor : cursor + count]
            assert text == " ".join(word.group() for word in group), (doc, text)
            spans.append({"start": group[0].start(), "end": group[-1].end()})
            cursor += count
        assert cursor == len(words)
        documents.setdefault(doc, []).append({"idx": unit.idx, "spans": spans})
    output = {
        "source": "kantheon@4f0b263b:services/docwh-store/src/main/kotlin/org/tatrman/docwh/store/ingestion/ParagraphSplitter.kt",
        "minWords": 30,
        "maxWords": 200,
        "documents": documents,
    }
    (WORKER / "tests/fixtures/paragraph-kotlin.json").write_text(canonical(output))
    print(f"Kotlin oracle recorded: {len(units)} sections")


if __name__ == "__main__":
    main()
