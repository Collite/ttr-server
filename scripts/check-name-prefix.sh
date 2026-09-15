#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Naming gate (RV-P(-1), ⚑RV-6). The `ttr-` prefix was dropped from every
# service, shared library, tool, worker and infra module in this repo; this
# check stops it creeping back in a later PR.
#
#   scripts/check-name-prefix.sh          fail on any non-allowlisted `ttr-`
#
# Two — and only two — classes of `ttr-` are legitimate here:
#
#   1. Coordinates of the UPSTREAM toolchain published by the `Collite/ttr-core`
#      repo (`org.tatrman:ttr-parser`, `ttr-metadata`, `ttr-translator`,
#      `ttr-snapshot`, `ttr-lexicon`, …). Those are a third-party dependency;
#      this repo does not get to rename them.
#   2. Kubernetes namespace names (`ttr-server`, and the ephemeral CI namespace
#      `ttr-umbrella`). Renaming a live namespace is a destructive estate
#      migration, not a code rename — explicitly out of scope for ⚑RV-6.
#
# Plus the RV schema ids (`ttr-lexicon/v1`, `ttr-skill/v1`, `ttr-nlp-routing/v1`),
# which are WIRE CONTRACT: ⚑RV-6 ruled they stay exactly as they are, because
# renaming them buys nothing and breaks every client.
#
# The left-hand `(?<![A-Za-z0-9_])` guard keeps innocent substrings — "attr-",
# "getattr-" — from tripping the gate.
#
# Prose sometimes has to spell an old name out loud: a rename table, a
# CHANGELOG entry, a migration note. Put the marker `naming-gate-ok` on such a
# line and the gate skips it. This file is skipped wholesale for the same
# reason — it documents the prefix it forbids.
set -euo pipefail

cd "$(dirname "$0")/.."

# Longest alternatives first: `metadata-git` must match before `metadata`.
#
# `snapshot` is class 1 — the upstream `org.tatrman:ttr-snapshot` (tar+zstd archive
# container), taken at RV-P1.4 T3 alongside the already-listed `ttr-lexicon`, which
# it carries. `ttr-lexicon-compile` needs no entry of its own: `lexicon` strips the
# prefix and leaves `-compile`, which the left-hand guard no longer matches.
# `ttr-operator-library` (RV-P4.4) is an RV SCHEMA ID, the same class as `ttr-lexicon`
# and `ttr-skill`: it is the `schemaVersion` inside the compiled operator-library
# artifact that `tatrman`'s compiler writes, so golem-py must spell it exactly.
# `ttr-nlp` (NLS-P0, ⚑NLS-D1) is a THIRD class, and the only member of it: a
# PUBLISHED DISTRIBUTION NAME owned by this repo — the PyPI project `ttr-nlp`,
# living at `shared/libs/python/ttr-nlp` and imported as `ttrnlp`. It is the same
# kind of thing as class 1 (a coordinate you do not get to rename) with the
# difference that we are the publisher; once it is on PyPI the name is public
# API. ⚑RV-6's target was the internal module prefix, and that ruling stands:
# the SERVICE is `services/nlp`, and `services/ttr-nlp` — deleted at NLS-P0.T1
# as leftover rename debris — must not come back.
#
# That last sentence needs the leading guard to be true. The gate works by
# STRIPPING allowlisted spellings out of each line and then re-testing what is
# left, so a bare `nlp` entry would erase `ttr-nlp` wherever it appeared —
# `services/ttr-nlp/generated/x.py:1:…` strips to `services//generated/…` and
# passes. The guard leaves any `ttr-` that directly follows `services/`
# un-stripped, so it trips the gate. It applies to every entry, not just `nlp`:
# ⚑RV-6 made every service bare-named, so no `services/ttr-*` path is
# allowlistable. Legitimate spellings are unaffected — the wheel lives at
# `shared/libs/python/ttr-nlp`.
#
# `ttr-morph` (NLS-P8.1, ⚑LMP-D4) joins that third class with one difference
# worth writing down: it is a distribution name owned by this repo that is
# DELIBERATELY NEVER PUBLISHED. It lives at `shared/libs/python/ttr-morph`,
# imports as `ttrmorph`, and ships only inside the `nlp-morph-tools` image and
# `services/morph-studio`. Unpublished does not make it renameable — it is the
# name in every `pyproject.toml` dependency, every `uv.lock`, both Dockerfiles
# and the justfile loops — and the SERVICE built on it is bare-named
# (`services/morph-studio`), exactly as ⚑RV-6 requires. ⚑ The gate has been red
# on this branch since P8.1 created the package and nobody added the entry; the
# wave's PR opens at P9.3, which is where that stopped being tolerable.
#
# GitHub repository names are a FOURTH class: `Collite/ttr-core`, `Collite/ttr-server`,
# `Collite/ttr-demo`. The repos were renamed into the Collite organization on 2026-09-15
# after GitHub retired their old `Collite/<name>` paths; a repository name is an external
# address, not a module name, so it is matched only with its `Collite/` owner in front.
#
# The slash is written `\/` because ALLOW is interpolated into `s/…//` below; an
# unescaped one would end the substitution and perl would refuse the pattern.
#
# Order matters: `nlp-routing` must precede bare `nlp` so the longer schema id
# matches first.
ALLOW='(?<!services\/)ttr-(metadata-git|metadata-adoption|metadata|translator-extraction|translator|plan-proto|parser|writer|semantics|server-proto|snapshot|lexicon|skill|operator-library|nlp-routing|nlp|morph|server|umbrella-ci|umbrella|\*|\{)|Collite\/ttr-(core|server|demo)'

hits="$(
  git grep -nI -P '(?<![A-Za-z0-9_])ttr-' -- . ':(exclude)scripts/check-name-prefix.sh' \
    | grep -v 'naming-gate-ok' \
    | perl -pe "s/${ALLOW}//g" \
    | perl -ne 'print if /(?<![A-Za-z0-9_])ttr-/' \
    || true
)"

if [ -n "$hits" ]; then
  echo "$hits" | while IFS= read -r line; do
    echo "::error::non-allowlisted \`ttr-\` prefix: $line"
  done
  cat <<'EOF'

Naming gate failed (RV-P(-1) / ⚑RV-6).

The `ttr-` prefix was dropped repo-wide: services, shared/libs, tools, workers
and infra all use bare functional names (query, dispatch, nlp, lex-matcher, …).
Use the new name, or — if this really is an upstream `org.tatrman:ttr-*`
coordinate, a K8s namespace, or an RV schema id — add it to ALLOW in
scripts/check-name-prefix.sh with a note saying which of the two classes it is.
EOF
  exit 1
fi

echo "naming gate: no non-allowlisted \`ttr-\` prefix"
