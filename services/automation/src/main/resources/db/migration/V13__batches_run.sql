-- SPDX-License-Identifier: Apache-2.0
-- D-202 (F2, S3.2c T4): a watermark re-read settles per row. The close reads the rows an earlier sealed run of the
-- program applied over an overlapping window: runs (by program) -> batches (by run) -> batch_rows (by batch, the
-- primary key). Batches had no index on run; this is it.
CREATE INDEX batches_run ON automation.batches (run);
