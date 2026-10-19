// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import org.tatrman.meta.v1.Direction as ProtoDirection
import org.tatrman.meta.v1.EdgeType as ProtoEdgeType
import org.tatrman.plan.v1.QualifiedName as ProtoQualifiedName
import org.tatrman.plan.v1.SchemaCode as ProtoSchemaCode
import org.tatrman.ttr.metadata.graph.Direction as DomainDirection
import org.tatrman.ttr.metadata.graph.EdgeType as DomainEdgeType
import org.tatrman.ttr.metadata.model.QualifiedName as DomainQualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode as DomainSchemaCode

/**
 * The proto ↔ library boundary (MD2). The `ttr-metadata` library is de-proto'd —
 * its model owns `QualifiedName`/`SchemaCode` and the graph owns `EdgeType`/
 * `Direction`. The Veles gRPC facade converts here at the edge; proto types
 * never cross into a library call, and library types never reach the wire raw.
 *
 * The proto surface is FROZEN (MD7): proto `SchemaCode` has no `WORLD` member, so a
 * library `WORLD` qname (never produced by the Veles model, but mapped defensively)
 * degrades to `SCHEMA_CODE_UNSPECIFIED`. So does grammar 0.16's `AUTOMATION` tier (`model automation`
 * objects): the automation service reads them from its own bundle, not over this wire.
 */

internal fun DomainSchemaCode.toProto(): ProtoSchemaCode =
    when (this) {
        DomainSchemaCode.DB -> ProtoSchemaCode.DB
        DomainSchemaCode.ER -> ProtoSchemaCode.ER
        DomainSchemaCode.CNC -> ProtoSchemaCode.CNC
        DomainSchemaCode.WS -> ProtoSchemaCode.WS
        DomainSchemaCode.OBJ -> ProtoSchemaCode.OBJ
        DomainSchemaCode.WORLD -> ProtoSchemaCode.SCHEMA_CODE_UNSPECIFIED
        DomainSchemaCode.AUTOMATION -> ProtoSchemaCode.SCHEMA_CODE_UNSPECIFIED
        DomainSchemaCode.UNSPECIFIED -> ProtoSchemaCode.SCHEMA_CODE_UNSPECIFIED
    }

internal fun ProtoSchemaCode.toDomain(): DomainSchemaCode =
    when (this) {
        ProtoSchemaCode.DB -> DomainSchemaCode.DB
        ProtoSchemaCode.ER -> DomainSchemaCode.ER
        ProtoSchemaCode.CNC -> DomainSchemaCode.CNC
        ProtoSchemaCode.WS -> DomainSchemaCode.WS
        ProtoSchemaCode.OBJ -> DomainSchemaCode.OBJ
        else -> DomainSchemaCode.UNSPECIFIED
    }

internal fun DomainQualifiedName.toProto(): ProtoQualifiedName =
    ProtoQualifiedName
        .newBuilder()
        .setSchemaCode(schemaCode.toProto())
        .setNamespace(namespace)
        .setName(name)
        .setPackage(`package`)
        .build()

internal fun ProtoQualifiedName.toDomain(): DomainQualifiedName =
    DomainQualifiedName(
        schemaCode = schemaCode.toDomain(),
        namespace = namespace,
        name = name,
        `package` = `package`,
    )

internal fun ProtoEdgeType.toDomain(): DomainEdgeType? =
    when (this) {
        ProtoEdgeType.DEFINES -> DomainEdgeType.DEFINES
        ProtoEdgeType.REFERENCES -> DomainEdgeType.REFERENCES
        ProtoEdgeType.MAPS_TO -> DomainEdgeType.MAPS_TO
        ProtoEdgeType.USES -> DomainEdgeType.USES
        else -> null // EDGE_TYPE_UNSPECIFIED / UNRECOGNIZED — filtered out
    }

internal fun DomainEdgeType.toProto(): ProtoEdgeType =
    when (this) {
        DomainEdgeType.DEFINES -> ProtoEdgeType.DEFINES
        DomainEdgeType.REFERENCES -> ProtoEdgeType.REFERENCES
        DomainEdgeType.MAPS_TO -> ProtoEdgeType.MAPS_TO
        DomainEdgeType.USES -> ProtoEdgeType.USES
    }

internal fun ProtoDirection.toDomain(): DomainDirection =
    when (this) {
        ProtoDirection.INCOMING -> DomainDirection.INCOMING
        ProtoDirection.BOTH -> DomainDirection.BOTH
        else -> DomainDirection.OUTGOING // OUTGOING / UNSPECIFIED default
    }
