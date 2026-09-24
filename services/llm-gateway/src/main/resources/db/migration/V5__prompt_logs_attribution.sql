-- LC-P0·S0.2 (LC contracts §2.1) — per-call attribution on the prompt-log row. ADDITIVE and nullable:
-- a caller that sends none of the headers writes NULLs and the row is never refused; rows written before
-- V5 keep NULLs, which the inspect surface reads as "no end-user subject" (role-only, ⚑LC-1).
--
--   purpose           ← X-Call-Purpose      the node/step that made the call (⚑LC-2)
--   end_user_subject  ← X-End-User-Subject  the human the turn is for; per-row read authz keys on it (⚑LC-1)
--   agent_id          ← X-Agent-Id          the agent pod that made the call (⚑LC-4)
--
-- IF NOT EXISTS follows V3/V4's house style (re-runnable DDL); the shape is the contract's verbatim.
ALTER TABLE prompt_logs
    ADD COLUMN IF NOT EXISTS purpose          TEXT,
    ADD COLUMN IF NOT EXISTS end_user_subject TEXT,
    ADD COLUMN IF NOT EXISTS agent_id         TEXT;

-- A subject reading their own turn filters on BOTH (`end_user_subject = sub AND turn_ref = ?`).
CREATE INDEX IF NOT EXISTS prompt_logs_end_user_subject_turn_ref_idx
    ON prompt_logs (end_user_subject, turn_ref);
