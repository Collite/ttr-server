-- LC-P0·S0.2 (LC contracts §2.1) — per-call attribution on the prompt-log row. ADDITIVE and nullable:
-- a caller that sends none of the headers writes NULLs and the row is never refused; rows written before
-- V5 keep NULLs, which the inspect surface reads as "no end-user subject" (role-only, ⚑LC-1).
--
--   purpose           ← X-Call-Purpose      the node/step that made the call (⚑LC-2)
--   end_user_subject  ← X-End-User-Subject  the human the turn is for; per-row read authz keys on it (⚑LC-1)
--   agent_id          ← X-Agent-Id          the agent pod that made the call (⚑LC-4)
--
-- IF NOT EXISTS follows V3/V4's house style (re-runnable DDL). Nullable, no default: a catalog-only change,
-- no table rewrite.
ALTER TABLE prompt_logs
    ADD COLUMN IF NOT EXISTS purpose          TEXT,
    ADD COLUMN IF NOT EXISTS end_user_subject TEXT,
    ADD COLUMN IF NOT EXISTS agent_id         TEXT;

-- Deliberately NO index on end_user_subject (review-100 F19; LC contracts §2.1 amended). A subject's read is
-- `turn_ref = ? AND end_user_subject = ?` (or `trace_id = ? AND …`): V4's partial turn_ref / trace_id index
-- already narrows it to one turn's handful of rows, and the subject is checked on those. An index here would
-- cover every row — NULL-subject for all legacy rows and every caller that sends no subject — on the
-- fastest-growing table, and building it (non-concurrently, inside Flyway's transaction) would block the
-- old replicas' inserts during a rolling update, filling their queues and dropping rows.
