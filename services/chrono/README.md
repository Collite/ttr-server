# chrono

**chrono** — the DATE_TIME grounding kernel. It reads a time phrase of a question („loni“, „minulý měsíc“,
"May 2026", "15. 3. 2026") into a half-open interval `[start, end)` and, when the model offers a date column,
into the recipe that restricts it. The service proto is `org.tatrman.grounding.v1` / `GroundingService`
(RPCs `Ground`, `GetStatus`); `services/grounding-mcp` exposes it to agents as `ground_time`.

Two steps, kept apart:

- **Recognition** needs the reference date, the calendar and the conversation — never the model and never a
  clock (`reference_datetime` always comes from the request).
- **Application** — which attribute the interval restricts and in what shape — reads the package's metadata:
  the `event_date` role (or the role the wording names: `due_date`, `posting_date`, `document_date`) and the
  period table (`semantics { kind: period_table }` with `period_start`/`period_end`/`period_code`).

A recognised phrase is always answered with its interval; a model with no date column changes only the
application.

## Outcomes of `Ground`

| Status | When | Result |
|---|---|---|
| `OK` · `join` | a period ("May 2026", „období 202605“) and a period table with start, end and code columns | `JoinRecipe` on the period table, filter `code = {p}` |
| `OK` · `filter` | an anchor date column: a period with no period table, or any other interval | `FilterRecipe` over `period_start({p})`/`period_end({p})`, or datetime bounds `{start}`/`{end}`; a `yyyyQn` quarter with no table takes datetime bounds |
| `OK` · `filter` | no date column, but a period table with a code column and a period phrase (#140) | `FilterRecipe` `code = {p}` |
| `OK` · `values` | **no date column and no period-code route** | `ValueBinding`: `start`, `end` (datetime, half-open, the request's zone) and `p` (the period code, `yyyyMM` / `yyyyQn`) when the phrase names a period; `sql_preview` empty; `explanation` names the package. The caller places the values. |
| `AWAITING_CLARIFICATION` | the phrase has two readings (a bare future month, „minulý rok“ after a question about a year) | one option per reading, `id` = the period code, `normalized` = its interval; resume with `clarification_answer_id` |
| `UNGROUNDABLE` | nothing read in the span (and no LLM fallback, or the fallback failed); a missing `reference_datetime`; an unknown `clarification_answer_id` | a `ResponseMessage` saying why |
| `UNGROUNDABLE` · `no_anchor` | an anaphoric period with nothing in the conversation to count from (#214) | never read against the reference date |

`UNGROUNDABLE` never means "the model has no date column" — that is `OK` with `values` (TS-1). A recognition
below the confidence threshold goes to the llm-gateway fallback (`source: LLM`) when one is configured.

Every `OK` carries `normalized.interval`; `filter` and `join` carry a `sql_preview` rendered from the recipe's own
expression tree, with parameters as `{name}`.

## Metrics

- `chrono_ground_total{outcome, source, application}` — `application` is `filter`, `join` or `values` for an `OK`,
  `none` otherwise; the `values` share is how often a question reached a model that declares no date column.
- `chrono_ground_latency_ms{outcome, source}`.

## Tests

`just test-kt chrono`. The golden corpus is `src/test/resources/corpus/chrono/cases.json` (`dateRoles: false`
is a package with no date role); `just eval-grounding-build` folds it into `eval/grounding/corpus`.
