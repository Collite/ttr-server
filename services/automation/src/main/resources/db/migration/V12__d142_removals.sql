-- SPDX-License-Identifier: Apache-2.0
-- D-142 (S3.2 T4): the Server keeps no queue, no task API, no scheduler, no webhooks and no window runtime. The
-- process engine owns those. V1–V4 stay as applied history; this migration removes what they created.
--
-- `approvals.task` keeps its values (an approval given through a task stays readable) but loses the reference.
ALTER TABLE automation.approvals DROP CONSTRAINT IF EXISTS approvals_task_fkey;

DROP TABLE automation.window_events;
DROP TABLE automation.windows;
DROP TABLE automation.deliveries;
DROP TABLE automation.callbacks;
DROP TABLE automation.tasks;

-- The run queue becomes a row claim on run_evaluations: a worker takes the oldest visible unclaimed ticket of its
-- owner FOR UPDATE SKIP LOCKED and hides it for a visibility window, as the queue's read did.
ALTER TABLE automation.run_evaluations
    DROP COLUMN message_id,
    ADD COLUMN enqueued_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN visible_at  timestamptz NOT NULL DEFAULT now();
CREATE INDEX run_evaluations_ready ON automation.run_evaluations (owner, enqueued_at)
    WHERE claimed_at IS NULL AND archived_at IS NULL;

-- The SQL-only queue install of V2/V3 (its schema, tables and functions). An installed extension of the same name
-- is not this service's, so it is left alone.
DO
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pgmq') THEN
        DROP SCHEMA IF EXISTS pgmq CASCADE;
    END IF;
END
$$;
