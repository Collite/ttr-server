-- SPDX-License-Identifier: Apache-2.0
-- BI (contracts C-6) — a receipt keeps the statement's notices as its closing metadata carried them: the warnings of
-- every batch, once each (`[{"code", "severity", "message"}]`, with `requested` / `applied` on `top_n_applied`).
-- Receipts written before this column have none.
ALTER TABLE bi_receipts ADD COLUMN notices jsonb NOT NULL DEFAULT '[]'::jsonb;
