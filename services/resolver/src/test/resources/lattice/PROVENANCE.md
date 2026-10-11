<!-- SPDX-License-Identifier: Apache-2.0 -->
# `lattice/` — where these fixtures come from

RV-P2.1.T2, extended by RV-P2.5.T4. Four cases, each a triple: a **case file** (what the estate knows), a **parse file**
(what nlp returned), and a **golden** (the whole `ResolutionState` the core must emit).

| id | question | pins |
|---|---|---|
| `h1-cs` | *Zobraz náklady účtu 501001 v roce 2025 podle období* | the 0-LLM hero — gap-free, `op:show` bound, the code attributed to the **account** because the user said *účtu* |
| `h1prime-cs` | …*účtu 5010O1*… | identical mentions + identical frame roles, one `G4_METHOD_MISS` |
| `h2-cs` | *Zobraz prvních 10 čerpacích stanic v Praze podle tržby za 12 měsíců.* | `G1_UNBOUND` on the unknown **SUBJECT**, `G3_UNATTRIBUTED` on the LOCATION hint |
| `h5-cs` | *Ukaž vývoj nákladů střediska 220 za posledních 12 měsíců a porovnej s plánem.* | the operator layer — **three** `op:` bindings in one question, via the ordinary path; `plánem` an honest `G1_UNBOUND` |

## The parses are real; the NER layer is authored

`*.parse.json` is the **cached Stanza output** from the RV-P0.2 (Q-15) frame-role spike —
`project/kantheon/features/resolving/implementation/spikes/frame-roles/parses/`, fetched from
`services/nlp`'s Stanza backend (`nlp-stanza:dev`, stanza 1.13.0), the cs `DEP_PARSE` hot
path. Tokens, lemmas, UPOS, `dep_head` and `dep_relation` are copied field-for-field; only the
spike's derived `depHeadIdx` (a harness convenience, not a proto field) is dropped.

**The `entities` list is the one authored part**, and it matters, so it is named here: the spike
requested `TOKENIZE / LEMMATIZE / POS_TAG / DEP_PARSE` only, while the resolver also asks for
`NER`. The entities added are the ones a cs NER front does produce and the design's own hero
renderings assume:

- `h1-cs` / `h1prime-cs` — `2025` as `DATE`. design.md H1: *"`2025` → chrono grounding"*.
- `h2-cs` — `Praze` as `LOC` (CNEC `gu`) and `12 měsíců` as `DATE`. 02-B §H2: *"`Praze` → value-like
  span, no anchor ⇒ **G3** (universal LOCATION hint from NER)"*.
- `h5-cs` — `posledních 12 měsíců` as `DATE`. The **whole** phrase, not the bare `12 měsíců`:
  *posledních* is what makes it the relative window design.md H5 asks for. `220` is deliberately
  NOT tagged, for the same reason `501001` is not — it must reach the member index.

Nothing else was invented: no entity is added that would remove a span from the domain path and
thereby manufacture a pass. `501001` is deliberately **not** NER-tagged — it must reach the
member index, which is the whole point of H1.

Consequence worth stating: because `12 měsíců` is one NER span, `měsíců` is not a separate
mention here, while the Q-15 fixture corpus (which had no NER) scores it as one. Both are right
for their input; the frame-role corpus is re-run against its own mention list in
`FrameRolesFixtureTest`, so the two never disagree about the rules.

## The vocabulary is a fixture, not a snapshot

`matcher.byQuery` is what **lex-matcher** would answer once an estate ships a compiled lexicon
(RV-P1.2/P1.4) — declared rows carrying `target_ref` + `target_class` + the authored
`match_method`, member rows carrying a data PK. The delivery chain that produces a real archive
is `p1-7`…`p1-9`; until it lands, no estate can serve these rows, so the fixture states them.
The registry override in each case file is the same estate seen from the resolver's side.

`h1-cs`'s registry is the **structural fix for `issues.md` §"Looking in wrong entity"**: the
account entity declares its anchor word, so `501001` is looked up in `md.dimension.Account.code`
and never offered `db.dbo.STREDISKO.KOD` at 0.667.

## Regenerating

`python3 regenerate-parses.py <path-to>/implementation/spikes/frame-roles` re-copies the parses
from the spike cache and re-applies the authored NER layer. Do not hand-edit tokens. The goldens are authored from
`contracts.md` §1 and the fixture gold, then confirmed against the emission; on a mismatch the
test writes what the core actually produced to `build/lattice-actual/<id>.lattice.json`.

---

## MS (review-084 F4, 2026-09-01) — `ms-shared-anchor-cs`, a case with no golden

A fifth case file joins the four above, and it is a different kind of thing: it has **no
`.lattice.json`**. It exists to run the MS-P3 chain — span-proposal merge → the Binder's
declared-containment collapse → the frame-role split — through the real `ResolverPipeline` and
assert three facts about the result, rather than to freeze an emission.

**Why it was needed.** Every case above declares no `ownerRef` and gives each anchor exactly ONE
owner, and `latticeWith` did not carry an owner onto the vocabulary it built. So MS-P3 could
report "no golden moved" truthfully while none of them *could* have moved: the merge and the
collapse had no end-to-end coverage anywhere in the module.

**Why it has no parse of its own.** Its `text` is `h2-cs`'s, character for character, and its
`parseFile` is `h2-cs.parse.json` — the `ms.yaml` technique from `frame-roles/PROVENANCE.md`
§MS. Hand-authoring a Czech dependency tree to test rules that read dependency trees proves only
that the tree matches the expectation. Holding the parse fixed makes the MODEL the only variable.

**What differs from `h2-cs`, and it is one thing.** `tržba` is declared by **two** owners — the
sales entity, which declares measures, and its own measure attribute, which declares the entity
as its `ownerRef` — and `matcher.byQuery["tržby"]` answers with one EXACT row per category. That
is the shape every real mention facet produces, and the shape no case here had.

It is asserted on the **snapshot** channel (`latticeWith`, no per-request override), because that
is the channel an estate actually uses, and it is asserted twice: once as declared, and once with
`ownerRef` blanked, which is what a pre-v3 archive serves. The control comes back with two
bindings and a `G2_AMBIGUOUS`; the declared run comes back with one binding to the measure and
`SUBJECT, MEASURE`. The pair is the diff artefact the MS-P4·T3 live chain drill should be read
against.
