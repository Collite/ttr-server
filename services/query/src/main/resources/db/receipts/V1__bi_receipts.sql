-- SPDX-License-Identifier: Apache-2.0
-- BI (contracts C-6) — one row per Flight edge statement, written after its stream (best effort). The ES receipt's
-- shape: who asked what, in which world, what the door ran and the row rules it applied, how it ended. Read by
-- GET /receipts (query-platform-admin only).
CREATE TABLE bi_receipts (
    correlation_id   text        PRIMARY KEY,
    ts               timestamptz NOT NULL,
    user_id          text        NOT NULL,
    roles            text[]      NOT NULL DEFAULT '{}',
    world            text        NOT NULL DEFAULT '',
    connection_id    text        NOT NULL DEFAULT '',
    client           text        NOT NULL,          -- superset | jdbc | adbc | other
    statement        text        NOT NULL,          -- as the client sent it
    executed_sql     text        NOT NULL DEFAULT '', -- the ES receipt's statement (or its ref when over the cap)
    security_applied jsonb       NOT NULL DEFAULT '[]'::jsonb,
    rows_total       bigint      NOT NULL DEFAULT 0,
    top_n_applied    boolean     NOT NULL DEFAULT false,
    status           text        NOT NULL,          -- ok | refused | failed | cancelled | expired
    error_code       text        NOT NULL DEFAULT ''
);

CREATE INDEX bi_receipts_user_ts ON bi_receipts (user_id, ts DESC);
CREATE INDEX bi_receipts_ts ON bi_receipts (ts DESC);
