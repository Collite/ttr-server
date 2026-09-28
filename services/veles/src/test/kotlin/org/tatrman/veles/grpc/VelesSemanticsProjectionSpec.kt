// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.tatrman.meta.v1.GetObjectRequest
import org.tatrman.meta.v1.GetModelRequest
import org.tatrman.meta.v1.GetObjectResponse
import org.tatrman.meta.v1.GetSnapshotRequest
import org.tatrman.meta.v1.ListObjectsRequest
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.veles.grpc.MetadataServiceImpl
import java.nio.file.Path

/**
 * RG-P3.S0.T2 — the semantics projection spec (test-first). Pins that Veles projects
 * the `semantics { }` surface onto `meta.v1` — BOTH facets of ttr-semantics vocabulary
 * v3: grounding (`kind:` / `role:`, RG-P3.S0) and mention (`name:` / `code:` /
 * `measures:`, MS-P2·S1):
 *
 *   fixture-semantics/semantics/{59-semantics.ttrm (er), 60-semantics-db.ttrm (db)}
 *     -> FileBasedSource -> ModelReconciler -> MetadataRegistry -> MetadataServiceImpl
 *     -> get_object / list_objects carry EntitySemantics / AttributeSemantics
 *
 * Provenance: 59/60-semantics are the grammar's golden conformance fixtures
 * (tatrman `tests/conformance/fixtures/`), vendored here verbatim — so they declare no
 * `package`, and sit in `semantics/` to belong to the one their directory implies (GetModel
 * serves packages, not directories: ttr-server#111). kind/role are
 * STRINGS (RS-33) — the open vocabulary lives in ttr-semantics, not in the proto.
 *
 * RED until RG-P3.S0.T3 adds the proto fields and T4 populates them.
 */
class VelesSemanticsProjectionSpec :
    StringSpec({

        fun serviceFrom(resourceDir: String): MetadataServiceImpl {
            val root = Path.of(checkNotNull(this::class.java.classLoader.getResource(resourceDir)).toURI())
            val source =
                FileBasedSource(
                    sourceId = resourceDir,
                    priority = 100,
                    storage = LocalFsStorage(id = resourceDir, rootPath = root),
                )
            val reconciler = ModelReconciler(ModelDescriptor(id = "test", name = "test", description = resourceDir))
            val snapshot = source.load()
            val result = reconciler.reconcile(listOf(snapshot))
            val registry = MetadataRegistry()
            registry.swap(result.model, ModelGraph.build(result.model), result.warnings + result.errors)
            return MetadataServiceImpl(registry, packageIndex = PackageIndex().apply { record(snapshot) })
        }

        suspend fun MetadataServiceImpl.descriptorByName(
            kind: String,
            localName: String,
        ): QualifiedName {
            val items = listObjects(ListObjectsRequest.newBuilder().setKind(kind).build()).itemsList
            return items.first { it.localName == localName }.qualifiedName
        }

        suspend fun MetadataServiceImpl.getByName(
            kind: String,
            localName: String,
        ): GetObjectResponse =
            getObject(
                GetObjectRequest.newBuilder().setQualifiedName(descriptorByName(kind, localName)).build(),
            )

        // ---- (a) er entity kind + period role columns + code_format ----

        "get_object on a period_table entity carries EntitySemantics.kind" {
            val svc = serviceFrom("fixture-semantics")
            val r = svc.getByName("entity", "AccountingPeriod")
            r.entity.hasSemantics() shouldBe true
            r.entity.semantics.kind shouldBe "period_table"
        }

        "period_table role attributes carry role + code_format" {
            val svc = serviceFrom("fixture-semantics")
            svc
                .getByName("attribute", "start_date")
                .attribute.semantics.role shouldBe "period_start"
            svc
                .getByName("attribute", "end_date")
                .attribute.semantics.role shouldBe "period_end"
            val period = svc.getByName("attribute", "period").attribute.semantics
            period.role shouldBe "period_code"
            period.codeFormat shouldBe "yyyyMM"
        }

        // ---- (b) event_date -> resolved period qname; amount -> currency_attribute ----

        "event_date attribute resolves its period reference to the period entity" {
            val svc = serviceFrom("fixture-semantics")
            val sem = svc.getByName("attribute", "txn_date").attribute.semantics
            sem.role shouldBe "event_date"
            sem.hasPeriod() shouldBe true
            sem.period.name shouldBe "AccountingPeriod"
        }

        "amount attribute carries its currency sibling-attribute local name" {
            val svc = serviceFrom("fixture-semantics")
            val sem = svc.getByName("attribute", "amount").attribute.semantics
            sem.role shouldBe "amount"
            sem.currencyAttribute shouldBe "currency_code"
        }

        // ---- (c) db twin: table kind + column roles from ONE get_object(table) ----

        "get_object on a db period table carries table + column semantics in one call" {
            val svc = serviceFrom("fixture-semantics")
            val table = svc.getByName("table", "accounting_period").table
            table.semantics.kind shouldBe "period_table"
            val periodCol = table.columnsList.first { it.name == "period" }
            periodCol.semantics.role shouldBe "period_code"
            periodCol.semantics.codeFormat shouldBe "yyyyMM"
            table.columnsList
                .first { it.name == "start_date" }
                .semantics.role shouldBe "period_start"
        }

        // ---- (d) list_objects descriptors carry semantics_kind (discovery accelerator) ----

        "list_objects descriptors carry semantics_kind for kinded objects, empty otherwise" {
            val svc = serviceFrom("fixture-semantics")
            val entities = svc.listObjects(ListObjectsRequest.newBuilder().setKind("entity").build()).itemsList
            entities.first { it.localName == "AccountingPeriod" }.semanticsKind shouldBe "period_table"
            entities.first { it.localName == "PoiLatLon" }.semanticsKind shouldBe "poi"
            entities.first { it.localName == "FxRate" }.semanticsKind shouldBe "fx_rate"
            // Transaction declares no entity-level kind → empty.
            entities.first { it.localName == "Transaction" }.semanticsKind shouldBe ""
        }

        // ---- (e) an object with NO semantics has the field unset ----

        "an entity without a semantics block has semantics unset (not empty-kind)" {
            val svc = serviceFrom("fixture-semantics")
            val r = svc.getByName("entity", "Transaction")
            r.entity.hasSemantics() shouldBe false
        }

        // ---- (g) MS mention facet: measures on the entity ----

        "an entity declaring measures serves them in DECLARED order" {
            val svc = serviceFrom("fixture-semantics")
            val sem = svc.getByName("entity", "SalesOrderLine").entity.semantics
            // Declared `measures: [amount_czk, { attribute: quantity, aggregation: avg }]`.
            // Order is load-bearing: the FIRST is the entity's default measure (contracts §7).
            sem.measuresList shouldBe listOf("amount_czk", "quantity")
        }

        "measures alone are enough to serve an EntitySemantics — no kind: required" {
            val svc = serviceFrom("fixture-semantics")
            val r = svc.getByName("entity", "SalesOrderLine")
            // New with MS: before it, the message existed only when `semanticsKind` was set.
            r.entity.hasSemantics() shouldBe true
            r.entity.semantics.kind shouldBe ""
            // …and the discovery accelerator stays empty, because no kind was declared.
            svc
                .listObjects(ListObjectsRequest.newBuilder().setKind("entity").build())
                .itemsList
                .first { it.localName == "SalesOrderLine" }
                .semanticsKind shouldBe ""
        }

        "name:/code: alone do NOT create an EntitySemantics — they ride the existing fields" {
            val svc = serviceFrom("fixture-semantics")
            val detail = svc.getByName("entity", "Customer").entity
            // Customer declares `semantics { name: customer_name, code: customer_no }` and
            // nothing else. name/code have been on the wire since long before MS, so the
            // mention facet reaches consumers through them (fed by the model-side D2 merge
            // in MS-P1·S2 — this asserts the chain, not new veles code) and the semantics
            // message stays absent rather than appearing empty.
            detail.nameAttribute shouldBe "customer_name"
            detail.codeAttribute shouldBe "customer_no"
            detail.hasSemantics() shouldBe false
        }

        "the two facets coexist on one entity block without interfering" {
            val svc = serviceFrom("fixture-semantics")
            val detail = svc.getByName("entity", "AccountingPeriod").entity
            // `semantics { kind: period_table, code: period }` — grounding kind served,
            // mention code served through code_attribute, measures empty.
            detail.semantics.kind shouldBe "period_table"
            detail.semantics.measuresList shouldBe emptyList()
            detail.codeAttribute shouldBe "period"
        }

        // ---- (h) MS mention facet: aggregation, denormalised onto the attribute ----

        "a measure attribute carries its declared aggregation" {
            val svc = serviceFrom("fixture-semantics")
            svc
                .getByName("attribute", "quantity")
                .attribute.semantics.aggregation shouldBe "avg"
        }

        "a bare-listed measure defaults to sum" {
            val svc = serviceFrom("fixture-semantics")
            val sem = svc.getByName("attribute", "amount_czk").attribute.semantics
            sem.aggregation shouldBe "sum"
            // The default is supplied by the vocabulary, and it does not invent a role.
            sem.role shouldBe ""
        }

        "an attribute NOT listed as a measure serves an empty aggregation beside its role" {
            val svc = serviceFrom("fixture-semantics")
            // Transaction.amount is a grounding amount and its owner declares no measures:
            // the grounding facet must be untouched by the mention one.
            val sem = svc.getByName("attribute", "amount").attribute.semantics
            sem.role shouldBe "amount"
            sem.currencyAttribute shouldBe "currency_code"
            sem.aggregation shouldBe ""
        }

        "both facets on ONE attribute arrive together" {
            val svc = serviceFrom("fixture-mention")
            // The case the mention facet exists for: a column that is both an amount to
            // convert and the measure people ask for. `role:` is single-valued, so this
            // could not be expressed before MS moved measures to the entity.
            val sem = svc.getByName("attribute", "total_czk").attribute.semantics
            sem.role shouldBe "amount"
            sem.currencyAttribute shouldBe "ccy"
            sem.aggregation shouldBe "sum"
        }

        "a db table projects the mention facet exactly as an entity does" {
            val svc = serviceFrom("fixture-mention")
            val table = svc.getByName("table", "invoice_line").table
            table.hasSemantics() shouldBe true
            table.semantics.kind shouldBe ""
            table.semantics.measuresList shouldBe listOf("amount", "qty")
            table.columnsList
                .first { it.name == "qty" }
                .semantics.aggregation shouldBe "avg"
            table.columnsList
                .first { it.name == "amount" }
                .semantics.aggregation shouldBe "sum"
            table.columnsList
                .first { it.name == "label" }
                .semantics.aggregation shouldBe ""
            // review-083 F1 — and the other two thirds of the facet. `invoice_line` declares
            // `name: label, code: line_no`; before F1 the declaration was accepted, validated
            // and then dropped, because DbTableDetail had nowhere to put it. This assertion is
            // the one the fixture was written for and could not make.
            table.nameAttribute shouldBe "label"
            table.codeAttribute shouldBe "line_no"
        }

        "a db table declaring no mention keys serves them EMPTY, not guessed" {
            val svc = serviceFrom("fixture-semantics")
            // `60-semantics-db.ttrm` is a vendored golden: grounding facet only, no mention
            // keys anywhere. The new fields must read as "nothing declared" rather than fall
            // back to a primary key or a first text column — absence is the answer (MS-R4).
            val table = svc.getByName("table", "accounting_period").table
            table.nameAttribute shouldBe ""
            table.codeAttribute shouldBe ""
        }

        // ---- (i) MS: the other two projection paths, and D4 ----

        "the GetModel bundle carries measures and aggregation" {
            val svc = serviceFrom("fixture-semantics")
            val bundle =
                svc
                    .getModel(GetModelRequest.newBuilder().addPackages("semantics").build())
                    .model
            // The bundle builds details through its OWN call path (toModelBundleEntity /
            // toModelBundleAttribute), which is the one a defaulted owner argument would have
            // silently skipped — hence a case of its own rather than trust in get_object.
            val line = bundle.entitiesList.first { it.objectDescriptor.localName == "SalesOrderLine" }
            line.detail.semantics.measuresList shouldBe listOf("amount_czk", "quantity")
            line.attributesList
                .first { it.objectDescriptor.localName == "quantity" }
                .detail.semantics.aggregation shouldBe "avg"
        }

        "the GetSnapshot projection carries measures and aggregation" {
            val svc = serviceFrom("fixture-semantics")
            val snap = svc.getSnapshot(GetSnapshotRequest.getDefaultInstance())
            val entries = snap.snapshot.objectsList
            entries
                .first { it.objectDescriptor.localName == "SalesOrderLine" }
                .entity.semantics.measuresList shouldBe listOf("amount_czk", "quantity")
            entries
                .first { it.objectDescriptor.localName == "quantity" }
                .attribute.semantics.aggregation shouldBe "avg"
        }

        "D4 — no accelerator on ObjectDescriptor for the mention facet" {
            // MS-D4 ruled that the mention facet gets NO descriptor-level accelerator: unlike
            // `kind`, it is not a discovery filter. Asserted against the proto SCHEMA rather
            // than one message, so it fails the day a field is added regardless of fixture.
            val fields = ObjectDescriptor.getDescriptor().fields.map { it.name }
            fields shouldNotContain "measures"
            fields shouldNotContain "aggregation"
            fields shouldNotContain "name_attribute"
            // And the entity that declares measures still advertises an EMPTY accelerator,
            // because it declares no `kind:` — measures must not leak into that string.
            val svc = serviceFrom("fixture-semantics")
            svc
                .getByName("entity", "SalesOrderLine")
                .objectDescriptor.semanticsKind shouldBe ""
        }

        // ---- (f) invalid semantics degrades: object served w/o semantics + load warning ----

        "an invalid semantics block degrades — object served without semantics + a load issue" {
            val svc = serviceFrom("fixture-semantics-invalid")
            // The object still loads and is served (no gRPC error, structural detail intact)…
            val r = svc.getByName("attribute", "booked")
            r.messagesList.none { it.code == "object_not_found" } shouldBe true
            r.attribute.type.shouldNotBeEmpty()
            // …but the unresolved period reference is NOT projected as semantics,
            r.attribute.hasSemantics() shouldBe false
            // …and the load issue surfaces through the model status/validation path.
            val validate =
                svc.validateModel(
                    org.tatrman.meta.v1.ValidateModelRequest
                        .getDefaultInstance(),
                )
            (validate.warningsCount + validate.errorsCount) shouldBeGreaterThan 0
        }
    })
