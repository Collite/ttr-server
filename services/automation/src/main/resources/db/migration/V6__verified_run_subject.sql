-- SPDX-License-Identifier: Apache-2.0
-- A display principal can be renamed or reused; durable run reads authorize the verified JWT subject.
-- Existing rows intentionally remain unowned. Never guess a subject from runs.caller or a protocol label.
ALTER TABLE automation.runs ADD COLUMN caller_subject text;
ALTER TABLE automation.runs ADD CONSTRAINT runs_caller_subject_nonempty
    CHECK (caller_subject IS NULL OR btrim(caller_subject) <> '');
