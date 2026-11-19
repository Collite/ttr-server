#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
# n8n 2.44.6 scans this directory with its package loader. Keep only a symlink on
# the PVC; the versioned package and metadata remain immutable in the image.
package=/opt/tatrman-nodes/node_modules/@collite/n8n-nodes-tatrman
scope="${N8N_USER_FOLDER:-/home/node}/.n8n/nodes/node_modules/@collite"
mkdir -p "$scope"
link="$scope/n8n-nodes-tatrman"
if [ -e "$link" ] && [ ! -L "$link" ]; then
  echo 'Refusing to replace an existing Tatrman npm installation; remove that installation deliberately before using the derived image.' >&2
  exit 1
fi
ln -sfn "$package" "$link"
if [ "${1:-}" = node ]; then exec "$@"; fi
exec /docker-entrypoint.sh "$@"
