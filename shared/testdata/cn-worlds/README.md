# cn-worlds — golden worlds the cn doors are seeded over (CN-P5 S5.1, CN-P6)

Two model roots from ttr-concept's own golden suite, so the doors' `calls:` seeds
(`services/resolver/src/test/resources/conformance/calls/cn-*.json`) run the frames ttr-concept's goldens pin, through the
door. Invented data: Hartland-style demo names, `X21`-style clinical codes, never client content.

| world | what the seeds use it for | layers |
|---|---|---|
| `sales` | `statement.*` — the md hero with concepts over it (`revenue` by `ActiveCustomer`, ⚑CN-P9's semi-join); nothing lands on er, so its phases are the er refusals | md + db, cn, automation |
| `clinic` | `assertion.*` (the Transfer assertion) and `definition.render` on er (DC-1, DC-12 with `{as_of}`) | er + db, cn |
| `calendar-key` | `statement.lower` on the er lane (CN-P6 ⚑S62-1, A-CN-101): per-channel md with no channel and no date grain, a stacked `sales`, every sale dated by an integer key into `date_dim` (`event_date`) | md + er + db, cn |

The `cn-estate` (`../cn-estate/`) is the third: it has a lexicon, so `concept.search` is seeded over it.

## Source

A byte-for-byte copy of ttr-core's `packages/kotlin/ttr-concept/src/test/resources/worlds/{sales,clinic}/*.ttrm` at
`grammar/v0.18.2` (`959d7906`), and of `calendar-key/*.ttrm` at `grammar/v0.18.10` (`14ca1ef8`) — the model files only (not `live-seed.sql` or `definition-diagnostics.txt`). ttr-core's
copy is the source: change it there, then copy it here again (`diff -r` against the source shows any drift). The files are
hash-pinned in `conformance/corpus-hashes.sha256`; a re-copy bumps those hashes in the same commit.

No lexicon archive goes with them, so a door over one pins its model to the model's own content id (`ConceptModels`: no
archive names a model).
