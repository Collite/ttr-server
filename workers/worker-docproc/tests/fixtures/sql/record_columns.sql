-- SPDX-License-Identifier: Apache-2.0
-- Engine provenance/identity contract delta, absent from frozen PX DDL.
ALTER TABLE review ADD COLUMN row_id text NOT NULL UNIQUE;
ALTER TABLE review ADD COLUMN subject_id text NOT NULL;
ALTER TABLE review ALTER COLUMN annotation_refs TYPE bigint[] USING annotation_refs::bigint[];
CREATE INDEX review_subject_idx ON review(subject_id);
