#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Exact historical compatibility bytes; shared by the guard and its mutation probes.
FROZEN_PATHS=()
FROZEN_HASHES=()
while read -r digest path; do
  FROZEN_PATHS+=("$path")
  FROZEN_HASHES+=("$digest")
done <<'FROZEN_FILES'
951d78b8e60f0934c94adac6e64cf36be71a2f256d13b2a68d5d2f3f92ed0d8d services/metadata/src/test/resources/wire-golden/get_object_table.bin
dec5f503c5d9c28bd363fcb7ddedd83b5748504ae6a0ffe45d08d15c08906f34 services/metadata/src/test/resources/wire-golden/list_objects.bin
baa60f2c000ff000e1a8d4ee21fb43ace4300e5fd3beb3bd117b33e1acbc21fe shared/proto/compat/fuzzy-v1-pre-lp.desc
38378e7d81c5a2a62c953980f7cb6359a5e98d32cebdd2b561d0f55aa8530ea6 shared/proto/compat/resolver-v1-pre-lattice.desc
2a9d3485518fd3652ef9664a78c9d86875893c732f6b4c6535960f7a26a40c20 services/automation/src/test/resources/hartland-h3/model/er/catalog.ttrm
f7756f0158dfd927b68684e0c3eccceb450395a211fbd772bf45317fa9620cc7 services/automation/src/test/resources/hartland-h3/model/er/customer_revenue.ttrm
223829f246210a0a83ea9622d5e2364924a109c4fa19e39f26fda0e64d2d6a8f services/automation/src/test/resources/hartland-h3/model/er/parties.ttrm
e1528801b1aa36839c964712793e09ef3ac58ec7f6d3619e84a91961fb7195bc services/automation/src/test/resources/hartland-h3/model/er/sales.ttrm
6f5fb6fafcf8e6af129a7f8862cbf15123aadfb6a13cc986ca84bc1b3ab71ffd services/automation/src/test/resources/hartland-h3/model/queries/q_hartland.ttrm
FROZEN_FILES
