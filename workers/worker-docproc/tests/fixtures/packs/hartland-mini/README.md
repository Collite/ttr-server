# hartland-mini — the DX fixture pack

The one domain pack every DX phase tests against: six small documents, the extraction slice of the model, two feeds and
a human golden reference. P1's fixture corpus, P2's graphs, P3's kind goldens, P4's eval slice and P5's hero walk all read
these same files. It is the seed of the Hartland pack (DH Train 2; DX-P6 swaps the corpus). **It is not the Hartland
pack**: the products, customers and claims are invented for the fixtures, in Hartland's real categories.

Module tests read it in place: the root build sets the system property `dx.pack.dir` to this directory, so no module
copies it. ttr-server mirrors it byte for byte into `workers/worker-docproc/tests/fixtures/packs/hartland-mini/` (P3 S3.1).
`just fixtures-check` holds it to its rules.

## Feeds

| feed | families | documents |
|---|---|---|
| `mini-rag` | `split` · `chunk-recursive` · `chunk-sentence` · `lemmatize` · `embed-chunks`. The RAG profile (DX-D6), which needs no model. | the two sheets |
| `mini-extract` | `mini-rag` plus `import` · `classify-sections` · `cz-mentions` (primary) + `llm-mentions` (mentor) · `link-mentions` · `normalize-review` (primary) + `review-extract` (mentor) · `consolidate-review` · `records-review` · `compile` | the reviews and claims |

## Documents

| file | what | mart · legal entity · subject |
|---|---|---|
| `sheet-001.en.html` | the Aurora Desk Lamp product sheet: headings, a spec table, a list, about 600 words | hartland-public · hartland-us · none |
| `sheet-002.cs.html` | the Breeze Tower Fan sheet, in Czech. Its specs are one heading level deeper, under an `h2` with no text of its own. | hartland-public · hartland-cz · none |
| `review-001.en.json` | two reviews of the lamp (`json-reviews` shape): one negative, one "after two weeks…" | hartland-public · hartland-us · HL-1001 |
| `review-002.cs.json` | two Czech reviews of the fan: colloquial, with a typo (`penize`), and an emoji right before a mention (`😤 Breeze`) | hartland-public · hartland-cz · HL-2040 |
| `claim-001.cs.txt` | a plain-text reklamace of the Summit Kettle: a defect, a claimed amount, an uncertain phrasing ("asi vadný") | hartland-claims · hartland-cz · HL-4205 |
| `claim-002.en.md` | an English claim for the Pulse Wireless Headphones, quoting an earlier e-mail | hartland-claims · hartland-us · HL-3310 |

Everything is NFC. `registrations/<doc>.json` is each document's `RegisterAssetRequest` (contracts §2.2), with
`contentRef` in place of the bytes. The review files also carry an `initialBundle` of `Import.review` facts, recorded by
`import:json-reviews@1` (R-57).

## Model and types

`model/` is ttr-core's `hartland-dx` world, copied. Its header names the source commit; change the world there, then
re-copy. There is one deliberate addition, the P4 S4.2 seam: `review` and `claim` carry `annotation_refs` and
`consolidation_run`, tagged `"provenance"`. The record ETL writes them, the query door returns them, and the extraction
types leave them out (ttr-core#327). `types/generated/` is rendered, never edited:

```
cd <ttr-core> && just render-types --model <kantheon>/shared/proto/fixtures/dx/packs/hartland-mini/model \
    --entities review,claim --out <kantheon>/shared/proto/fixtures/dx/packs/hartland-mini
```

`index.json` pins `snapshotHash`, the content hash of `model/` (contracts §6). `fixtures-check` recomputes it, and each
file's sha256. `types/registry.yaml` holds the four model types; the structural set is P1 S1.1's `types/engine.yaml`.

## The golden reference (`golden/mini-gold-v1/`)

The human truth, per document in `<doc>.expected.json`:

- **`sections`, for all six documents.** Each has its exact NFC text, `contentHash`, `sectionPath` and, for
  mini-extract documents, the expected `sectionKind`.
- **`chunks`.** `chunk-recursive` boundaries for the two sheets.
- **`mentions`.** Spans, surfaces and the linked member, for the four review and claim documents.
- **`imports` and `statements`.** For the two review files.

`records/review.rows.json` holds the expected `review` table rows. `split.yaml` is the eval slice: train and test by
subject, with `review-002.cs.json` and `claim-001.cs.txt` on the test side. `queries.yaml` has 12 retrieval questions in
EN and CZ: two only a concept walk answers, two in colloquial Czech.

**Ids.** `g:<doc>:<kind><n>`, where `<doc>` is the file name without its last extension and `<kind>` is one of `s`
(section), `c` (chunk), `m` (mention), `i` (import fact) or `st` (statement); `<n>` counts within the document. Tests
resolve golden ids. They are never DB ids. Inside a statement payload, `spans[].unitId` is the section's `idx`.

**Offsets** are Unicode code points over the section's NFC text, end exclusive. `fixtures-check` slices every mention
span against its `surface`, and every statement span against its `verbatim`.

**How the sections are cut.** This is the rule P2 S2.2's `split` is held to. Sections are built on docwh-store's
`DocumentParser`, which P2 moves to `doc-parser` unchanged. The golden texts were taken from that parser's output on
these files.

- **Headings** (`sections: headings`). Every heading of any level opens a section, titled with the heading. The
  section's text is the parser's blocks up to the next heading, joined by `\n\n`; the heading itself is not in the text.
  A heading with no blocks of its own makes no section and appears only in its children's `sectionPath` (the Czech
  "Technické parametry"). Blocks before the first heading form a section titled with the document title. Plain text
  has no headings, so it is one untitled section of the parser's paragraphs.
- **What the parser does to the text.** HTML tables flatten to one line, with a double space between cells. Plain text
  merges paragraphs under 30 words into the next one, so the claim's greeting joins its body. A Markdown soft line
  break disappears, so these files keep each paragraph on one line.
- **JSON** (the split manifest's `json` block). One section per array element; the text is `title` + `\n\n` + `body`,
  and the title is `title`.

**Chunks** (`chunk-recursive`, `max_tokens: 200` on `cl100k_base`). A section that fits is one chunk. One that does not
is split on the first separator it contains, in the order `\n\n`, `\n`, `. `, ` `. The pieces are then merged greedily,
while the merged slice still fits, and an oversized piece recurses with the next separator. The separator between two
chunks belongs to neither. Each sheet has one section that splits.

**Mentions.** These are the named item mentions, each linked to `cn.Item` with its sku `member`, plus one deliberate
abstention: `novou konvici` in `claim-001` ("a new kettle") names no item, so `link` must abstain. Pronoun-like
references ("Konvici jsem používala…") are not golden mentions.

**Statements and rows.** The statements are complete, as a human reads each review: sentiment, temporal context and
concept included. That is `review-extract`'s target. `normalize-review`'s rules emit only item, rating and verification
(P3 S3.3 T4). The rows are the consolidation of the golden statements, one per review event (`consolidation.yaml`).
`review_id` (an identity column) and `consolidation_run` are left out of the rows; tests check that `consolidation_run`
is not empty. `annotation_refs` lists the evidence statements as golden ids.

## Open items for the stages that read the pack

- **⚑PX-1 · P2 S2.1.** `link-mentions` consumes `Mention[item]` and re-emits linked `Mention[item]`s derived from them,
  as P3 S3.3 T2 specifies. The graph checks (`graph/cycle`, `graph/two-primaries`) have to treat a family that refines
  the type it consumes as a refinement. Otherwise this graph is refused.
- **⚑PX-2 · P3 S3.3 / P4.** Two families produce `Statement[review]`: the deterministic `normalize-review` and the LLM
  `review-extract`. `review-extract` is a **mentor** here (planned SHADOW), so the feed has one primary. P4 decides when
  the LLM takes over.
- **⚑PX-3 · PX T1 amended.** The `Import.review` facts carry a raw-text span over their review's `body`
  (`{unitId: -1, start, end}`, contracts §2.2), not "no spans". P2 S2.2's split golden re-anchors them; the emoji before
  a mention must not shift them. The payload carries the unit-level facts (rating, createdAt, verified, reviewId).
- **⚑PX-4 · P1 pre-flight, DX-D8.** `index.json` still says `feedsGold` (ttr-core's renderer), while `registry.yaml`
  says `feeds_records` (contracts §6). The rename reaches the renderer with DX-D8.
- **⚑PX-5 · plan.** `dx.pack.dir` is the pack root (`…/packs/hartland-mini`), not `…/packs`. P1 S1.3 reads
  `registrations/` under it, and P2 S2.1 reads `dx.pack.dir/../../schemas/`.
- **`prompts/`** stays empty until P4 S4.1.
