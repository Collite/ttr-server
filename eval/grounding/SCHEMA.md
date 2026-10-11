# Grounding eval corpus — schema

Standalone, reference-clock-pinned eval corpus for the deterministic time/geo/money
grounding feature (A14.1). Two tiers, two files under `corpus/`:

- **`grounding-cases.json`** — *bulk* tier. One case per grounded span, asserted at the
  **GroundingResult** level (drives `grounding-mcp` directly). Consolidated from the
  per-service golden corpora (`services/{chrono,geo,money}/src/test/resources/corpus`)
  by `build_corpus.py`, plus `supplemental.json`. **Regenerate with `just eval-grounding-build`;
  the committed file is the source of truth for the runner.**
- **`e2e-cases.json`** — *e2e* tier. A ~20-case subset asserted at the **final SQL condition
  shape** (drives Golem `/v2/chat`, so the grounded recipe must survive the cascade).

The reference clock is pinned per case so "yesterday" / "today's rate" are deterministic;
default `2026-05-15T12:00:00+02:00` (Europe/Prague noon), matching the Kotlin corpus specs.

## Bulk case (`grounding-cases.json`)

```jsonc
{
  "id": "chrono/iso-date",          // "<service>/<name>", unique
  "tool": "ground_time",            // ground_time | ground_geo | ground_money
  "kind": "DATE",                   // DATE | LOCATION | MONEY  (Resolver UniversalEntityType)
  "span": "2026-03-15",             // spanText handed to the tool
  "locale": "en-US",                // BCP-47; drives cs/en balance + GroundingContext.locale
  "reference_datetime": "2026-05-15T12:00:00+02:00",  // GroundingContext.referenceDatetime
  "timezone": "Europe/Prague",
  "package": "cnc",                 // GroundRequest.package
  "model": "accounting-period",     // model fixture id (see §Models); the deployed stack
                                     // must serve a compatible metadata model for the case
  "here": null,                     // geo "here" place (→ GroundingContext.herePlaceRef), else null
  "expect": {
    "status": "OK",                 // OK | AWAITING_CLARIFICATION | UNGROUNDABLE
    "application": "FILTER",         // FILTER | JOIN | VALUES  (only when status == OK)
    "source": "RULES",               // RULES | LLM | NONE      (optional)
    "period_code": "202605",         // chrono `p` param value (optional)
    "sql_contains": ["t.\"date\" >= {start}"]  // fragments that must appear in sql_preview
  }
}
```

`expect.sql_contains` matches against `GroundingResult.sql_preview` (the recipe render, params
still as `{name}` placeholders) — **not** executed SQL. Only `status` is mandatory; the rest are
present where the source corpus pinned them.

## E2E case (`e2e-cases.json`)

```jsonc
{
  "id": "e2e/may-transactions",
  "tier": "e2e",
  "locale": "cs",
  "reference_datetime": "2026-05-15T12:00:00+02:00",
  "package": "cnc",
  "turns": [
    {
      "user_text": "Vypiš účetní záznamy za období 2026.03",
      "expect": {
        "grounded": true,                     // ≥1 OK grounding on the envelope
        "grounding_note_contains": ["2026"],  // substring in a FormatEnvelope.grounding_notes line
        "not_clarifying": true,               // turn did not dead-end in a clarification
        "sql_contains": ["period_start", "period_end"],  // over the executed/planned SQL when exposed
        "plan_source_in": ["free_sql", "llm_pattern", "pattern"]
      }
    }
  ]
}
```

E2E assertions are intentionally shape-level (fragment presence), not full-SQL equality — the
grounded **condition** must survive the cascade, but plan composition around it may vary.

## Models (fixtures)

| id | service | what it provides | source spec |
|----|---------|------------------|-------------|
| `accounting-period` | chrono | `AccountingPeriod` table present → JOIN path available | `periodTable=true` |
| `calendar` | chrono | `Transaction` only (no period table) → FILTER via `period_start/end` | `periodTable=false` |
| `geo-brno-praha` | geo | Brno/Praha centroids + boundary polygons; `Újezd*` ambiguous; `Atlantis` unknown | `GeoCorpusSpec` resolver |
| `money-domestic` | money | `amount_domestic` shortcut column | `model=domestic` |
| `money-fx` | money | foreign amount + FX rate table → JOIN | `model=fx` |
| `money-native` | money | native `currency` column filter | `model=native` |
| `money-amountOnly` | money | amount, no FX table → foreign currency UNGROUNDABLE | `model=amountOnly` |
| `money-ambiguous` | money | amount column ambiguous → AWAITING_CLARIFICATION | `model=ambiguous` |

Against a deployed estate the runner tags each result with its `model` and reports coverage; only cases
whose `model` the deployed metadata model can satisfy are scored as pass/fail, the rest as
`skipped:model-unavailable` (see `run_eval.py`).
