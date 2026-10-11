# SPDX-License-Identifier: Apache-2.0
"""Layer-file export, and the gate on it (contracts §7; NLS-P9.2 T2/T6).

*"DB works, files publish."* This module is the second half of that sentence.
What leaves the database is a `*.morph.yaml` layer file in exactly the shape
contracts §3 specifies — the same shape a hand-authored layer has, written by
the same renderer the importers use — so an exported layer is reviewable as a
diff, ownable by a world, and compilable by the same `ttr-morph compile` that
builds the published snapshot.

**The gate is one line and it is the point** (`store.exportable_entries`):
`verified` and above. An entry at `proposed` is a hypothesis and an entry at
`auto-validated` is a hypothesis the engine could not refute; neither is a fact
about Czech, and the artifact this feeds ships to every deployment.

**A corrected form makes a full-form entry.** If a reviewer edited a cell of the
generated table, the entry no longer *is* its pattern — writing it out as
`vzor: hrad` would mean the compiler regenerates the uncorrected paradigm and
raises `LM-MORPH-005`, or worse, silently ships the version the reviewer
rejected. So those export as `forms:` entries, carrying the rejected pattern in
the `vzor` field exactly as the importers do, which is what keeps the mismatch
visible instead of losing it.

**Retractions are written down, not merely absent.** A rejected entry leaves the
export by disappearing from it, and a diff of a two-thousand-line generated file
shows a removal that nobody can attribute. So the header carries the retraction
list: which lexemes went, and why they are not simply missing (LM-12's world
lane reads that header by hand).
"""

from __future__ import annotations

from dataclasses import dataclass, field

from sqlalchemy.orm import Session
from ttrmorph.compile.layers import LICENSE_SUITE, WORLD_LICENSE_PREFIX
from ttrmorph.enrich.cascade import LAYER_CORE, LAYER_WORLD
from ttrmorph.importers.emit import LayerHeader, forms_entry, render_layer, vzor_entry

from morph_studio import status as st
from morph_studio.config import Settings
from morph_studio.models import Entry
from morph_studio.store import exportable_entries

#: The layer ids this service writes. `core-studio` sits beside `core-hand` and
#: the importer layers in `lexicon/cs/LAYERS`; the world's id is its own.
CORE_LAYER_ID = "core-studio"


def world_layer_id(world: str) -> str:
    return f"{world}-entities"


@dataclass
class ExportResult:
    """What an export produced, without having written anything yet."""

    files: dict[str, str] = field(default_factory=dict)
    #: `(lemma, upos)` of everything the gate dropped, by reason. Reported back
    #: on the endpoint: "nothing was exported" and "nothing is ready" are very
    #: different answers to a reviewer who just pressed the button.
    withheld: dict[str, int] = field(default_factory=dict)
    exported: int = 0


def export_layers(
    session: Session, settings: Settings, *, retractions: list[str] | None = None
) -> ExportResult:
    """The core layer and the world layer, gated (contracts §7)."""
    result = ExportResult()
    counts = _withheld(session)
    result.withheld = counts

    for layer, layer_id, license_ in (
        (LAYER_CORE, CORE_LAYER_ID, LICENSE_SUITE),
        (
            LAYER_WORLD,
            world_layer_id(settings.world),
            WORLD_LICENSE_PREFIX + settings.world,
        ),
    ):
        entries = exportable_entries(session, layer)
        text = render_layer(
            LayerHeader(
                layer=layer_id,
                language="cs",
                license=license_,
                note=_note(layer, settings, len(entries), retractions or []),
            ),
            [entry_document(entry) for entry in entries],
        )
        result.files[f"{layer_id}.morph.yaml"] = text
        result.exported += len(entries)

    return result


def export_proposals(session: Session, settings: Settings) -> ExportResult:
    """The DFP lane (LM-12): *proposed*-entry fragments, not published truth.

    That world's analysts edit their layer as files in their model repository, so
    what the queue owes them is not a UI and not a finished layer — it is the
    material to review, in the file format their own validator already runs over.
    Which is why this endpoint exports the entries the other one deliberately
    withholds, and marks every one of them.

    ⚑ **Split by layer, exactly as `export_layers` is.** One fragment carrying
    everything below the gate put every core-routed proposal into a file
    rendered under `world:<id>` — LM-10 routes to `core` precisely the entries
    that are *not* the world's, so the fragment relabelled them as that world's
    material, in that world's repository, under that world's licence. Dropping
    them instead would have been the other wrong answer: the core queue is
    reviewed too, and it is reviewed here. So both are emitted, each under the
    licence it belongs to, which is the same rule the published export follows.
    """
    result = ExportResult()
    for layer, layer_id, license_ in (
        (LAYER_CORE, f"{CORE_LAYER_ID}-proposed", LICENSE_SUITE),
        (
            LAYER_WORLD,
            f"{settings.world}-proposed",
            WORLD_LICENSE_PREFIX + settings.world,
        ),
    ):
        entries = _proposed(session, layer)
        result.files[f"{layer_id}.morph.yaml"] = render_layer(
            LayerHeader(
                layer=layer_id,
                language="cs",
                license=license_,
                note=_proposed_note(layer, settings, len(entries)),
            ),
            [entry_document(entry, proposed=True) for entry in entries],
        )
        result.exported += len(entries)
    return result


def write(result: ExportResult, directory: str) -> list[str]:
    """Write an export to disk. Returns the paths written."""
    from pathlib import Path

    target = Path(directory)
    target.mkdir(parents=True, exist_ok=True)
    written = []
    for name, text in sorted(result.files.items()):
        path = target / name
        path.write_text(text, encoding="utf-8")
        written.append(str(path))
    return written


# ── internals ────────────────────────────────────────────────────────────────


def entry_document(entry: Entry, *, proposed: bool = False) -> dict[str, object]:
    """One entry, as contracts §3 writes it."""
    corrected = [row for row in entry.forms if row.corrected]
    if corrected or not entry.vzor:
        document = forms_entry(
            entry.lemma,
            entry.upos,
            [(row.form, row.feats) for row in entry.forms],
            entry.provenance,
            vzor=entry.vzor or "",
            flags=tuple(entry.flags or ()),
        )
    else:
        document = vzor_entry(
            entry.lemma,
            entry.upos,
            entry.vzor,
            tuple(entry.flags or ()),
            entry.provenance,
        )
    if proposed and entry.provisional:
        # Q-7's mark travels into the file, because a fragment that did not say
        # which of its entries are already live in a world's overlay would leave
        # the reviewer unable to tell what rejecting one retracts.
        document["provisional"] = True
    return document


def _proposed(session: Session, layer: str) -> list[Entry]:
    """Everything below the gate on one layer — what `exportable_entries` drops."""
    from sqlalchemy import select

    return list(
        session.scalars(
            select(Entry)
            .where(
                Entry.layer == layer,
                Entry.status.in_([st.PROPOSED, st.AUTO_VALIDATED]),
            )
            .order_by(Entry.lemma, Entry.upos)
        ).unique()
    )


def _proposed_note(layer: str, settings: Settings, count: int) -> str:
    whose = (
        "the core analytical lexicon"
        if layer == LAYER_CORE
        else f"the {settings.world} world's entity layer"
    )
    merge_into = (
        "the core layers in `ttr-morph/lexicon/cs`"
        if layer == LAYER_CORE
        else "the world's own layer"
    )
    return (
        f"PROPOSED ENTRIES for {whose} (LM-12, morph-studio "
        f"{settings.mode} lane).\n\n"
        "These are the enrichment queue's proposals, NOT a publishable "
        "layer: every entry here is below `verified` and has been "
        "produced by the guesser or the LLM classifier, checked only in "
        "that the engine regenerates the observed form.\n\n"
        f"Review them in this file, correct what is wrong, and merge what "
        f"survives into {merge_into} — the model-validator CLI runs the "
        "same schema and regeneration checks the studio does.\n\n"
        f"{count} entries."
    )


def _withheld(session: Session) -> dict[str, int]:
    from sqlalchemy import func, select

    rows = session.execute(
        select(Entry.status, func.count(Entry.id)).group_by(Entry.status)
    ).all()
    return {
        status: count
        for status, count in rows
        if status not in st.EXPORTABLE
    }


def _note(layer: str, settings: Settings, count: int, retractions: list[str]) -> str:
    what = (
        "the core analytical lexicon"
        if layer == LAYER_CORE
        else f"the {settings.world} world's entity layer"
    )
    lines = [
        f"GENERATED by morph-studio ({settings.world}) — {what}.",
        "",
        "Do not hand-edit: this file is re-emitted whole by `POST /export`, and "
        "an edit here is lost at the next export while the database it came "
        "from still says the old thing. Correct entries in the studio.",
        "",
        f"{count} entries, all at `verified` or above — the export gate "
        "(contracts §7) drops everything below, which is every proposal the "
        "enrichment cascade has not had confirmed by a person.",
    ]
    if retractions:
        lines += [
            "",
            "RETRACTED since the last export (rejected; removed from this file "
            "and from any provisional overlay row):",
            *(f"  - {name}" for name in sorted(retractions)),
        ]
    return "\n".join(lines)


__all__ = [
    "CORE_LAYER_ID",
    "ExportResult",
    "entry_document",
    "export_layers",
    "export_proposals",
    "world_layer_id",
    "write",
]
