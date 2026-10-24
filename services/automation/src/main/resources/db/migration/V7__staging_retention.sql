-- SPDX-License-Identifier: Apache-2.0
-- The release fence commits before external deletion. checked_at records attempts for fair bounded scanning;
-- released runs are revisited so a pre-fence writer cannot leave an immortal, unjournalled blob.
ALTER TABLE automation.runs ADD COLUMN staging_released_at timestamptz;
ALTER TABLE automation.runs ADD COLUMN staging_checked_at timestamptz;
CREATE INDEX runs_staging_sweep ON automation.runs (staging_checked_at NULLS FIRST, started_at, id);
