# SPDX-License-Identifier: Apache-2.0
FROM ghcr.io/collite/n8n:2.44.6
LABEL org.opencontainers.image.source="https://github.com/Collite/ttr-server"
USER root
# Build context is the unpacked pnpm tarball, not the workspace or a PVC directory.
COPY package.json README.md /opt/tatrman-nodes/node_modules/@collite/n8n-nodes-tatrman/
COPY dist/ /opt/tatrman-nodes/node_modules/@collite/n8n-nodes-tatrman/dist/
COPY --chmod=755 scripts/entrypoint.sh /opt/tatrman-entrypoint.sh
RUN find /opt/tatrman-nodes -type d -exec chmod 755 {} + && find /opt/tatrman-nodes -type f -exec chmod 644 {} +
# Resolve the exact SDK already shipped by the pinned n8n runtime; no registry token in layers.
ENV NODE_PATH=/usr/local/lib/node_modules/n8n/node_modules
USER node
ENTRYPOINT ["tini", "--", "/opt/tatrman-entrypoint.sh"]
