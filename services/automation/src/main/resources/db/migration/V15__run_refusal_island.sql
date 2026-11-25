-- SPDX-License-Identifier: Apache-2.0
-- D-280: the refusing island belongs to the run's pinned manifest. Existing rows have no location.
ALTER TABLE automation.runs ADD COLUMN refused_island text;
CREATE INDEX runs_refused_island_expiry ON automation.runs (ended_at, id) WHERE refused_island IS NOT NULL;
