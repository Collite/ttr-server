# This dockerfile builds an image for the backend package.
# It should be executed with the root of the repo as docker context.
#
# Before building this image, be sure to have run the following commands in the repo root:
#
# yarn install --immutable
# yarn tsc
# yarn build:backend
#
# Once the commands have been run, you can build the image using `yarn build-image`
#
# Alternatively, there is also a multi-stage Dockerfile documented here:
# https://backstage.io/docs/deployment/docker#multi-stage-build

# Base images are ghcr.io/collite copies of the Docker official images (.github/workflows/mirror-base-images.yml).
FROM ghcr.io/collite/node:24-trixie-slim

# Set Python interpreter for `node-gyp` to use
ENV PYTHON=/usr/bin/python3

# Install isolate-vm dependencies, these are needed by the @backstage/plugin-scaffolder-backend.
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt-get update && \
    apt-get install -y --no-install-recommends python3 g++ build-essential && \
    rm -rf /var/lib/apt/lists/*

# Install python3-pip and venv for TechDocs
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt-get update && \
    apt-get install -y --no-install-recommends python3-pip python3-venv && \
    rm -rf /var/lib/apt/lists/*

# Install mkdocs-techdocs-core
# Using --break-system-packages is necessary in newer Debian/Python versions or use a venv
RUN pip3 install mkdocs-techdocs-core --break-system-packages

USER node

# This should create the app dir as `node`.
# If it is instead created as `root` then the `tar` command below will fail: `can't create directory 'packages/': Permission denied`.
# If this occurs, then ensure BuildKit is enabled (`DOCKER_BUILDKIT=1`) so the app dir is correctly created as `node`.
WORKDIR /app

# Copy files needed by Yarn
COPY --chown=node:node infra/backstage/.yarn ./.yarn
COPY --chown=node:node infra/backstage/.yarnrc.yml ./
COPY --chown=node:node infra/backstage/backstage.json ./

# This switches many Node.js dependencies to production mode.
ENV NODE_ENV=production

# This disables node snapshot for Node 20 to work with the Scaffolder
ENV NODE_OPTIONS="--no-node-snapshot"

# Copy repo skeleton first, to avoid unnecessary docker cache invalidation.
# The skeleton contains the package.json of each package in the monorepo,
# and along with yarn.lock and the root package.json, that's enough to run yarn install.
COPY --chown=node:node infra/backstage/yarn.lock infra/backstage/package.json infra/backstage/packages/backend/dist/skeleton.tar.gz ./
RUN tar xzf skeleton.tar.gz && rm skeleton.tar.gz

RUN --mount=type=cache,target=/home/node/.cache/yarn,sharing=locked,uid=1000,gid=1000 \
    yarn workspaces focus --all --production && rm -rf "$(yarn cache clean)"

# This will include the examples, if you don't need these simply remove this line
#COPY --chown=node:node infra/backstage/examples ./examples

# Then copy the rest of the backend bundle, along with any other files we might want.
COPY --chown=node:node infra/backstage/packages/backend/dist/bundle.tar.gz infra/backstage/app-config*.yaml ./
RUN tar xzf bundle.tar.gz && rm bundle.tar.gz

# Copy docs and services to root for access by catalog locations
# This ensures ../../../docs and ../../../services (from packages/backend) resolve correctly
COPY --chown=node:node docs /docs
COPY --chown=node:node services /services
COPY --chown=node:node infra/backstage/templates /app/templates

CMD ["node", "packages/backend", "--config", "app-config.yaml", "--config", "app-config.production.yaml"]
