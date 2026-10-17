// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.meta.v1.GetObjectRequest
import org.tatrman.meta.v1.GetSnapshotRequest
import org.tatrman.meta.v1.ObjectEntry
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.snapshot.SnapshotModelHandle
import org.tatrman.translate.v1.Language
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.veles.LogicalQueryFixture

/**
 * GetSnapshot / GetObject serve a relation WITH its detail — the entities it joins and its `join:` pairs.
 *
 * The snapshot is the model the translate service runs on, and the translator conditions a bare entity `JOIN` from
 * the relations it is handed (`SnapshotModelHandle.relations()`). Served descriptor-only, that list was empty: every
 * bare join fell through to the foreign keys MAP_TO_PHYSICAL finds, and a join the model can only condition from
 * `join:` pairs — onto an entity stacked on a query (LQ), which has no foreign key — could not be conditioned at all.
 */
class GetSnapshotRelationSpec :
    StringSpec({
        val model = LogicalQueryFixture.model()
        val registry = MetadataRegistry().also { it.swap(model, ModelGraph.build(model)) }
        val service = MetadataServiceImpl(registry = registry, packageIndex = PackageIndex())

        fun entity(name: String): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("entity")
                .setName(name)
                .build()

        "every relation is served with its entities and join pairs" {
            val snapshot = service.getSnapshot(GetSnapshotRequest.getDefaultInstance()).snapshot
            val relations = snapshot.objectsList.filter { it.objectDescriptor.kind == "relation" }
            relations.map { it.contentCase }.toSet() shouldBe setOf(ObjectEntry.ContentCase.RELATION)
            relations.map { it.objectDescriptor.localName } shouldContainExactlyInAnyOrder
                listOf("sale_shopper", "shopper_household", "shopper_revenue_household")
            val stacked = relations.single { it.objectDescriptor.localName == "shopper_revenue_household" }.relation
            stacked.fromEntity shouldBe entity("shopper_revenue")
            stacked.toEntity shouldBe entity("household")
            stacked.joinPairsList.map { it.fromAttr.name to it.toAttr.name } shouldBe
                listOf("shopper_revenue.current_household" to "household.household_id")
        }

        "GetObject serves the same detail" {
            val qname =
                registry
                    .read()!!
                    .model
                    .objectByQname()
                    .values
                    .single { it.kind == "relation" && it.qname.name == "sale_shopper" }
                    .qname
                    .toProto()
            val detail = service.getObject(GetObjectRequest.newBuilder().setQualifiedName(qname).build())
            detail.hasRelation() shouldBe true
            detail.relation.fromEntity shouldBe entity("sale")
            detail.relation.joinPairsCount shouldBe 1
        }

        "the translator conditions a bare JOIN onto an entity stacked on a query from the snapshot's relations" {
            val handle = SnapshotModelHandle.from(service.getSnapshot(GetSnapshotRequest.getDefaultInstance()).snapshot)
            handle.relations().map { it.fromEntity.name to it.toEntity.name } shouldContainExactlyInAnyOrder
                listOf("sale" to "shopper", "shopper" to "household", "shopper_revenue" to "household")
            val parsed =
                Translator(handle)
                    .parseToRelNode(
                        "SELECT household.buy_potential, SUM(shopper_revenue.total_revenue) AS revenue " +
                            "FROM shopper_revenue JOIN household GROUP BY household.buy_potential",
                        Language.SQL,
                        targetSchema = SchemaCode.ER,
                        sourceSchema = SchemaCode.ER,
                    ).shouldBeInstanceOf<ParseResult.Success>()
            parsed.modelJoinsSql!! shouldContain
                "\"shopper_revenue\".\"current_household\" = \"household\".\"household_id\""

            fun join(n: PlanNode): PlanNode? =
                when {
                    n.hasJoin() -> n
                    n.hasProject() -> join(n.project.input)
                    n.hasAggregate() -> join(n.aggregate.input)
                    else -> null
                }
            checkNotNull(join(parsed.plan)).join.hasCondition() shouldBe true
        }
    })
