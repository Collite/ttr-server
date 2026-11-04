# SPDX-License-Identifier: Apache-2.0
"""NLS-P2.2.T1/T2/T5 — fail-all-or-nothing, and the state id.

Two properties carry the whole design, and both are about what does *not* happen.

**Nothing loads if anything is wrong.** The test that matters here is not "a bad
pack raises" — it is that three good packs beside one bad one produce *no*
snapshot. Partial loading is the failure this posture exists to prevent: a
service that comes up healthy, answers most questions, and cannot answer the ones
the broken pack was for, with nobody the wiser until a user notices.

**A state id means what it says.** Same bytes ⇒ same id, any change ⇒ new id.
`ReloadPacks` reports the id, so an operator uses it to tell a reload that
changed something from one that did not; an id keyed on mtimes or a counter would
call a redeployed identical config map a new snapshot.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

from ttrnlp.packs.diag import (
    NLS_PACK_001,
    NLS_PACK_003,
    NLS_PACK_004,
)
from ttrnlp.packs.loader import (
    LoadError,
    gather_sources,
    load_sources,
    read_sources,
    state_id_for,
)

FIXTURES = Path(__file__).parent.parent / "fixtures"
VALID_PACKS = FIXTURES / "packs" / "valid"
INVALID_PACKS = FIXTURES / "packs" / "invalid"
VALID_LISTS = FIXTURES / "lists" / "valid"
INVALID_LISTS = FIXTURES / "lists" / "invalid"

GOOD_PACK = """
pack: <ID>
version: 1
phases:
  - phase: p
    input: [Token]
    control: appelt
    rules:
      - rule: R
        lhs: [ { ann: Token } ]
        rhs: [ { add: { type: M } } ]
"""

BAD_PACK = """
pack: broken
version: 1
phases:
  - phase: p
    input: [Token]
    control: appelts
    rules:
      - rule: R
        lhs: [ { ann: Token } ]
        rhs: [ { add: { type: M } } ]
"""

GOOD_LIST = """
list: <ID>
version: 1
matching: ci
source: {world: hand, origin: test}
entries:
  - term: faktura
"""


def good_pack(pack_id: str) -> str:
    """`.format()` is unusable here — a pack body is full of YAML flow mappings
    (`{ ann: Token }`) and every brace would need doubling, which is exactly the
    kind of noise that makes a fixture unreadable."""
    return GOOD_PACK.replace("<ID>", pack_id)


def good_list(list_id: str) -> str:
    return GOOD_LIST.replace("<ID>", list_id)


def tree(root: Path, files: dict[str, str]) -> Path:
    for name, text in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    return root


def three_good(root: Path) -> Path:
    return tree(
        root,
        {
            f"{name}.pack.yaml": good_pack(name)
            for name in ("alpha", "beta", "gamma")
        }
        | {"vocab.list.yaml": good_list("vocab")},
    )


# ── fail-all (T1) ────────────────────────────────────────────────────────────


def test_three_good_packs_and_one_bad_one_load_nothing(tmp_path):
    three_good(tmp_path)
    (tmp_path / "broken.pack.yaml").write_text(BAD_PACK, encoding="utf-8")

    with pytest.raises(LoadError) as raised:
        load_sources([str(tmp_path)])

    # Exactly the bad pack's diagnostics — the good three contribute none.
    assert [d.pack for d in raised.value.diagnostics] == ["broken"]
    assert raised.value.codes == [NLS_PACK_001]
    assert "appelts" in raised.value.diagnostics[0].message


def test_the_error_names_every_broken_file_not_just_the_first():
    """One round trip per fix would make a six-typo pack tree a six-run job."""
    with pytest.raises(LoadError) as raised:
        load_sources([str(INVALID_PACKS)])

    sources = {Path(d.source).name for d in raised.value.diagnostics}
    assert len(sources) == len(list(INVALID_PACKS.glob("*.pack.yaml")))


def test_all_good_sources_produce_a_state(tmp_path):
    state = load_sources([str(three_good(tmp_path))], loaded_at="2026-08-11T00:00:00Z")

    assert sorted(state.packs) == ["alpha", "beta", "gamma"]
    assert sorted(state.lists) == ["vocab"]
    assert state.packs_loaded == 3
    assert state.lists_loaded == 1
    assert state.loaded_at == "2026-08-11T00:00:00Z"
    assert state.gazetteer.list_ids == ("vocab",)
    assert state.phase_names("alpha") == ("p",)


def configmap(root: Path, stamp: str) -> Path:
    """The layout a ConfigMap volume has: the files under `..<timestamp>/`, `..data`
    a symlink to it, and each key a symlink through `..data`. An update writes a new
    timestamped directory and swaps `..data`."""
    dated = root / f"..{stamp}"
    three_good(dated)
    (root / "..data").unlink(missing_ok=True)
    (root / "..data").symlink_to(dated.name)
    for file in dated.iterdir():
        if not (root / file.name).is_symlink():
            (root / file.name).symlink_to(Path("..data") / file.name)
    return root


def test_a_configmap_mount_loads_each_file_once(tmp_path):
    state = load_sources([str(configmap(tmp_path, "2026_11_04_12_00_00.1"))])

    assert sorted(state.packs) == ["alpha", "beta", "gamma"]
    assert sorted(state.lists) == ["vocab"]


def test_a_configmap_update_with_the_same_content_keeps_the_state_id(tmp_path):
    before = load_sources([str(configmap(tmp_path, "2026_11_04_12_00_00.1"))]).state_id
    after = load_sources([str(configmap(tmp_path, "2026_11_04_13_00_00.2"))]).state_id
    assert before == after


def test_the_shipped_fixtures_load_as_one_state():
    """The coverage packs and the hero list, together, the way a deployment holds
    them — a pack tree and a list tree as two separate sources."""
    state = load_sources([str(VALID_PACKS), str(VALID_LISTS)])

    assert "hero-cs-invoices" in state.packs
    assert "dfp-entity-aliases" in state.lists


def test_a_broken_list_fails_the_whole_load_too(tmp_path):
    """Lists are not second-class: a bad one stops everything, same as a pack."""
    three_good(tmp_path)
    (tmp_path / "bad.list.yaml").write_text(
        "list: bad\nversion: 1\nmatching: fuzzy\n"
        "source: {world: hand, origin: test}\nentries: [{term: x}]",
        encoding="utf-8",
    )

    with pytest.raises(LoadError) as raised:
        load_sources([str(tmp_path)])
    assert raised.value.codes == [NLS_PACK_003]


def test_a_source_that_is_not_there_is_a_diagnostic_not_a_crash():
    """A packs directory that is absent is usually a volume that failed to mount.
    The service has to come up and say so — `Analyze` does not need packs, and a
    crash loop puts the reason in logs nobody is reading yet."""
    with pytest.raises(LoadError) as raised:
        load_sources(["/definitely/not/here"])

    (diagnostic,) = raised.value.diagnostics
    assert diagnostic.code == NLS_PACK_001
    assert "does not exist" in diagnostic.message


def test_a_file_that_cannot_be_read_is_a_diagnostic_not_a_crash(tmp_path):
    """Same posture as a missing directory, and the likelier failure of the two.

    An unreadable file named directly as a source went through an unguarded
    `read_text`, so a `PermissionError` escaped `load_sources` entirely — past
    `PackState._attempt`, which catches `LoadError` — and crashed the boot or
    came back from `ReloadPacks` as UNKNOWN. What an operator needs is
    NLS-PACK-001 naming the file.
    """
    path = tmp_path / "sealed.pack.yaml"
    path.write_text(good_pack("sealed"), encoding="utf-8")
    path.chmod(0o000)
    try:
        with pytest.raises(LoadError) as raised:
            load_sources([str(path)])
    finally:
        path.chmod(0o644)

    (diagnostic,) = raised.value.diagnostics
    assert diagnostic.code == NLS_PACK_001
    assert "could not read" in diagnostic.message


def test_a_file_that_is_not_utf8_is_a_diagnostic_not_a_crash(tmp_path):
    """`UnicodeDecodeError` is a `ValueError`, NOT an `OSError`, so guarding only
    the latter left it escaping — and one stray byte in a mounted configmap is
    the likeliest way a cluster meets this."""
    (tmp_path / "latin.pack.yaml").write_bytes(
        b"pack: x\nversion: 1\n# z\xe1kazn\xedk\n"
    )

    with pytest.raises(LoadError) as raised:
        load_sources([str(tmp_path)])

    assert raised.value.codes == [NLS_PACK_001]
    assert "could not read" in raised.value.diagnostics[0].message


def test_an_unreadable_file_does_not_hide_the_rest_of_the_tree(tmp_path):
    """Reading stopped at the first bad file, and `*.list.yaml` is a whole second
    suffix pass — so an author fixed one unreadable pack only to be told about
    the next, and the lists were never even looked at."""
    three_good(tmp_path)
    (tmp_path / "latin.pack.yaml").write_bytes(b"# z\xe1kazn\xedk\n")
    (tmp_path / "bad.list.yaml").write_text(
        "id: nope\nentries: not-a-list\n", encoding="utf-8"
    )

    with pytest.raises(LoadError) as raised:
        load_sources([str(tmp_path)])

    messages = " ".join(d.message for d in raised.value.diagnostics)
    assert "latin.pack.yaml" in messages
    # The list was reached, parsed and complained about in the SAME run.
    assert NLS_PACK_003 in raised.value.codes


def test_a_single_file_is_a_valid_source():
    state = load_sources([str(VALID_PACKS / "hero-cs-role.pack.yaml")])
    assert sorted(state.packs) == ["hero-cs-role"]


def test_a_file_of_an_unknown_kind_is_rejected(tmp_path):
    path = tmp_path / "notes.yaml"
    path.write_text("pack: nope\n", encoding="utf-8")

    with pytest.raises(LoadError) as raised:
        load_sources([str(path)])
    assert "neither" in raised.value.diagnostics[0].message


def test_files_are_found_recursively(tmp_path):
    tree(tmp_path, {"world/dfp/deep.pack.yaml": good_pack("deep")})
    assert sorted(load_sources([str(tmp_path)]).packs) == ["deep"]


# ── the state id (T2) ────────────────────────────────────────────────────────


def test_the_same_content_gives_the_same_state_id(tmp_path):
    first = load_sources([str(three_good(tmp_path / "a"))]).state_id
    second = load_sources([str(three_good(tmp_path / "a"))]).state_id
    assert first == second


def test_any_byte_change_gives_a_new_state_id(tmp_path):
    root = three_good(tmp_path)
    before = load_sources([str(root)]).state_id

    pack = root / "alpha.pack.yaml"
    pack.write_text(pack.read_text(encoding="utf-8") + "\n", encoding="utf-8")

    assert load_sources([str(root)]).state_id != before


def test_the_state_id_ignores_mtimes(tmp_path):
    """A redeployed config map has new mtimes and identical content. Calling that
    a new snapshot would make the id useless for the question it exists for."""
    root = three_good(tmp_path)
    before = load_sources([str(root)]).state_id

    for path in root.glob("*.yaml"):
        text = path.read_text(encoding="utf-8")
        path.unlink()
        path.write_text(text, encoding="utf-8")

    assert load_sources([str(root)]).state_id == before


def test_the_state_id_does_not_depend_on_the_order_sources_are_given(tmp_path):
    """Two mount points listed either way round are the same snapshot."""
    a = tree(tmp_path / "a", {"a.pack.yaml": good_pack("a")})
    b = tree(tmp_path / "b", {"b.pack.yaml": good_pack("b")})

    assert (
        load_sources([str(a), str(b)]).state_id
        == load_sources([str(b), str(a)]).state_id
    )


def test_the_state_id_is_short_and_hex(tmp_path):
    state_id = load_sources([str(three_good(tmp_path))]).state_id
    assert len(state_id) == 16
    assert all(ch in "0123456789abcdef" for ch in state_id)


def test_an_empty_source_is_not_an_error(tmp_path):
    """A deployment with no packs yet is a legitimate state: the service serves
    Analyze and reports zero packs, rather than refusing to boot."""
    state = load_sources([str(tmp_path)])
    assert state.packs_loaded == 0
    assert state.state_id == state_id_for([])


# ── cross-file duplicates (T5) ───────────────────────────────────────────────


def test_two_packs_with_the_same_id_across_sources_are_rejected(tmp_path):
    a = tree(tmp_path / "a", {"one.pack.yaml": good_pack("clash")})
    b = tree(tmp_path / "b", {"two.pack.yaml": good_pack("clash")})

    with pytest.raises(LoadError) as raised:
        load_sources([str(a), str(b)])

    (diagnostic,) = raised.value.diagnostics
    assert diagnostic.code == NLS_PACK_001
    assert "duplicate pack id 'clash'" in diagnostic.message
    # Both sides named: "which two files" is the only useful thing to say.
    assert "one.pack.yaml" in diagnostic.message
    assert diagnostic.source.endswith("two.pack.yaml")


def test_two_lists_with_the_same_id_across_sources_are_rejected(tmp_path):
    a = tree(tmp_path / "a", {"one.list.yaml": good_list("clash")})
    b = tree(tmp_path / "b", {"two.list.yaml": good_list("clash")})

    with pytest.raises(LoadError) as raised:
        load_sources([str(a), str(b)])

    (diagnostic,) = raised.value.diagnostics
    assert diagnostic.code == NLS_PACK_003
    assert "duplicate list id 'clash'" in diagnostic.message


def test_a_pack_and_a_list_may_share_an_id(tmp_path):
    """Different namespaces. A pipeline says `gazetteer: [x]` or
    `rules: [{pack: x}]`, never just `x`, so there is nothing to confuse."""
    root = tree(
        tmp_path,
        {
            "p.pack.yaml": good_pack("same"),
            "l.list.yaml": good_list("same"),
        },
    )
    state = load_sources([str(root)])
    assert "same" in state.packs
    assert "same" in state.lists


# ── pipeline references (T1, NLS-PACK-004) ───────────────────────────────────


def pipelines_of(**spec):
    return {"query-patterns": spec}


def test_a_pipeline_referencing_an_unknown_pack_is_rejected(tmp_path):
    with pytest.raises(LoadError) as raised:
        load_sources(
            [str(three_good(tmp_path))],
            pipelines=pipelines_of(rules=[{"pack": "nope", "phase": "p"}]),
        )
    (diagnostic,) = raised.value.diagnostics
    assert diagnostic.code == NLS_PACK_004
    assert "no pack 'nope'" in diagnostic.message
    # The message lists what IS loaded — the next thing the reader needs.
    assert "alpha" in diagnostic.message


def test_a_pipeline_referencing_an_unknown_phase_is_rejected(tmp_path):
    with pytest.raises(LoadError) as raised:
        load_sources(
            [str(three_good(tmp_path))],
            pipelines=pipelines_of(rules=[{"pack": "alpha", "phase": "nope"}]),
        )
    (diagnostic,) = raised.value.diagnostics
    assert diagnostic.code == NLS_PACK_004
    assert "has no phase 'nope'" in diagnostic.message
    assert diagnostic.pack == "alpha"


def test_a_pipeline_referencing_an_unknown_list_is_rejected(tmp_path):
    with pytest.raises(LoadError) as raised:
        load_sources(
            [str(three_good(tmp_path))], pipelines=pipelines_of(gazetteer=["nope"])
        )
    (diagnostic,) = raised.value.diagnostics
    assert diagnostic.code == NLS_PACK_004
    assert "no list 'nope'" in diagnostic.message


def test_a_pipeline_whose_references_all_resolve_loads(tmp_path):
    state = load_sources(
        [str(three_good(tmp_path))],
        pipelines=pipelines_of(
            ops=["TOKENIZE"],
            gazetteer=["vocab"],
            rules=[{"pack": "alpha", "phase": "p"}],
        ),
    )
    assert state.packs_loaded == 3


def test_a_malformed_pipeline_table_is_rejected_rather_than_ignored(tmp_path):
    with pytest.raises(LoadError) as raised:
        load_sources([str(three_good(tmp_path))], pipelines={"p": ["not", "a", "map"]})
    assert raised.value.codes == [NLS_PACK_004]


# ── URL sources (T1) ─────────────────────────────────────────────────────────


class _Response:
    def __init__(self, text: str, status: int = 200):
        self.text = text
        self.status = status

    def raise_for_status(self):
        if self.status >= 400:
            raise RuntimeError(f"HTTP {self.status}")


def test_a_url_source_is_fetched_and_loaded(monkeypatch):
    import httpx

    seen = {}

    def fake_get(url, **kwargs):
        seen["url"] = url
        return _Response(good_pack("remote"))

    monkeypatch.setattr(httpx, "get", fake_get)
    state = load_sources(["https://packs.example/dfp/remote.pack.yaml"])

    assert seen["url"] == "https://packs.example/dfp/remote.pack.yaml"
    assert sorted(state.packs) == ["remote"]


def test_a_url_may_serve_a_list_too(monkeypatch):
    import httpx

    monkeypatch.setattr(
        httpx, "get", lambda url, **kw: _Response(good_list("remote"))
    )
    state = load_sources(["https://packs.example/remote.list.yaml"])
    assert sorted(state.lists) == ["remote"]


def test_a_connection_error_is_a_diagnostic(monkeypatch):
    """A source that did not arrive is a fail-all reason, not a traceback: the
    service must report it through GetStatus like any other pack problem."""
    import httpx

    def refuse(url, **kwargs):
        raise httpx.ConnectError("connection refused")

    monkeypatch.setattr(httpx, "get", refuse)

    with pytest.raises(LoadError) as raised:
        load_sources(["https://packs.example/remote.pack.yaml"])

    (diagnostic,) = raised.value.diagnostics
    assert diagnostic.code == NLS_PACK_001
    assert "could not fetch" in diagnostic.message
    assert "connection refused" in diagnostic.message


def test_an_http_error_status_is_a_diagnostic(monkeypatch):
    import httpx

    monkeypatch.setattr(httpx, "get", lambda url, **kw: _Response("", status=404))

    with pytest.raises(LoadError) as raised:
        load_sources(["https://packs.example/remote.pack.yaml"])
    assert "HTTP 404" in raised.value.diagnostics[0].message


def test_a_url_that_names_no_file_kind_is_rejected(monkeypatch):
    with pytest.raises(LoadError) as raised:
        load_sources(["https://packs.example/packs/"])
    assert "one URL is one file" in raised.value.diagnostics[0].message


def test_the_http_extra_being_absent_says_so(monkeypatch):
    """The actionable version of `ModuleNotFoundError: httpx`. `None` in
    sys.modules is what makes `import httpx` fail the way an uninstalled extra
    would."""
    monkeypatch.setitem(sys.modules, "httpx", None)

    with pytest.raises(LoadError) as raised:
        load_sources(["https://packs.example/remote.pack.yaml"])
    assert "ttr-nlp[http]" in raised.value.diagnostics[0].message


# ── read_sources / gather_sources directly ───────────────────────────────────


def test_reading_is_deterministic(tmp_path):
    root = three_good(tmp_path)
    first, _ = read_sources([str(root)])
    second, _ = read_sources([str(root)])
    assert [f.path for f in first] == [f.path for f in second]


def test_packs_are_read_before_lists(tmp_path):
    """Not semantically required, but it must be *decided*: an order that depends
    on directory iteration makes the diagnostic order jump around between runs on
    otherwise identical input."""
    files, _ = read_sources([str(three_good(tmp_path))])
    kinds = [f.kind for f in files]
    assert kinds == sorted(kinds, key=lambda k: k != "pack")


def test_gathering_collects_without_raising(tmp_path):
    three_good(tmp_path)
    (tmp_path / "broken.pack.yaml").write_text(BAD_PACK, encoding="utf-8")

    gathered = gather_sources([str(tmp_path)])

    assert gathered.has_errors
    # The good material is still there — the caller decides what to do about it.
    assert sorted(gathered.packs) == ["alpha", "beta", "gamma"]


def test_a_loaded_state_cannot_be_mutated(tmp_path):
    state = load_sources([str(three_good(tmp_path))])
    with pytest.raises(TypeError):
        state.packs["sneaky"] = None  # type: ignore[index]
    with pytest.raises(AttributeError):  # frozen dataclass
        state.state_id = "different"  # type: ignore[misc]
