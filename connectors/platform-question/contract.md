# Platform question contract

Version 0.11.0 · Apache-2.0

This contract describes a synchronous, read-only task for an external workflow
engine. It publishes an interface, not a service implementation. The normative
words **must** and **may** distinguish requirements from options already present
in the source contract.

## HTTP interface

`POST /automation/query` accepts a JSON object:

```json
{
  "config": {"core": {"items": {"entity": "item"}}},
  "params": {}
}
```

`config` may instead be a string containing YAML. `params` supplies configuration
placeholders. The call must be synchronous with one budget for the whole call.
`POST /automation/check` accepts the same body and performs the same checks
without calling either execution lane. `GET /health` is the health endpoint.

Every answer must be JSON with `status`: `ok`, `refused`, `clarify` (Golem lane
only), or `error`. An error carries `error: {code, message}`; the source contract
requires an analyst-facing message in Czech. The API uses these HTTP codes:

| HTTP | Error code |
|---|---|
| 200 | Successful answer, refusal, or Golem clarification |
| 400 | `config-invalid`, `param-missing` |
| 401 | `unauthorized` |
| 422 | `query-refused`, `frame-truncated`, `variable-invalid` |
| 502 | `upstream-unreachable` |
| 504 | `budget-exceeded` |

## Configuration

[`config.schema.json`](config.schema.json) describes the parsed configuration
object, not the request envelope or the YAML string. Parsing YAML precedes schema
validation. Runtime checks still have to resolve model names, core references,
placeholders, frame columns, query safety, and result cardinality.

`core` must contain 1–8 named entries, with names matching `[a-z][a-z0-9_]*`.
Each entry selects exactly one source:

| Source | Configuration |
|---|---|
| Named query | `query: <served pattern id>` containing a `.query.` segment, e.g. `catalog.query.open_items`; a bare name is invalid |
| SQL | `sql: <text>` over entities, including logical queries |
| Entity | `entity: <name>`, with optional `where` and `columns` |
| Table | `table: <name>` declared by the served model, with optional `where` and `columns` |
| Golem | `golem: {agent: <id>, question: <text>}` |

A configuration must have either data cores or exactly one Golem core, never a
mixture. `variables` is optional. Variable names must match ASCII
`[A-Za-z_][A-Za-z0-9_]*` and must not begin with `tatrman_`.
When supplied, `columns` must be a nonempty list of distinct column names.

`{name}` in `sql`, `where`, and `question` is a placeholder supplied by `params`.
A missing parameter must produce `param-missing`. For SQL, the platform's own
pattern-lane binding must bind values as parameters; the service must never
concatenate them into SQL. `${…}` is not a configuration placeholder.

## Variables

Each named variable is an object with `kind` and the keys below. `over` refers to
a core's frame; `scalar` runs DuckDB SQL over the frames.

| `kind` | Keys besides `kind` | Result |
|---|---|---|
| `row_count` | `over`, optional `distinct` column name (never a boolean) | Integer |
| `exists` | `over` | Boolean |
| `aggregate` | `over`, `agg` (`sum`, `avg`, `min`, `max`, `count`), `column`, optional `null_is_zero` | Number or null |
| `column` | `over`, `column`, optional boolean `distinct` | List |
| `row` | `over`, optional `prefix` | One variable per column, using ASCII-folded names |
| `table` | `over` | List of objects |
| `scalar` | `sql` | One value |
| `verdict` | `over` referring to a Golem core | `answered`, `refused`, or `clarify` |

For `row`, an absent prefix and `prefix: ""` both mean no prefix. Zero rows
produce null for each column; more than one row must produce `variable-invalid`.
An explicit nonempty prefix must match `[A-Za-z_][A-Za-z0-9_]*` and must not
begin with `tatrman_`. An implementation must never export a variable named
`tatrman_*`: it must refuse a row expansion producing such a name with
`variable-invalid`, including an unprefixed column named `tatrman_…`.
On an empty frame, aggregates are null, `count` is zero, and `null_is_zero: true`
turns null into zero. The derivation core's `validation_inconsistent` behavior
on empty frames is unchanged.

On a Golem refusal or clarification, the answer must be
`200 {status: refused|clarify, text, data: {}, variables}`. Derive `verdict`
variables and include every other variable with null; `row` adds no keys because
there are no columns. An answered, text-only turn still produces
`422 variable-invalid` for data variables.

## Limits and answer

The implementation must cut a core at its row limit and mark `truncated: true`.
It must refuse a variable over a truncated frame with `422 frame-truncated`,
rather than return an incomplete calculation. The Golem frame is the table its
answer envelope carries.

The answer envelope is `{status, data, variables, text?, traceId}`. Each
`data.<core>` is `{columns: [{name, type}], rows: [{…}], rowCount, truncated}`.
Column names must be the model's served names, dates ISO-8601, and decimals JSON
numbers. `variables` must be flat. `text`, when present, is the Golem's answer
text. `traceId` must be the trace used in the platform's logs.

## Identity

The caller must present a service credential. Calls to both lanes use the
configured service identity and roles through `X-User-ID` and `X-Roles`, retaining
the validator's row ceiling for that identity. There is no per-user identity in
this contract. Deployment-specific credential names and locations are outside
this published interface.

## Camunda 7 binding

The two templates, `tatrman-question` (English) and `tatrman-dotaz` (Czech), bind
a `bpmn:ServiceTask` to `http-connector`. They expose literal configuration text,
process-parameter names, and the result variable (default `tatrman_vysledek`).
Configuration must remain literal `camunda:field` data, never a JUEL expression.
Method, headers, payload, timeouts, and the end listener are hidden properties.
The endpoint property reads the engine JVM system property `tatrman.url` with
no default host. Authorization reads `tatrman.token` from the engine JVM, never
from BPMN or process variables. These are portable engine property keys, not
deployment credential names.
If either engine property is missing or blank, the task must fail with an engine
incident naming that property, before an HTTP call is made.

The listener must store the answer as Spin JSON, set `tatrman_status` and
`tatrman_chyba` on errors, and export the answer's variables: native scalars,
Spin JSON for lists and objects. No custom Java is needed; Connect, Spin, and
Groovy are prerequisites. Behavior is the same on Camunda 7.14 and 7.20.
The listener may use a native Double for a decimal only when the finite Double's
decimal text round-trips unchanged. Otherwise it must retain the BigDecimal
exactly using the engine's built-in Java Object serialization
(`application/x-java-serialized-object`). This uses the JDK BigDecimal class;
the HTTP answer remains JSON with decimal numbers. Camunda 7.20's default
`javaSerializationFormatEnabled=false` needs no change: that setting restricts
API-supplied pre-serialized Java values, not an object the template asks the
engine to serialize itself.
The listener must skip invalid or `tatrman_`-prefixed keys
from a service answer and log a warning, preserving the actual answer status.

A service JSON error body, regardless of HTTP status, reaches the end listener
and gateway. A transport failure (connection, timeout, TLS) creates an engine
incident before the listener; `asyncBefore="true"` preserves the started process
and permits an operator retry from Cockpit.

## Source cross-reference

This restates the legacy AG-Q design, §12, without adding execution rules.

| Source id | Published section |
|---|---|
| C-47 | HTTP interface |
| C-48 | Configuration |
| C-49 | Variables |
| C-50 | Limits and answer |
| C-51 | Limits and answer |
| C-52 | Identity |
| C-53 | Camunda 7 binding |
