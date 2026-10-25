// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.plan.v1.SchemaCode as ProtoSchemaCode
import org.tatrman.ttr.metadata.model.SchemaCode as DomainSchemaCode

/**
 * The frozen proto `SchemaCode` (MD7) against the library's, which grows with the grammar: the five wire codes
 * round-trip; the tiers that never cross the metadata wire — WORLD, AUTOMATION (grammar 0.16), CN (0.17, the
 * conceptual tier) — degrade to unspecified. Grammar 0.17.3 added CN and metadata stopped compiling on master.
 */
class SchemaCodeConverterSpec :
    StringSpec({
        "the five wire codes round-trip" {
            for (code in listOf(
                DomainSchemaCode.DB,
                DomainSchemaCode.ER,
                DomainSchemaCode.CNC,
                DomainSchemaCode.WS,
                DomainSchemaCode.OBJ,
            )) {
                code.toProto().toDomain() shouldBe code
            }
        }

        "the tiers that never cross the wire degrade to unspecified" {
            for (code in listOf(
                DomainSchemaCode.WORLD,
                DomainSchemaCode.AUTOMATION,
                DomainSchemaCode.CN,
                DomainSchemaCode.UNSPECIFIED,
            )) {
                code.toProto() shouldBe ProtoSchemaCode.SCHEMA_CODE_UNSPECIFIED
            }
        }
    })
