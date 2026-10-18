// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.parse

import org.tatrman.meta.v1.ModelDescriptor
import org.tatrman.meta.v1.ModelSnapshot
import org.tatrman.translate.snapshot.SnapshotModelHandle
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ParseStatus
import org.tatrman.ttr.metadata.model.Query
import org.tatrman.veles.grpc.snapshotObjects

/**
 * [model] as the query path reads it: the translate service's own adapter ([SnapshotModelHandle]) over this model's
 * snapshot objects — tables, entities with their attributes, relations with their `join:` pairs, entity mappings
 * (a query-backed entity included) and attribute renames — so a saved query parses here exactly as the query
 * service later translates it (the member-vocabulary renderer reads the model the same way).
 *
 * Each query carries [parseStatusOf] — by default the model's stored status (PENDING: no saved-query body). LQ §8
 * (L-21): the worker parses a query that reads other saved queries in a later wave, against this handle built with the
 * earlier waves' results and [savedQueriesAsObj], which serves each parsed query under its obj name.
 */
fun queryPathHandle(
    model: Model,
    parseStatusOf: (Query) -> ParseStatus = { it.parseStatus },
    savedQueriesAsObj: Boolean = false,
): ModelHandle =
    SnapshotModelHandle.from(
        ModelSnapshot
            .newBuilder()
            .setModel(ModelDescriptor.newBuilder().setVersion(model.version.value))
            .addAllObjects(snapshotObjects(model, packageOf = { null }, parseStatusOf))
            .build(),
        savedQueriesAsObj,
    )
