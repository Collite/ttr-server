-- SPDX-License-Identifier: Apache-2.0
-- CN-P5 S5.3 (cn contracts §6, A-CN-90) — the candidate store. Candidates are not canon: nothing here reaches the model
-- snapshot except the registered vocabulary versions, which the service publishes as `cn.vocabularyVersions`.

-- One row per proposal. proposal / provenance / resolution are the cn-candidate.v1 document's members as submitted
-- (resolution once a steward or a merge resolved it); status is the document's, state the store's (state
-- pending_merge keeps the document proposed). content_hash dedupes a proposal submitted twice (A-CN-92).
CREATE TABLE cn_candidate (
    id             uuid        PRIMARY KEY,
    content_hash   text        NOT NULL UNIQUE,
    proposal       jsonb       NOT NULL,
    provenance     jsonb       NOT NULL,
    status         text        NOT NULL,          -- proposed | rejected | promoted (the document's)
    resolution     jsonb,
    state          text        NOT NULL,          -- proposed | pending_merge | rejected | promoted
    producer       text        NOT NULL,
    kind           text        NOT NULL,          -- the concept's stereotype, or term | relation
    question_count integer,
    document_count integer,
    submitted_by   text        NOT NULL,          -- the verified principal
    submitted_at   timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    promotion      jsonb                          -- the opened promotion: qname, diff, pull request, base, by, at
);

CREATE INDEX cn_candidate_state ON cn_candidate (state, submitted_at DESC);
CREATE INDEX cn_candidate_producer ON cn_candidate (producer);

-- The audit rows (T7): every submit, duplicate, rejection, promotion and merge, with who and when.
CREATE TABLE cn_candidate_event (
    seq          bigserial   PRIMARY KEY,
    candidate_id uuid        NOT NULL,
    action       text        NOT NULL,
    actor        text        NOT NULL,
    at           timestamptz NOT NULL,
    detail       text        NOT NULL DEFAULT ''
);

CREATE INDEX cn_candidate_event_candidate ON cn_candidate_event (candidate_id, seq);

-- T5 (A-CN-67, A-CN-94) — the registered vocabulary versions, in registration order.
CREATE TABLE cn_vocabulary_version (
    seq           bigserial   UNIQUE,
    vocabulary    text        NOT NULL,
    version       text        NOT NULL,
    bundle        text,
    registered_at timestamptz NOT NULL,
    registered_by text        NOT NULL,
    PRIMARY KEY (vocabulary, version)
);
