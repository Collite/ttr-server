<!-- SPDX-License-Identifier: Apache-2.0 -->
# Changelog

## 0.2.0

- Tatrman Trigger polls the Automation Server for closed runs. Existing workflows using its poll configuration keep their deployment and Since settings.
- The Trigger emits a run ID and protocol reference in `_tatrman`. It follows the Server’s list pagination and deduplicates runs across polls.
- D-142 removes webhook delivery. Update workflows that used webhook delivery to poll after a run closes.
