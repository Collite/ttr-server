# contract-diff — the LLM Gateway 1.x → 2.0 migration gate

> **Renamed 2026-10-26 (DX-D10, DX-RN):** `docwh-store` (was Kallimachos) · `docwh-pipeline` (was Pinakes).

Purpose (design G-1 gate 1 / architecture §8): capture what every consumer really sends to and reads
back from the **1.x** gateway, then diff those response shapes against **2.0** in staging. A clean
diff over a thin corpus is a *false pass* (design §Risks) — so the corpus must carry every consumer ×
shape, and the diff must ignore only run-to-run noise, never contractual structure.

## Layout

```
contract-diff/
├── corpus/                 # one JSON per consumer×shape — git-tracked, secrets redacted
│   ├── hebe-chat-stream-notool.json
│   ├── hebe-chat-stream-toolcall.json
│   ├── hebe-embeddings.json
│   ├── themis-golem-chat-nonstream.json
│   ├── kleio-chat-nonstream-apiv1.json
│   ├── docwh-pipeline-chat-content.json          # SQ-2 field-surface consumer
│   └── iris-responses.json                # SQ-2 field-surface consumer
└── README.md
```

Each corpus entry (`CorpusEntry`, see `src/test/.../conformance/ContractCorpus.kt`):

| field | meaning |
|---|---|
| `consumer` / `shape` | who, and which distinct call shape |
| `method` / `path` | on-wire endpoint (note Kleio's `/api/v1/...`, docwh-pipeline' `/v1/chat`) |
| `headers` | headers sent (**secrets redacted** — see rules below) |
| `requestBody` | the real request shape, `origin: reconstructed-from-source` until a live capture replaces it |
| `readsResponseFields` | **the load-bearing column** — response fields the consumer actually depends on; drives diff ranking |
| `sources` | file:line provenance in the consumer repos |

## The diff engine

`ContractDiff` (`src/test/.../conformance/ContractDiff.kt`) is the tested authority. It reports
field-level deltas (key added/removed, value/type changed, array-size changed) and ignores:

- **volatile identity**: `id`, `created`, `created_at`/`createdAt`, `system_fingerprint`
- **model-generated text**: any `…​.content` / `.message.content` / `.delta.content` value (presence and
  type are still checked — a *dropped* field is always a hit)

Self-test (runs in the PR lane, `just test-kt :services:llm-gateway` → `ContractDiffSpec`):
identical envelopes ⇒ zero deltas; the usage-name rename, dropped non-standard fields, and shape
changes ⇒ caught.

> **Deviation from the S2·T5 task (recorded):** the task named a standalone `diff.main.kts`. We keep a
> *single* implementation (`ContractDiff`), CI-gated by `ContractDiffSpec`, and drive live runs through
> the property-gated replay below rather than a second, untested script.

## Capturing against a live 1.x (manual — needs the deployed/local 1.x + real caller secrets)

1. Start 1.x locally: `./gradlew :services:llm-gateway:bootRun` (pre-rewrite tree, tag
   `llm-gateway/pre-2.0`) or point at the staging 1.x.
2. For each `corpus/*.json`, replay its `requestBody` to `{BASE}{path}` with its `headers` (fill the
   `__REDACTED__` / `__…__` placeholders from the real caller secret at run time — never commit them).
3. Save the response next to the request as `corpus/<name>.response-1x.json` (redact any echoed key).
4. Repeat against 2.0 in staging → `corpus/<name>.response-2x.json`.
5. Diff: run with `-Dcontract.diff.base1x=…​ -Dcontract.diff.base2x=…​` (the replay path re-uses
   `ContractDiff`; wire it as a property-gated Kotest spec when live infra is available — it is skipped
   in CI by design). Output = the per-consumer markdown from `ContractDiff.report(...)`.

## Redaction rules (enforce before every commit)

- No bearer tokens, API keys, `ttrk-`/`gw-` secrets, cost-center instance ids, real trace ids, or PII.
- Placeholders: `__REDACTED_*__` for secrets, `__…__` for caller-specific values.
- `origin` must be `captured` **only** once a real response is stored; reconstructed request bodies stay
  `reconstructed-from-source`.

## SQ-2 status (the removal warrant — re-swept LG-P6·S1·T4 2026-07-15; see the S1 task Findings)

Verified against kantheon `master` (all consumers live there) + olymp/modeler/tatrman `master`.

- **Async jobs surface** — zero external callers → safe to remove (G-2). ✓
- **`/v1/conversations` endpoint** — zero callers → safe to remove. ✓
- **`/api/v1/*` prefix — 🔴 BLOCKED, and cutover-breaking.** `kleio` still `POST /api/v1/chat/completions`
  (`agents/kleio/.../HttpClients.kt:149`, wired) and `docwh-store` `POST /api/v1/embeddings`
  (`services/docwh-store/.../LlmGatewayEmbeddingsClient.kt:69`, wired) on **master** — 2.0 exposes only
  `/v1/*`, so both 404 on cutover. The Kleio `/api/v1`→`/v1` migration is on branch `lg-p0-kleio`, NOT master.
- **Top-level `content` + custom `/v1/chat` — 🔴 BLOCKED.** `docwh-pipeline` `POST /v1/chat` reads top-level
  `content` (`services/docwh-pipeline/.../LlmGatewayClient.kt:46,51`, wired) — 404 on cutover. `kleio` also keeps a
  top-level-`content` fallback.
- **Responses *field* surface (`output[]/status/createdAt/conversationId/reasoning.summary`, `conversation`
  req)** — the sole consumer is Iris' `LlmGatewayView.vue`/`llmGatewayService.ts`, which is **orphaned**
  (not in `router/index.ts`; live Iris goes via iris-bff). Removable in-tree, but ⚑ Bora: delete rather
  than leave latent.

⚑ **Net: G-3 ("zero caller changes") is not achievable as-is** — `kleio`, `docwh-store`, `docwh-pipeline` must
migrate to the standard `/v1/*` surface first, OR 2.0 keeps `/api/v1/*` + `/v1/chat` compat shims for the
migration window. Bora's disposition (reopens G-2 / LG-D3). Corpus TODO: add a `docwh-store-embeddings`
entry (missing — it was not in the P0 consumer set).
