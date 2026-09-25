# lex-matcher

> **forked-from:** `ai-platform@2575b923dca521fea0e3156257e4b779f02a6ed4` (`services/fuzzy-matcher/` + `tools/fuzzy-mcp/`), tag `kantheon-fork-point`, forked 2026-06-13 (Stage 2.2).
> Maintained independently since the fork; do not assume parity with the ai-platform original.

**lex-matcher** — the Czech-aware fuzzy matcher for kantheon. Given a user term and an
entity type, it returns the best-matching catalog entries through an algorithm
cascade (TATRMAN / LEVENSHTEIN / JARO_WINKLER), tuned for Czech diacritics and
morphology. Themis and Golem call it (via `tools/lex-matcher-mcp`) to resolve fuzzy
entity references before query construction.

## Surface

Proto `org.tatrman.fuzzy.v1` / `FuzzyService` (`com.tatrman.fuzzy.v1` /
`FuzzyMatcherService` are gone — renamed at fork, wire shapes unchanged):

- **Match** — fuzzy-match a term against the entity catalog; the algorithm is a
  request arg (`TATRMAN | LEVENSHTEIN | JARO_WINKLER`). Returns scored matches
  (`FuzzyMatchResponse`). The proto carries no Rule-6 add — the fork principle is
  "wire shapes fork unchanged"; the only additive exception is the Veles hop.

`tools/lex-matcher-mcp` wraps this as the single `match` tool (capability id
`fuzzy.match:v1`).

## Loader sources

The entity catalog populates from one of two loader sources (opt-in via config):

- **Static** (default; local/CI) — reads an in-repo JSON catalog. No external
  dependency; the lean carve-out the fork landed on.
- **DB-backed** (`FUZZY_LOADER_SOURCE=metadata`) — the estate's **member vocabularies**.
  Veles lists one per indexed **attribute** (`ListMemberVocabularies`), keyed by the
  attribute's ref (`er.<ns>.<entity>.<attribute>`), each with its match method and a
  read plan the translator rendered from the entity for this warehouse's dialect. The
  loader runs the plans against the warehouse and keeps each vocabulary's DISTINCT values,
  each one's id the value itself (A-MV-15), stamped with the method, so an `EXACT` code is
  matched exactly. It composes no SQL of its own (alias tables aside)
  and knows nothing of tables or primary keys: two entities over one table are two
  vocabularies, and a view- or query-backed entity reads its own population. A
  vocabulary Veles cannot plan is listed in `GetStatus` warnings (`RG-FUZ-001` no single
  key, `RG-FUZ-003` no read plan) and keeps its previous load if it had one; no listing at
  all (`RG-FUZ-004`, e.g. a Veles older than member vocabularies) keeps the previous
  member layer. A first boot with no listing still comes up, serving its declared lexicon,
  and retries the listing within seconds rather than a whole refresh interval.

## Retrieval modes (TATRMAN path)

The TATRMAN matcher supports two retrieval strategies behind
`fuzzy.token-based.retrieval` (env `FUZZY_TOKEN_BASED_RETRIEVAL`). Both return the
**same scores** — scoring, the cascade min-score gates, and the `fuzzy.match:v1`
contract are untouched:

- **`index-first`** (default) — resolves each query token once against the interned
  token vocabulary (edit-distance ≤ 2 over length buckets), sweeps postings to pick
  the best candidates term-at-a-time, then **exact-rescores** the top few hundred with
  the unchanged scorer. Cost scales with postings length, not corpus size: ~7–40×
  faster than legacy at product-name scale, and parity-or-better (it reaches candidates
  with no exact-token overlap that legacy misses). This is also the `CandidateRetriever`
  seam an OpenSearch backend can plug into.
- **`legacy`** — the escape hatch: score every candidate that shares an exact token with
  the query. Byte-identical to the pre-FZ engine. Select with
  `FUZZY_TOKEN_BASED_RETRIEVAL=legacy`, **and** `FUZZY_MATCH_VERSION=v1` — see below.

## Scorer version (`fuzzy.match`)

`fuzzy.match.version` picks the TATRMAN scorer. **The service ships `v2`** (flipped at LP-P3,
ruling LPA-2, on the §4.7 parity gate); `v1` is the byte-pinned pre-LP engine and is one
environment variable away, with no image involved.

- **`v2`** — per-token kinds (`exact` · `typo` · `prefix`) on the ES-AUTO edit ladder, a
  candidate-coverage tie-break at ε = 0.01, the order bonus computed over *matched* candidate
  positions, and per-token provenance (`Provenance.token_hits` + `coverage`, `method`
  `TATRMAN_V2`). What it buys is partial names: a query that is a prefix or a subset of a
  multi-token candidate now scores, and a candidate only fractionally covered is ranked below one
  fully covered at the same precision.
- **`v1`** — the pre-LP scorer, unchanged to the byte, `method` `TATRMAN`.

⚠ **v2 requires `index-first` retrieval and refuses to start on `legacy`.** Before the default
flipped, `FUZZY_TOKEN_BASED_RETRIEVAL=legacy` on its own quietly gave you v1 + legacy; now it is a
startup error unless `FUZZY_MATCH_VERSION=v1` goes with it.

⚑ The **library** default (`FuzzyMatcher(matchVersion = …)` in `lex-matcher-core`) is still `V1`.
Only this service's shipped configuration moved. An embedder that never passes the argument keeps
the pinned engine — that is what "v1 is byte-pinned" means.

| Config key | Env | Values | Default |
|---|---|---|---|
| `fuzzy.token-based.retrieval` | `FUZZY_TOKEN_BASED_RETRIEVAL` | `index-first` \| `legacy` | `index-first` |
| `fuzzy.match.version` | `FUZZY_MATCH_VERSION` | `v1` \| `v2` | **`v2`** |

The effective value is logged at startup (`Fuzzy match engine: fuzzy.match.version=…`) and echoed
in `FuzzyStatusResponse.engine_version`, so the question "which engine is this pod serving?" is
answerable from outside the pod. Ask the pod, not the manifest.

## Run

```bash
just build-kt fuzzy          # compile
just test-kt fuzzy           # Kotest unit + component suite (44 tests)
just deploy-kt fuzzy         # Jib image + k8s/overlays/local (local K3s)
```

## Ports

- HTTP **7265** (health / ready / `/match` REST) · gRPC **7266** (`FuzzyService.Match`)
- `tools/lex-matcher-mcp` wrapper: **7267**

Tag: `lex-matcher/v0.1.0`.
