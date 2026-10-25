// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.meta.v1.GetObjectResponse
import org.tatrman.meta.v1.ListObjectsResponse

/**
 * RG-P3.S0.T3 wire-compat gate. The `wire-golden` .bin fixtures were serialized from the
 * PRE-change build (no semantics fields). Parsing them under the semantics-
 * augmented schema must succeed, preserve every pre-existing field, and leave the
 * new semantics fields UNSET — proving the change is purely additive (new numbers,
 * reserved ranges untouched). If this ever fails, a field was renumbered or a
 * reserved slot reused.
 */
class MetaWireCompatSpec :
    StringSpec({

        fun bytes(name: String): ByteArray =
            checkNotNull(this::class.java.classLoader.getResourceAsStream("wire-golden/$name")).readBytes()

        "pre-change GetObjectResponse parses under the new schema, semantics unset" {
            val r = GetObjectResponse.parseFrom(bytes("get_object_table.bin"))
            // Pre-existing fields survive.
            r.objectDescriptor.localName shouldBe "customers"
            r.objectDescriptor.kind shouldBe "table"
            r.hasTable() shouldBe true
            r.table.columnsList.map { it.name } shouldBe listOf("id", "name", "tenant_id")
            r.table.primaryKeyList shouldBe listOf("id")
            // New additive fields are unset on old bytes.
            r.objectDescriptor.semanticsKind shouldBe ""
            r.table.hasSemantics() shouldBe false
            r.table.columnsList.all { !it.hasSemantics() } shouldBe true
        }

        "pre-change ListObjectsResponse parses under the new schema, semantics_kind empty" {
            val r = ListObjectsResponse.parseFrom(bytes("list_objects.bin"))
            (r.itemsCount > 0) shouldBe true
            r.itemsList.all { it.semanticsKind == "" } shouldBe true
            // A representative pre-existing descriptor field survives.
            r.itemsList.any { it.localName == "customers" && it.kind == "table" } shouldBe true
        }
    })
