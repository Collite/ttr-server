-- DX contracts §2.6; the production migration belongs to docwh-pipeline P2 S2.2.
CREATE SCHEMA IF NOT EXISTS pipeline;
CREATE TABLE IF NOT EXISTS pipeline.run_artefacts (
    run_id TEXT PRIMARY KEY,
    protocol JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
