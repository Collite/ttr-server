-- SPDX-License-Identifier: Apache-2.0
-- Durable, at-most-once evaluation dispatch. Owner is a fresh process-incarnation UUID, never a hostname.
-- Credentials and pinned executors stay in that process. A lost owner is abandoned, never reassigned.
CREATE TABLE automation.run_evaluations (
    run         uuid PRIMARY KEY REFERENCES automation.runs (id),
    owner       uuid NOT NULL,
    message_id  bigint NOT NULL UNIQUE,
    claimed_at  timestamptz,
    archived_at timestamptz
);
CREATE INDEX run_evaluations_unarchived ON automation.run_evaluations (run) WHERE archived_at IS NULL;
