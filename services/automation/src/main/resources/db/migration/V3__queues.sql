-- SPDX-License-Identifier: Apache-2.0
-- The four ledger queues (AU contracts §2; D-46). pgmq keeps every queue in its own `pgmq` schema
-- (`pgmq.q_<name>` + the archive `pgmq.a_<name>`), so the `automation.` of the contract's prose is a
-- name prefix here: automation.runs = `automation_runs`.
SELECT pgmq.create('automation_runs');
SELECT pgmq.create('automation_tasks');
SELECT pgmq.create('automation_windows');
SELECT pgmq.create('automation_signals');
