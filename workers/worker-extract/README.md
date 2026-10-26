# worker-extract — Aristarchos

The document-extraction worker runtime: a Python pool that takes work items off the
pipeline's pgmq queues, runs the deterministic processor kinds over a document's units
and writes annotations back to the warehouse by reference.

**State: spike skeleton (DX-P0 S0.2/S0.4).** Only two pieces exist:

* `src/aristarchos/bus/pgmq.py` — the worker side of the pgmq work-item protocol, on the
  SQL-only pgmq install (`services/automation/src/main/resources/db/migration/V2__pgmq_1_13_0.sql`):
  `fetch · extend · ack · fail · metrics` (+ `send`, `ensure_queue`, the `_hi` lane).
* `src/aristarchos/sdk/models.py` — the message models (`WorkItem`, `Completion`, …) held to
  the shared JSON fixtures under `tests/fixtures/contracts/` (camelCase; a default-valued
  field is absent).

The runtime (processor runner, registry, validators, the warehouse and pipeline clients,
probes, image, chart) lands with the worker phase. The design and plan live in the
Collite planning workspace (effort DX).

## Test

```bash
uv sync
uv run pytest                  # unit + component (needs Docker for the component tier)
uv run pytest -m "not component"
```

Locally behind a Docker Hub mirror problem, `TESTCONTAINERS_RYUK_DISABLED=true` skips the
reaper image.
