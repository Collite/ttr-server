-- SPDX-License-Identifier: Apache-2.0
-- D-185 (S2.3, ⚑AU-P2-8): the toolchain an approval was given under, written by approve(). A text fp moved only by a
-- toolchain bump is then reported `rebuild: [text]` (D-137). Rows written before V9 keep NULL: no rebuild claim.
ALTER TABLE automation.approvals ADD COLUMN toolchain text NULL;
