# SPDX-License-Identifier: Apache-2.0
"""V-OM-1: pin real RDF importer behavior, independently of connector reconciliation."""

from pathlib import Path
from urllib.parse import quote

import pytest

from .conftest import LiveOm

pytestmark = pytest.mark.om_live


@pytest.mark.parametrize("profile", ["owl", "both"])
def test_rdf_import(om: LiveOm, profile: str) -> None:
    glossary = f"xm-rdf-{profile}"
    om.call("PUT", "glossaries", json={"name": glossary, "description": "V-OM-1 fixture"})
    turtle = (Path(__file__).parents[1] / f"fixtures/ttl/{profile}.ttl").read_text()
    result = om.call(
        "PUT",
        f"glossaries/name/{quote(glossary, safe='')}/importRdf",
        params={"format": "turtle", "dryRun": "false"},
        data=turtle.encode(),
        headers={"Content-Type": "text/turtle"},
    )
    assert result["dryRun"] is False
    terms = [
        t
        for t in om.entities("glossaryTerms", "synonyms,parent,relatedTerms")
        if t["glossary"]["name"] == glossary
    ]
    om.record("rdf-import", {"profile": profile, "result": result, "terms": terms})
    assert terms, result
    assert {"https://example.org/northline/Party", "urn:ttr:northline:cn.role.Customer"} <= {
        t.get("iri") for t in terms
    }
    assert not any(str(m).startswith("Failed ") for m in result.get("messages", [])), result

    party = next(t for t in terms if t["iri"] == "https://example.org/northline/Party")
    assert party["displayName"] == "Party"
    assert "Osoba" not in (party.get("synonyms") or [])
    assert party.get("synonyms", []) == (
        [] if profile == "owl" else ["party", "osoba", "partner", "partner"]
    )
    assert any("Relation isa skipped" in str(m) for m in result.get("messages", []))
