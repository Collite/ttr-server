# Byte-for-byte upstream fixture copies. Override CORE for another checkout of
# Collite/ttr-core at feea9114991ed9c1952ceb8a8e863bf1ef1fbc92 (grammar/v0.18.8).
CORE ?= ../../../tatrman-xm
GOLDENS = $(CORE)/packages/kotlin/ttr-metadata/src/test/resources/xm-goldens

.PHONY: sync-fixtures check
sync-fixtures:
	cp $(GOLDENS)/graph.json/world.json tests/fixtures/export/graph.json
	cp $(GOLDENS)/defs.json/world.json tests/fixtures/export/defs.json
	cp $(GOLDENS)/ttl/world-owl.ttl tests/fixtures/export/cn.ttl
	printf 'fixture-1\n' > tests/fixtures/export/.snapshot

check:
	uv run --locked ruff check .
	uv run --locked mypy
	uv run --locked pytest -q
