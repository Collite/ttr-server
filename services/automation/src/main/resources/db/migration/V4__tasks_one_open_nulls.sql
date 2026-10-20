-- SPDX-License-Identifier: Apache-2.0
-- D-98's "one open task per (recipe, key, action)" with NULLs held equal. V1's index let two open tasks share a
-- triple whenever one of its parts was NULL — a set-level ask (key NULL) could be opened twice — because a unique
-- index treats NULLs as distinct by default. NULLS NOT DISTINCT is PG15+ (the clusters run PG18).
--
-- A task without a recipe stays outside the rule, as it was: the Studio's approval asks (D-101: no run, no recipe)
-- may be open side by side.
DROP INDEX automation.tasks_one_open;

CREATE UNIQUE INDEX tasks_one_open
    ON automation.tasks (recipe, key, action) NULLS NOT DISTINCT
    WHERE status = 'open' AND recipe IS NOT NULL;
