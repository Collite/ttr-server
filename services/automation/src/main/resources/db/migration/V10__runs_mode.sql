-- SPDX-License-Identifier: Apache-2.0
-- D-192 (S3.1 T0, contracts "Amendment 2026-11-14 — the perform seam"): the program's mode at Open, so the abandonment
-- clock follows it — an `on_event` run by `budget_sync` + grace, every other run by `budget_batch` + grace. Rows
-- written before V10 keep NULL and keep the batch → sync → default order they had.
ALTER TABLE automation.runs ADD COLUMN mode text NULL;
