#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Resolve public images before BuildKit/compose. Regional caches may miss a digest;
# vendor proxies and shared runner IPs may be rate-limited. Never change OM bytes.
set -euo pipefail
mode="${1:-lane}"
output="${2:?usage: pull-images.sh base|lane environment-file}"
case "$mode" in base|lane) ;; *) exit 64 ;; esac

pull_verified() {
    local key="$1" path="$2" version="$3" expected="$4" registry image digests resolved
    for registry in mirror.gcr.io docker.io docker.getcollate.io; do
        image="${registry}/${path}:${version}"
        if ! docker pull "$image"; then
            echo "Image source unavailable: $image; trying the next official mirror" >&2
            continue
        fi
        digests="$(docker image inspect "$image" --format '{{json .RepoDigests}}')"
        if [ -n "$expected" ]; then
            resolved="$(jq -r --arg suffix "@${expected}" \
                '[.[] | select(endswith($suffix))][0] // empty' <<< "$digests")"
        else
            resolved="$(jq -r '.[0] // empty' <<< "$digests")"
        fi
        if [ -z "$resolved" ]; then
            echo "Digest verification failed for $image" >&2
            continue
        fi
        # Only fixed registry/path/version inputs and an OCI digest enter this file.
        printf '%s=%s\n' "$key" "$resolved" >> "$output"
        return 0
    done
    echo "No verified image source available for ${path}:${version}" >&2
    return 1
}

pull_verified OM_INGESTION_IMAGE openmetadata/ingestion-base 2.0.6 \
    sha256:b7228ccffde7e5d0eb89e48d2c5247476d7b0e87924e11de0c929150a28668b9
if [ "$mode" = lane ]; then
    case "${OM_OPENSEARCH_VERSION:-3.4.0}" in 3.4.0|3.0.0) ;; *) exit 64 ;; esac
    pull_verified OM_SERVER_IMAGE openmetadata/server 2.0.6 \
        sha256:87706a33003a65b850e1ec832735bddcf0d4a5ab680a79922b0556d4cfc1d359
    pull_verified OM_POSTGRES_IMAGE library/postgres 16 ""
    pull_verified OM_OPENSEARCH_IMAGE opensearchproject/opensearch \
        "${OM_OPENSEARCH_VERSION:-3.4.0}" ""
fi
