// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.parse

import org.tatrman.meta.v1.ModelDescriptor
import org.tatrman.meta.v1.ModelSnapshot
import org.tatrman.translate.snapshot.SnapshotModelHandle
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.veles.grpc.snapshotObjects

/**
 * [model] as the query path reads it: the translate service's own adapter ([SnapshotModelHandle]) over this model's
 * snapshot objects — tables, entities with their attributes, relations with their `join:` pairs, entity mappings
 * (a query-backed entity included) and attribute renames — so a saved query parses here exactly as the query
 * service later translates it (the member-vocabulary renderer reads the model the same way).
 *
 * Each query carries the model's stored status (PENDING): the worker builds this before any query has parsed, so no
 * saved-query body is in it.
 */
fun queryPathHandle(model: Model): ModelHandle =
    SnapshotModelHandle.from(
        ModelSnapshot
            .newBuilder()
            .setModel(ModelDescriptor.newBuilder().setVersion(model.version.value))
            .addAllObjects(snapshotObjects(model, packageOf = { null }) { it.parseStatus })
            .build(),
    )
