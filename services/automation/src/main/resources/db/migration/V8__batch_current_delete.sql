-- SPDX-License-Identifier: Apache-2.0
-- D-105: current maintenance journals its key-scoped delete before inserts; no rows are applied here.
ALTER TABLE automation.batch_rows DROP CONSTRAINT batch_rows_op_check;
ALTER TABLE automation.batch_rows ADD CONSTRAINT batch_rows_op_check CHECK (op IN ('insert', 'update', 'transition', 'delete'));
