FROM n8nio/n8n:2.44.6
LABEL org.opencontainers.image.source="https://github.com/Collite/kantheon"
USER root
# Build context is the unpacked pnpm tarball, not the workspace or a PVC directory.
COPY --chown=node:node package.json README.md /opt/tatrman-nodes/node_modules/@collite/n8n-nodes-tatrman/
COPY --chown=node:node dist/ /opt/tatrman-nodes/node_modules/@collite/n8n-nodes-tatrman/dist/
ENV N8N_CUSTOM_EXTENSIONS=/opt/tatrman-nodes
# Resolve the exact SDK already shipped by the pinned n8n runtime; no registry token in layers.
ENV NODE_PATH=/usr/local/lib/node_modules/n8n/node_modules
USER node
