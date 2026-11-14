-- SPDX-License-Identifier: Apache-2.0
-- S3.2 (contracts "Amendment 2026-11-01"). D-143: a windowed program's watermark, one row per (program, window).
-- A `port: window` open with no explicit bounds runs over [last_to, as_of) (the epoch when no row exists yet). The
-- close that seals a wet run advances last_to to the run's as_of, in the seal's transaction. A dry run never
-- advances it. `run` names the run that advanced the row last.
CREATE TABLE automation.program_windows (
    program     text        NOT NULL,
    "window"    text        NOT NULL,
    last_to     timestamptz NOT NULL,
    run         uuid        NOT NULL REFERENCES automation.runs (id),
    advanced_at timestamptz NOT NULL,
    PRIMARY KEY (program, "window")
);

-- D-150, D-159: what an engine-run open carries, stored on the run. asserted_person = the open's `answeredBy`;
-- person_verified = whether the engine's task history named the same assignee (NULL when nothing was asserted).
ALTER TABLE automation.runs
    ADD COLUMN engine_instance_id   text NULL,
    ADD COLUMN business_key         text NULL,
    ADD COLUMN engine_task_id       text NULL,
    ADD COLUMN asserted_person      text NULL,
    ADD COLUMN person_verified      boolean NULL,
    ADD COLUMN engine_deployment_id text NULL;
