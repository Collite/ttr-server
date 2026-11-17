-- SPDX-License-Identifier: Apache-2.0
-- S3.5 (D-230, ⚑AU-S35-1): an engine answer run decides the answered key's held rows of the instance's waiting run at
-- Open; it is a run of its own kind. The answer finds that run by its engine instance: the newest waiting run the engine
-- opened there without a task (an original engine Open names none).
ALTER TABLE automation.runs DROP CONSTRAINT runs_kind_check;
ALTER TABLE automation.runs ADD CONSTRAINT runs_kind_check CHECK (kind IN ('recipe', 'query', 'perform', 'answer'));
CREATE INDEX runs_engine_waiting ON automation.runs (engine_instance_id, recipe, started_at)
    WHERE status = 'waiting' AND engine_task_id IS NULL;
