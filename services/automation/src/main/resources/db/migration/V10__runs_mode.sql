-- SPDX-License-Identifier: Apache-2.0
-- D-192 (S3.1 T0, contracts "Amendment 2026-11-14 — the perform seam"): the program's mode at Open, so the abandonment
-- clock follows it — an `on_event` run by `budget_sync` + grace, every other run by `budget_batch` + grace. Rows
-- written before V10 keep NULL and keep the batch → sync → default order they had.
ALTER TABLE automation.runs ADD COLUMN mode text NULL;

-- S3.1 (D-146, D-168): a run is a recipe's, a governed query's (`POST /automation/query`) or a typed action's
-- (`POST /automation/deployments/{d}/actions/{a}/perform`). `recipe` keeps the subject (the recipe, the query's lane
-- subject, the action). Every row written before V10 is a recipe run.
ALTER TABLE automation.runs ADD COLUMN kind text NOT NULL DEFAULT 'recipe'
    CHECK (kind IN ('recipe', 'query', 'perform'));

-- The runs list (S3.1 T4): one caller's runs, newest first.
CREATE INDEX runs_caller_started ON automation.runs (caller_subject, started_at DESC, id DESC);
