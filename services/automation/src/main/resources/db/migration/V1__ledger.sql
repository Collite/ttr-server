-- SPDX-License-Identifier: Apache-2.0
-- The automation ledger (AU contracts §2; D-46 one ledger, D-73 retention, D-97 waiting, D-98 one open task,
-- D-101 tasks.run nullable, D-110 run_protocol INSERT-only). Schema `automation`; the world schemas
-- (`automation_pg`, contracts §3) are created by the writer at /refresh, not here.
--
-- Status vocabularies are CHECK constraints, so a typo in the service fails the write instead of
-- writing a state nothing reads. Budgets are milliseconds.

CREATE SCHEMA IF NOT EXISTS automation;

CREATE TABLE automation.bundles (
    id           text        PRIMARY KEY,
    kind         text        NOT NULL CHECK (kind IN ('served', 'preview')),
    branch       text,
    commit       text        NOT NULL,
    model_fp     text,
    loaded_at    timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz
);

CREATE TABLE automation.runs (
    id              uuid        PRIMARY KEY,
    recipe          text        NOT NULL,
    program         text,
    deployment      text,
    bundle          text,
    fp_text         text,
    fp_values       text,
    fp_program      text,
    fp_deployment   text,
    model_fp        text,
    port            jsonb       NOT NULL DEFAULT '{}'::jsonb,
    as_of           timestamptz,
    author          text,
    run_as          text,
    caller          text,
    origin          text,
    status          text        NOT NULL
        CHECK (status IN ('open', 'running', 'evaluated', 'waiting', 'closed', 'failed', 'abandoned')),
    dry_run         boolean     NOT NULL DEFAULT false,
    governance      text        NOT NULL DEFAULT 'governed' CHECK (governance IN ('governed', 'ungoverned')),
    idempotency_key text,
    replay_of       uuid        REFERENCES automation.runs (id),
    batch_id        uuid,
    protocol_ref    text,
    started_at      timestamptz NOT NULL DEFAULT now(),
    ended_at        timestamptz,
    budget_sync     bigint,
    budget_batch    bigint,
    error           jsonb
);

-- Exactly-one (D-46): at most one run per key that is live (open · running · evaluated · waiting) or done
-- (closed — a replay). A failed or abandoned run frees its key, so a new claim may insert beside it. The
-- claim looks a key up through this index; it also serves the "live runs by key" read of the task list.
CREATE UNIQUE INDEX runs_idempotency_key_claimed
    ON automation.runs (idempotency_key)
    WHERE idempotency_key IS NOT NULL AND status NOT IN ('failed', 'abandoned');

CREATE INDEX runs_abandoned ON automation.runs (status) WHERE status = 'abandoned';

CREATE TABLE automation.run_outputs (
    run         uuid        NOT NULL REFERENCES automation.runs (id),
    island      text        NOT NULL,
    port        text        NOT NULL,
    rows        bigint      NOT NULL DEFAULT 0,
    bytes       bigint      NOT NULL DEFAULT 0,
    path        text,
    sha256      text,
    released_at timestamptz,
    PRIMARY KEY (run, island, port)
);

CREATE TABLE automation.tasks (
    id            uuid        PRIMARY KEY,
    run           uuid        REFERENCES automation.runs (id),
    recipe        text,
    key           jsonb,
    kind          text        NOT NULL CHECK (kind IN ('ask', 'start', 'deliver', 'call', 'signal')),
    action        text,
    connector     text,
    target_role   text,
    target_person text,
    about         text,
    options       jsonb,
    detail        jsonb,
    group_key     jsonb,
    correlation   text,
    status        text        NOT NULL
        CHECK (status IN ('open', 'answered', 'due', 'cancelled', 'superseded', 'delivered', 'failed')),
    due           timestamptz,
    answer        jsonb,
    answered_by   text,
    answered_at   timestamptz,
    dry_run       boolean     NOT NULL DEFAULT false,
    origin        text,
    approval      jsonb,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- D-98: one open task per (recipe, key, action).
CREATE UNIQUE INDEX tasks_one_open
    ON automation.tasks (recipe, key, action)
    WHERE status = 'open';
CREATE INDEX tasks_for_person ON automation.tasks (target_person, status);
CREATE INDEX tasks_open_due ON automation.tasks (due) WHERE status = 'open';

CREATE TABLE automation.windows (
    id          uuid        PRIMARY KEY,
    recipe      text        NOT NULL,
    program     text,
    by          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    opened_at   timestamptz NOT NULL DEFAULT now(),
    closes_at   timestamptz,
    event_count integer     NOT NULL DEFAULT 0,
    run         uuid        REFERENCES automation.runs (id),
    status      text        NOT NULL CHECK (status IN ('open', 'closed'))
);

CREATE TABLE automation.window_events (
    "window"    uuid        NOT NULL REFERENCES automation.windows (id),
    seq         integer     NOT NULL,
    key         jsonb,
    payload_ref text,
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY ("window", seq)
);

CREATE TABLE automation.deliveries (
    id           uuid        PRIMARY KEY,
    run          uuid        REFERENCES automation.runs (id),
    program      text,
    action       text,
    group_key    jsonb,
    period       text,
    rows_ref     text,
    status       text        NOT NULL CHECK (status IN ('pending', 'delivered', 'failed', 'skipped')),
    digest_id    uuid,
    connector    text,
    delivered_at timestamptz,
    dry_run      boolean     NOT NULL DEFAULT false
);

CREATE TABLE automation.callbacks (
    id         uuid        PRIMARY KEY,
    deployment text        NOT NULL,
    url        text        NOT NULL,
    secret_ref text,
    kinds      text[]      NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE automation.approvals (
    id          uuid        PRIMARY KEY,
    object_kind text        NOT NULL CHECK (object_kind IN ('recipe', 'program', 'deployment', 'personal')),
    object      text        NOT NULL,
    fp_text     text,
    fp_values   text,
    fp          text,
    kind        text        NOT NULL CHECK (kind IN ('recipe', 'values', 'program', 'deployment', 'personal')),
    approver    text        NOT NULL,
    at          timestamptz NOT NULL DEFAULT now(),
    note        text,
    task        uuid        REFERENCES automation.tasks (id),
    status      text        NOT NULL CHECK (status IN ('approved', 'rejected', 'superseded'))
);

CREATE TABLE automation.batches (
    id           uuid        PRIMARY KEY,
    run          uuid        NOT NULL REFERENCES automation.runs (id),
    state        text        NOT NULL CHECK (state IN ('preview', 'held', 'committed', 'reverted')),
    rows         integer     NOT NULL DEFAULT 0,
    committed_by text,
    committed_at timestamptz,
    rev_of       uuid        REFERENCES automation.batches (id)
);

CREATE TABLE automation.batch_rows (
    batch     uuid    NOT NULL REFERENCES automation.batches (id),
    seq       integer NOT NULL,
    entity    text    NOT NULL,
    op        text    NOT NULL CHECK (op IN ('insert', 'update', 'transition')),
    key       jsonb,
    row       jsonb,
    group_key jsonb,
    status    text    NOT NULL CHECK (status IN ('staged', 'applied', 'failed', 'skipped')),
    error     text,
    PRIMARY KEY (batch, seq)
);

-- protocol/v1 (D-70, D-110): nine sections, INSERT-only, sealed at close by a `sealed` row in `automation`.
CREATE TABLE automation.run_protocol (
    run     uuid        NOT NULL REFERENCES automation.runs (id),
    section text        NOT NULL
        CHECK (section IN ('automation', 'islands', 'decision', 'actions', 'writes', 'people', 'turns', 'signals',
                           'receipts')),
    seq     integer     NOT NULL,
    payload jsonb       NOT NULL,
    at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (run, section, seq)
);

-- The table's owner is the service's own user, so a REVOKE would not bind it: the triggers do. An UPDATE is
-- always refused. A DELETE or a TRUNCATE (which fires no row trigger, hence the second, statement-level one) is
-- refused unless the session opted in with `SET automation.retention = 'on'` — the D-73 retention job (protocol
-- records 400 days) is the only writer that may remove a record.
CREATE FUNCTION automation.run_protocol_insert_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') AND current_setting('automation.retention', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'run_protocol is INSERT-only (D-110): % refused', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER run_protocol_insert_only
    BEFORE UPDATE OR DELETE ON automation.run_protocol
    FOR EACH ROW EXECUTE FUNCTION automation.run_protocol_insert_only();

CREATE TRIGGER run_protocol_no_truncate
    BEFORE TRUNCATE ON automation.run_protocol
    FOR EACH STATEMENT EXECUTE FUNCTION automation.run_protocol_insert_only();
