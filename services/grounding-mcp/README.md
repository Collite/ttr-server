# grounding-mcp

The MCP face of the three deterministic grounding services — chrono (time), geo (places), money (amounts). Thin
wrappers, no grounding logic: each tool's arguments are the camelCase mirror of the service's proto request, and its
`structuredContent` the camelCase mirror of the response (`org.tatrman.grounding.v1`, via protobuf `JsonFormat`).

| Tool | Capability id | Service RPC | Arguments | Result |
|---|---|---|---|---|
| `ground_time` | `grounding.time:v1` | chrono `Ground` (kind `DATE_TIME`) | `spanText`, `questionText`, `package`, `context` (`referenceDatetime` required), `clarificationAnswerId` | `GroundResponse`: `status`, `result` (interval, recipe, `sqlPreview`), `options`, `messages` |
| `ground_geo` | `grounding.geo:v1` | geo `Ground` (kind `LOCATION`) | as `ground_time`; no reference date needed | `GroundResponse` |
| `ground_money` | `grounding.money:v1` | money `Ground` (kind `MONEY`) | as `ground_time`; no reference date needed | `GroundResponse` |
| `scan_time` | `grounding.time.scan:v1` | chrono `Scan` | `text` (quoted literals blanked, length kept), `package`, `context` (`referenceDatetime` required, `locale`, `priorQuestions`) | `{spans: [{start, end, text, kind, confidence, trigger}], messages}` — every field printed, so a span at offset 0 has `start: 0` and none is `spans: []` |

`scan_time` finds the time phrases of a question (TS-P0 S0.3); ground each span with `ground_time`. What a span is,
Warnings (including `text_too_long`, after 128 tokens) and chrono errors are preserved; any ERROR makes the tool
result `isError=true`. What a span is, and why, is chrono's: see `services/chrono/README.md` § `Scan`.

`scan_time` stays listed whatever chrono runs, but calls `Scan` only while chrono's `GetStatus` declares
`capabilities["scan"] = "true"` (OL-C4, the same read as the resolver's). Otherwise, or while `GetStatus` fails, it
answers a tool error with `errorCode: "not_available"` in one sentence and does not call `Scan`. The answer is kept for
`grounding.status-ttl-seconds` (default 60, env `GROUNDING_STATUS_TTL_SECONDS`).

`isError` is for a bad argument (`INVALID_ARGUMENT`), a failed call to the service (`TRANSPORT_ERROR`) or a chrono
without Scan (`not_available`);
`AWAITING_CLARIFICATION`, `UNGROUNDABLE` and a scan that finds nothing are ordinary results. Each tool is wrapped in
`safeMcpTool`, so a timeout is a well-formed error result. The manifests in `src/main/resources/manifests/tools`
register the capability ids with capabilities-mcp when `CAPABILITIES_MCP_URL` is set.

Tests: `just test-kt grounding-mcp` (mocked services; `ToolsTest` replays a recorded `ScanResponse`).
