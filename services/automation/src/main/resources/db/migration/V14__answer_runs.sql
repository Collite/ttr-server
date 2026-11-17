-- SPDX-License-Identifier: Apache-2.0
-- S3.5 (D-230, ⚑AU-S35-1): an engine answer run decides the answered key's held rows of the instance's run at Open; it
-- is a run of its own kind. The answer finds that run by its engine instance and the key it held (✅R354-A): the newest
-- run the engine opened there without a task (an original engine Open names none) whose batch holds the key's group.
ALTER TABLE automation.runs DROP CONSTRAINT runs_kind_check;
ALTER TABLE automation.runs ADD CONSTRAINT runs_kind_check CHECK (kind IN ('recipe', 'query', 'perform', 'answer'));
CREATE INDEX runs_engine_original ON automation.runs (engine_instance_id, recipe, started_at)
    WHERE engine_task_id IS NULL;
-- review-354 F7: the held rows of a batch (the answer lookup, run status' `batch.held` and `decided`) without reading
-- an unheld batch's rows.
CREATE INDEX batch_rows_held ON automation.batch_rows (batch) WHERE group_key IS NOT NULL;
