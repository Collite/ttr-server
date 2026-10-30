# BatchMatch fixtures

`hartland.json`: actual read-only BatchMatch replies recorded 2026-10-29 through a
loopback service/fuzzy port-forward, ghcr.io/collite/lex-matcher:0.11.30. The plan's
0.11.5 pin no longer describes the deployment. vocabularyVersion/layerVersions in
each reply identify its actual archive/member snapshot; no archive-v6 assumption.
The current world has different product names from PX; PX names mostly abstain.

`px-authored.json`: explicitly authored test-world responses from PX items.tsv,
including an ambiguous Czech ventilátor. These are NOT recordings from Hartland.
CI replays both through the real generated gRPC client. No live services needed.
Re-record real replies with scripts/record_lexmatcher.py --target <loopback:port>.
