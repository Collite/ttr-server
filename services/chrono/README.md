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

## What it recognises

Czech and English, case-inflected and with or without diacritics. Intervals are `[start, end)`; *ref* is the
reference date of the request.

| Phrase family | Czech | English | Reading |
|---|---|---|---|
| dates | *15. 3. 2026*, *15. května 2026*, *2026-03-15* | *March 15 2026* | that day; a date with no year takes the year of the question or the conversation, else ref's |
| months | *květen 2026*, *v říjnu 2025*, *květnové období* | *May 2026*, *May period* | that month, `PERIOD` `yyyyMM` |
| last month of a year | *poslední měsíc roku*, *v posledním měsíci loňského roku*, *poslední měsíc roku 2025* | *the last month of the year*, *… of last year*, *… of 2025* | December of that year; yearless reads as *December* (and asks the same way) |
| month, no year | *za červenec*, *od června*, *v září* | *in July*, *for September* | that month; the year from the conversation (#214), else ref's — and **asks** this year vs last when the month is later than ref's |
| period code | *období 202605*, *202605* (alone) | *period 202605* | that month; a six-digit run inside other words is no period |
| years | *2025*, *v roce 2025*, *fiskální rok 2026* | *2025*, *fiscal year 2026* | the calendar year (`CALENDAR_YEAR` / `FISCAL_YEAR`); a quarter or half of a fiscal year (*Q4 of fiscal year 2025*) is that quarter or half |
| relative years | *letos*, *loni*, *vloni*, *předloni*, *letošní*, *loňský*, *předloňský*, *tento rok* | *this year*, *last year*, *the year before last* | ref's year, the one before, two before |
| relative months, quarters | *tento / minulý / předminulý měsíc*, *toto / minulé čtvrtletí*, *tento / minulý kvartál* | *this / last month*, *this / last quarter* | ref's period, or the one before; quarters `PERIOD` `yyyyQn` |
| anaphoric | *předchozí / předešlý rok, měsíc, čtvrtletí*; *minulý …* | *the previous year*, *the month before* | the period before the one the conversation is about (#214); *minulý* asks when the two readings differ; nothing to count from → `UNGROUNDABLE no_anchor` |
| elapsed | *uplynulý měsíc / týden / rok / čtvrtletí* | *the past month / year* | the period before ref's |
| weeks | *tento / minulý / předminulý týden* | *this / last week* | Monday to Monday |
| numbered quarter | *2. čtvrtletí 2026*, *druhé čtvrtletí*, *3. kvartál*, *Q2 2026*, *Q2*, *poslední čtvrtletí roku* | *Q2 2026*, *the second quarter of 2025*, *the last quarter of the year*, *… of the previous year* | that quarter, `yyyyQn` (the last quarter of a year is its fourth); yearless reads as a yearless month (and asks the same way); a quarter in a year chrono cannot read — one it reads nowhere (*Q2 next year*, *the last quarter of next year*, *Q2 a year ago*, *každé 2. čtvrtletí*) or digits no pattern places (*Q2 for 2025*) — is not read |
| half-year | *1. pololetí 2026*, *druhé pololetí*, *H1 2026*, *poslední pololetí loňského roku* | *H1 2026*, *the first half of 2026*, *the second half of last year*, *the last half of the year* | six months, `yyyyHn` (the last half of a year is its second); yearless reads as a yearless month; in a year chrono cannot read (*H1 next year*, *první pololetí příštího roku*, *H2 fiscal 2025*), not read |
| trailing | *poslední měsíc / týden / rok*, *posledního měsíce*, *v posledním měsíci* | *the last month / week / year* | **asks** (`AWAITING_CLARIFICATION`): `trailing` — the last 30 / 7 / 365 days through ref — or `calendar` — the period before ref's. The answer is not in the conversation, so an anaphoric period of the unit after it („A předchozí měsíc?“) is `no_anchor`, as after an asked *minulý* |
| last N | *posledních 30 dní*, *poslední 3 měsíce* | *last 30 days*, *past 3 months* | exactly N days ending with ref, `[ref − N + 1 day, ref + 1 day)`; N months from the day after the same date N months back; a count of weeks or years (*posledních 6 týdnů*, *past 2 years*) is not read, nor is a count of a larger period (*the last 2 months of the year*, *poslední 2 měsíce roku*, *the last 10 days in March*) |
| year to date | *od začátku roku*, *letos dosud*, *… do dneška*, *… do současnosti*, *… do března* | *year to date*, *YTD*, *… as of today*, *… so far*, *… through September*, *… by end of March* | `[1 January, ref + 1 day)`; ending with a month, to that month's end; in the year the span states, in digits or words (*loni*, *last year*), else ref's; with no ending, a year other than ref's is that whole year; a breakdown (*by region*, *by month*) is no ending, nor is *do* / *to* before a place (*do Německa*, *to Germany*); any other ending that names a time is no one period |
| days | *dnes*, *včera*, *zítra* | *today*, *yesterday*, *tomorrow* | that day |

Not a period: a bare period noun (*měsíc*, *po měsících*, *roce*) — a breakdown or a trigger word; the grain
adjectives (*měsíční*, *čtvrtletní*, *pololetní*, *kvartální*, *monthly*, *quarterly*); a week, month or day **of** a
stated year ("the last week of 2025"), which no rule reads yet; a quarter or half-year by number in a span that
names any other period — *1. a 2. čtvrtletí*, *first and second quarter of 2025*, *Q1 a Q2 2025*, *last quarter and
Q1*, *Q2 2025 vs 2024*, *Q3 and H1 2025*, *Q1 a březen 2025*, a year joined by a comparison or a conjunction (*Q2
this year vs last year*, *2. čtvrtletí letos a loni*, *Q2 vs 2024*), a comparison with a time on its other side
(*Q2 vs the same quarter last year*, *2. čtvrtletí oproti stejnému období loni*, *Q2 vs a year ago*), a count (*Q1 and the last 3 months*) or year to
date (*Q2 vs YTD*) — which is not one period (a year word with no joiner, *letošní 2. čtvrtletí*, *Q2 last year*, only
says which year); nor is year to date beside any other period (*year to date vs last year*, *YTD vs the same period
last year*).
The trailing ask is the singular only: *poslední měsíce* (recent months) does not ask. The last week of any period
and the last month of a quarter — *the last week of the year*, *the last week in October*, *the last month of Q3
2025*, *poslední týden v měsíci*, *poslední měsíc čtvrtletí* — are not read at all, nor is the last quarter, half or
month of a year chrono cannot name (*the last month of next year*, *poslední čtvrtletí každého roku*); an earlier
question with one names no period of its unit. A year is part of no month: *last year in March* is March of last
year. An estate's `ground:chrono` lexicon slice (RV-42)
says which words are chrono's; it never changes what they mean.

## Outcomes of `Ground`

| Status | When | Result |
|---|---|---|
| `OK` · `join` | a period ("May 2026", „období 202605“) and a period table **of its grain** with start, end and code columns: a month meets a monthly table, a quarter a `yyyyQn` one; a `code_format` of another grain (`yyyy`, `yyyyMMdd`) meets none | `JoinRecipe` on the period table, filter `code = {p}` |
| `OK` · `filter` | an anchor date column: a period with no period table, or any other interval | `FilterRecipe` over `period_start({p})`/`period_end({p})`, or datetime bounds `{start}`/`{end}`; a quarter or half-year with no table of its grain takes datetime bounds |
| `OK` · `filter` | no date column, but a period table with a code column and a period phrase of its grain (#140) | `FilterRecipe` `code = {p}` |
| `OK` · `values` | **no date column and no period-code route** (no table, or a period of another grain) | `ValueBinding`: `start`, `end` (datetime, half-open, the request's zone) and `p` (the period code, `yyyyMM` / `yyyyQn`) when the phrase names a period; `sql_preview` empty; `explanation` names the package. The caller places the values. |
| `AWAITING_CLARIFICATION` | the phrase has two readings (a bare future month, „minulý rok“ after a question about a year) | one option per reading, `id` = the period code, `normalized` = its interval; resume with `clarification_answer_id` |
| `UNGROUNDABLE` | nothing read in the span (and no LLM fallback, or the fallback failed); a missing `reference_datetime`; an unknown `clarification_answer_id` | a `ResponseMessage` saying why |
| `UNGROUNDABLE` · `no_anchor` | an anaphoric period with nothing in the conversation to count from (#214) | never read against the reference date |

`UNGROUNDABLE` never means "the model has no date column" — that is `OK` with `values` (TS-1). A recognition
below the confidence threshold goes to the llm-gateway fallback (`source: LLM`) when one is configured; with no date
column it may answer `values` too (`start`/`end`, empty `sql_preview`).

Every `OK` carries `normalized.interval`; `filter` and `join` carry a `sql_preview` rendered from the recipe's own
expression tree, with parameters as `{name}`.

## Metrics

- `chrono_ground_total{outcome, source, application}` — `application` is `filter`, `join` or `values` for an `OK`,
  `none` otherwise; the `values` share is how often a question reached a model that declares no date column.
- `chrono_ground_latency_ms{outcome, source}`.

## Tests

`just test-kt chrono`. The golden corpus is `src/test/resources/corpus/chrono/cases.json` (`dateRoles: false`
is a package with no date role); `just eval-grounding-build` folds it into `eval/grounding/corpus`.
