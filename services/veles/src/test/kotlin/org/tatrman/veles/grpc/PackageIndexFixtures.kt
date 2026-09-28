// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import org.tatrman.ttr.metadata.source.SourceSnapshot

/** ttr-server#111 — an index serving exactly [snapshots], as if their model had just swapped in. */
internal fun servedIndex(vararg snapshots: SourceSnapshot): PackageIndex =
    PackageIndex().apply {
        snapshots.forEach { record(it) }
        commit()
    }
