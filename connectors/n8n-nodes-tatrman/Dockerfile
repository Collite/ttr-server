FROM n8nio/n8n:2.44.6
USER root
COPY --chown=node:node dist/ /opt/tatrman/
ENV N8N_CUSTOM_EXTENSIONS=/opt/tatrman
ENV NODE_PATH=/usr/local/lib/node_modules/n8n/node_modules
USER node
