# SPDX-License-Identifier: Apache-2.0
"""Sources -> one immutable snapshot, or nothing at all (NL-15).

**Fail-all-or-nothing.** A source tree with three good packs and one broken one
loads *nothing*. The alternative — load what parses, skip what does not — is
worse than it sounds: the service comes up looking healthy, answers most
questions, and silently cannot answer the ones the broken pack was for. Nobody
notices until a user does. So a single ERROR anywhere raises `LoadError` with
every diagnostic from every file, and the previously loaded snapshot (if any)
keeps serving untouched.

**One validation code path.** ``ttr-nlp validate`` on a laptop, the service's
boot load, and ``ReloadPacks`` in the cluster all run the functions in this
module. That is a design constraint, not a convenience: a pack author whose file
passes locally and fails in the cluster has no way to tell which of the two is
wrong, and the DFP model-validator wraps this rather than reimplementing it.
``validate.py`` is the collect-don't-raise face of the same code, and it collects
by *not* raising rather than by walking a second path.

**Reading is separated from judging.** ``gather_sources`` reads and compiles,
returning diagnostics alongside whatever it did manage to build; the two callers
then decide what to do about them. This is what lets the CLI report all six typos
in one run while the service refuses the whole snapshot on the first.

**What a source is.** A directory (globbed for ``**/*.pack.yaml`` and
``**/*.list.yaml``), a single file of either kind, or an ``http(s)`` URL naming
one file. The URL lane keeps ``httpx`` in the ``[http]`` extra and imports it
lazily, so the mandatory dependency set stays as small as ⚑NLS-D3 needs.

**Unreadable sources are diagnostics, not exceptions.** A packs directory that is
not there is nearly always a volume that failed to mount, and the service must
come up saying so — a crash loop puts the reason in logs nobody is reading yet,
and Analyze does not need packs to work. The CLI, whose sources come from a human
rather than from config, checks its own arguments first and exits 2 for a path
that does not exist (contracts §9); everything this module reports is exit 1.
"""

from __future__ import annotations

import hashlib
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from types import MappingProxyType
from typing import Any, Literal

from ttrnlp.gazetteer.annotate import Gazetteer, build_gazetteer
from ttrnlp.gazetteer.lists import GazetteerList, load_list
from ttrnlp.packs.diag import (
    NLS_PACK_001,
    NLS_PACK_003,
    NLS_PACK_004,
    Diagnostic,
    PackError,
    error,
)
from ttrnlp.rules import build_pack
from ttrnlp.rules.compiler import CompiledPack

PACK_SUFFIX = ".pack.yaml"
LIST_SUFFIX = ".list.yaml"

FileKind = Literal["pack", "list"]


class LoadError(Exception):
    """No snapshot was built. Carries every diagnostic from every source.

    Distinct from ``PackError``, which is one file's problem: this is the
    fail-all verdict over a whole set of sources, and the service turns it into
    NOT_READY plus the diagnostics an operator reads from ``GetStatus``.
    """

    def __init__(self, diagnostics: Sequence[Diagnostic]):
        self.diagnostics: list[Diagnostic] = list(diagnostics)
        errors = [d for d in self.diagnostics if d.severity == "ERROR"]
        summary = "; ".join(d.message for d in errors[:3])
        more = f" (+{len(errors) - 3} more)" if len(errors) > 3 else ""
        super().__init__(
            f"{len(errors)} error(s) loading packs and lists: {summary}{more}"
            if errors
            else "packs and lists could not be loaded"
        )

    @property
    def codes(self) -> list[str]:
        return [d.code for d in self.diagnostics]


@dataclass(frozen=True)
class SourceFile:
    """One file read from one source, with the hash the state id is built from."""

    source: str
    path: str
    kind: FileKind
    text: str

    @property
    def digest(self) -> str:
        return hashlib.sha256(self.text.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class LoadedState:
    """An immutable snapshot: everything loaded, nothing half-loaded.

    Held by the service and replaced wholesale on reload, never mutated — which
    is what makes ``ReloadPacks``' atomic swap a pointer assignment and lets
    in-flight requests finish against the snapshot they started on.

    ``loaded_at`` is passed in rather than read from the clock here. A snapshot
    that timestamps itself cannot be tested for equality, and the caller knows
    which clock it wants (the service's, or a fixed one in a test).
    """

    packs: Mapping[str, CompiledPack]
    lists: Mapping[str, GazetteerList]
    gazetteer: Gazetteer
    state_id: str
    loaded_at: str = ""
    files: tuple[SourceFile, ...] = ()
    diagnostics: tuple[Diagnostic, ...] = ()

    @property
    def packs_loaded(self) -> int:
        return len(self.packs)

    @property
    def lists_loaded(self) -> int:
        return len(self.lists)

    def phase_names(self, pack_id: str) -> tuple[str, ...]:
        pack = self.packs.get(pack_id)
        return tuple(phase.phase for phase in pack.phases) if pack else ()


@dataclass
class Gathered:
    """What reading and compiling a set of sources produced.

    Both the compiled material *and* the complaints, because the two callers
    want different things: the CLI wants every complaint and no material, the
    service wants the material only if there are no complaints at all.
    """

    packs: dict[str, CompiledPack] = field(default_factory=dict)
    lists: dict[str, GazetteerList] = field(default_factory=dict)
    files: list[SourceFile] = field(default_factory=list)
    diagnostics: list[Diagnostic] = field(default_factory=list)
    #: (kind, id) -> the file that declared it, so a duplicate names both sides.
    origins: dict[tuple[FileKind, str], str] = field(default_factory=dict)

    @property
    def has_errors(self) -> bool:
        return any(d.severity == "ERROR" for d in self.diagnostics)


# ── reading ──────────────────────────────────────────────────────────────────


def _kind_of(name: str) -> FileKind | None:
    if name.endswith(PACK_SUFFIX):
        return "pack"
    if name.endswith(LIST_SUFFIX):
        return "list"
    return None


def _is_url(source: str) -> bool:
    return source.startswith(("http://", "https://"))


def _read_url(source: str) -> tuple[list[SourceFile], list[Diagnostic]]:
    """Fetch one file over HTTP. ``httpx`` comes from the ``[http]`` extra."""
    try:
        import httpx
    except ImportError:
        return [], [
            error(
                NLS_PACK_001,
                "an http(s) pack source needs the `http` extra — "
                "install `ttr-nlp[http]`",
                source=source,
            )
        ]

    kind = _kind_of(source.split("?")[0])
    if kind is None:
        return [], [
            error(
                NLS_PACK_001,
                f"a URL source must name a `{PACK_SUFFIX}` or `{LIST_SUFFIX}` file "
                "— one URL is one file, and the suffix is how its kind is known",
                source=source,
            )
        ]

    try:
        response = httpx.get(source, follow_redirects=True, timeout=30.0)
        response.raise_for_status()
    except Exception as exc:
        # Deliberately broad: connection errors, TLS failures, timeouts and HTTP
        # status errors are all "this source did not arrive", and the caller's
        # response to each is identical. The message carries the detail.
        return [], [
            error(
                NLS_PACK_001,
                f"could not fetch {source}: {type(exc).__name__}: {exc}",
                source=source,
            )
        ]

    return [SourceFile(source=source, path=source, kind=kind, text=response.text)], []


#: What reading a file can fail with. `OSError` is the missing/unreadable half;
#: `UnicodeDecodeError` (a `ValueError`, NOT an `OSError`) is the other one, and
#: it is the likelier of the two in a cluster: one stray byte in a mounted
#: configmap is a decode error, and leaving it unguarded turned a diagnostic into
#: an exception escaping `load_sources` — a boot crash, or an UNKNOWN on
#: `ReloadPacks`. See the module docstring: unreadable sources are diagnostics.
_READ_FAILURES = (OSError, UnicodeDecodeError)


def _read_file(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _in_tree(root: Path, path: Path) -> bool:
    """A file of the source's tree, not one under a hidden directory in it.

    A Kubernetes ConfigMap volume — how a deployment mounts its packs and lists —
    holds every key twice: `<key>`, a symlink through `..data`, and
    `..<timestamp>/<key>`, the file itself. `rglob` walks the timestamped
    directory, so every pack and list in a mounted ConfigMap was its own
    duplicate id (NLS-PACK-003) and nothing loaded. The top-level names are the
    content; a dot-directory never is.
    """
    return not any(part.startswith(".") for part in path.relative_to(root).parts[:-1])


def _read_dir(source: str, root: Path) -> tuple[list[SourceFile], list[Diagnostic]]:
    files: list[SourceFile] = []
    problems: list[Diagnostic] = []
    for suffix, kind in ((PACK_SUFFIX, "pack"), (LIST_SUFFIX, "list")):
        for path in sorted(p for p in root.rglob(f"*{suffix}") if _in_tree(root, p)):
            try:
                text = _read_file(path)
            except _READ_FAILURES as exc:
                # Collected, not returned: bailing on the first bad file left
                # every later pack — and every `*.list.yaml`, which is a whole
                # second suffix pass — unread and unreported, so the author fixed
                # one unreadable file only to be told about the next. Nothing is
                # loaded either way (fail-all), so reading on costs nothing and
                # names every problem in one run.
                problems.append(
                    error(NLS_PACK_001, f"could not read {path}: {exc}", source=source)
                )
                continue
            files.append(
                SourceFile(source=source, path=str(path), kind=kind, text=text)
            )
    return files, problems


def read_sources(sources: Iterable[str]) -> tuple[list[SourceFile], list[Diagnostic]]:
    """Read every file every source names, in a stable order.

    Sorted per source and sources in the order given, so the state id and the
    diagnostic order are both deterministic — a reload that changed nothing must
    produce the same id, or `ReloadPacks` reports a new snapshot every time.
    """
    files: list[SourceFile] = []
    diagnostics: list[Diagnostic] = []

    for source in sources:
        if _is_url(source):
            found, problems = _read_url(source)
        else:
            path = Path(source)
            if path.is_dir():
                found, problems = _read_dir(source, path)
            elif path.is_file():
                kind = _kind_of(path.name)
                if kind is None:
                    found, problems = [], [
                        error(
                            NLS_PACK_001,
                            f"{path.name} is neither a `{PACK_SUFFIX}` nor a "
                            f"`{LIST_SUFFIX}` file",
                            source=source,
                        )
                    ]
                else:
                    try:
                        found, problems = [
                            SourceFile(
                                source=source,
                                path=str(path),
                                kind=kind,
                                text=_read_file(path),
                            )
                        ], []
                    except _READ_FAILURES as exc:
                        found, problems = [], [
                            error(
                                NLS_PACK_001,
                                f"could not read {path}: {exc}",
                                source=source,
                            )
                        ]
            else:
                found, problems = [], [
                    error(
                        NLS_PACK_001,
                        f"source does not exist: {source} — a directory that is "
                        "not there is usually a volume that failed to mount",
                        source=source,
                    )
                ]
        files.extend(found)
        diagnostics.extend(problems)

    return files, diagnostics


def state_id_for(files: Iterable[SourceFile]) -> str:
    """An opaque id for exactly this content.

    Same files with the same bytes ⇒ same id, so an operator can tell a reload
    that changed something from one that did not. Keyed on (path, content hash)
    rather than on mtimes or a counter: a redeployed config map has new mtimes
    and identical content, and calling that a new snapshot would be a lie.
    """
    digest = hashlib.sha256()
    for path, content in sorted((f.path, f.digest) for f in files):
        digest.update(path.encode("utf-8"))
        digest.update(b"\0")
        digest.update(content.encode("utf-8"))
        digest.update(b"\n")
    return digest.hexdigest()[:16]


# ── compiling ────────────────────────────────────────────────────────────────


def gather_sources(sources: Iterable[str]) -> Gathered:
    """Read, parse, cross-check and compile everything — collecting complaints.

    This is the single code path. It does not decide anything: a caller that
    wants a snapshot raises on ``has_errors``, and a caller that wants a report
    prints ``diagnostics``.
    """
    gathered = Gathered()
    files, read_problems = read_sources(sources)
    gathered.files = files
    gathered.diagnostics.extend(read_problems)

    for file in files:
        if file.kind == "pack":
            _gather_pack(gathered, file)
        else:
            _gather_list(gathered, file)

    return gathered


def _gather_pack(gathered: Gathered, file: SourceFile) -> None:
    try:
        compiled = build_pack(file.text, source=file.path)
    except PackError as exc:
        gathered.diagnostics.extend(exc.diagnostics)
        return

    if compiled.pack in gathered.packs:
        # T5. Two packs claiming one id: pipelines name packs by id, so which of
        # the two a `rules: [{pack: …}]` ref meant is unanswerable — and the
        # second would have silently replaced the first in the dict.
        gathered.diagnostics.append(
            error(
                NLS_PACK_001,
                f"duplicate pack id {compiled.pack!r}: also declared by "
                f"{gathered.origins[('pack', compiled.pack)]} — pipelines "
                "reference packs by id",
                source=file.path,
                pack=compiled.pack,
            )
        )
        return

    gathered.packs[compiled.pack] = compiled
    gathered.origins["pack", compiled.pack] = file.path


def _gather_list(gathered: Gathered, file: SourceFile) -> None:
    try:
        loaded = load_list(file.text, source=file.path)
    except PackError as exc:
        gathered.diagnostics.extend(exc.diagnostics)
        return

    if loaded.id in gathered.lists:
        gathered.diagnostics.append(
            error(
                NLS_PACK_003,
                f"duplicate list id {loaded.id!r}: also declared by "
                f"{gathered.origins[('list', loaded.id)]} — pipelines reference "
                "lists by id and every Lookup records it as `source`",
                source=file.path,
                pack=loaded.id,
            )
        )
        return

    gathered.lists[loaded.id] = loaded
    gathered.origins["list", loaded.id] = file.path


# ── pipeline references (NLS-PACK-004) ───────────────────────────────────────


def check_pipelines(
    gathered: Gathered, pipelines: Mapping[str, Any] | None
) -> list[Diagnostic]:
    """Every pipeline ref must resolve against what was actually loaded.

    Checked here rather than in the service's config model, because a ref is only
    wrong relative to a *snapshot*: the config is fine and the pack is fine, and
    the mismatch appears when they meet. It is therefore re-checked on every
    reload, not only at boot — a reload that removed a phase must be refused, not
    applied and discovered at the next request (contracts §7: "fail-all applies
    to the pipeline table too").

    The table is read as a plain mapping on purpose. Its schema belongs to the
    service (contracts §7); duplicating it here would give the wheel an opinion
    about service config it has no business having.
    """
    if not pipelines:
        return []

    diagnostics: list[Diagnostic] = []
    for name, spec in pipelines.items():
        where = f"pipelines.{name}"
        if not isinstance(spec, Mapping):
            diagnostics.append(
                error(
                    NLS_PACK_004,
                    f"{where}: expected a mapping of ops/gazetteer/rules, "
                    f"found {type(spec).__name__}",
                )
            )
            continue

        for index, list_id in enumerate(spec.get("gazetteer") or []):
            if list_id not in gathered.lists:
                known = ", ".join(sorted(gathered.lists)) or "none loaded"
                diagnostics.append(
                    error(
                        NLS_PACK_004,
                        f"{where}.gazetteer[{index}]: no list {list_id!r} "
                        f"(loaded: {known})",
                    )
                )

        for index, ref in enumerate(spec.get("rules") or []):
            at = f"{where}.rules[{index}]"
            if not isinstance(ref, Mapping):
                diagnostics.append(
                    error(
                        NLS_PACK_004,
                        f"{at}: expected {{pack, phase}}, found "
                        f"{type(ref).__name__}",
                    )
                )
                continue
            pack_id = ref.get("pack")
            phase = ref.get("phase")
            compiled = gathered.packs.get(pack_id)
            if compiled is None:
                known = ", ".join(sorted(gathered.packs)) or "none loaded"
                diagnostics.append(
                    error(
                        NLS_PACK_004,
                        f"{at}: no pack {pack_id!r} (loaded: {known})",
                    )
                )
                continue
            phases = [p.phase for p in compiled.phases]
            if phase not in phases:
                diagnostics.append(
                    error(
                        NLS_PACK_004,
                        f"{at}: pack {pack_id!r} has no phase {phase!r} "
                        f"(it has: {', '.join(phases)})",
                        pack=pack_id,
                    )
                )

    return diagnostics


# ── the two faces ────────────────────────────────────────────────────────────


def load_sources(
    sources: Iterable[str],
    *,
    pipelines: Mapping[str, Any] | None = None,
    loaded_at: str = "",
) -> LoadedState:
    """Load everything, or nothing.

    Args:
        sources: Directories, single files, or ``http(s)`` URLs.
        pipelines: The service's pipeline table (contracts §7), if there is one.
            Its references are validated against what loaded.
        loaded_at: A timestamp for the snapshot, from the caller's clock.

    Returns:
        An immutable ``LoadedState``.

    Raises:
        LoadError: If anything at all was wrong, with every diagnostic found.
            Nothing is loaded — see the module docstring on why partial loading
            is the worse failure.
    """
    gathered = gather_sources(sources)
    gathered.diagnostics.extend(check_pipelines(gathered, pipelines))

    if gathered.has_errors:
        raise LoadError(gathered.diagnostics)

    return LoadedState(
        packs=MappingProxyType(dict(gathered.packs)),
        lists=MappingProxyType(dict(gathered.lists)),
        gazetteer=build_gazetteer(gathered.lists.values()),
        state_id=state_id_for(gathered.files),
        loaded_at=loaded_at,
        files=tuple(gathered.files),
        diagnostics=tuple(gathered.diagnostics),
    )


__all__ = [
    "LIST_SUFFIX",
    "PACK_SUFFIX",
    "Gathered",
    "LoadError",
    "LoadedState",
    "SourceFile",
    "check_pipelines",
    "gather_sources",
    "load_sources",
    "read_sources",
    "state_id_for",
]
