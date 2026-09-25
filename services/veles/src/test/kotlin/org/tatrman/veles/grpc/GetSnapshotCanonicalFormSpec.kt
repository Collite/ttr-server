// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.meta.v1.GetSnapshotRequest
import org.tatrman.meta.v1.GetSnapshotResponse
import org.tatrman.meta.v1.ParseStatus as ProtoParseStatus
import org.tatrman.meta.v1.QueryDetail
import org.tatrman.plan.v1.PlanNode
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.DbColumn
import org.tatrman.ttr.metadata.model.DbSchema
import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ModelVersion
import org.tatrman.ttr.metadata.model.ParseStatus
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.Query
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.veles.parse.QueryParseState
import org.tatrman.veles.parse.QueryParseWorker
import java.time.Instant

/**
 * GH #112 — GetSnapshot carries each parsed query's canonical form, and its ETag moves while the
 * parse worker fills them in.
 *
 * The snapshot is the model the translate service runs on: a query-backed entity is expanded into
 * the saved query's plan, so a snapshot without the plans made every ER query over such an entity
 * fail (`PlanNode case 'NODE_NOT_SET'`). The plans land after the model swap, while the model
 * version is fixed at swap time — so the ETag has to move with them too, or a consumer that polled
 * during the parse window keeps the plan-less snapshot until the model next changes.
 */
class GetSnapshotCanonicalFormSpec :
    StringSpec({

        val customers = QualifiedName(SchemaCode.DB, "dbo", "customers")
        val okQn = QualifiedName(SchemaCode.UNSPECIFIED, "q", "ok")
        val badQn = QualifiedName(SchemaCode.UNSPECIFIED, "q", "bad")

        fun model(
            version: String = "v1",
            okSql: String = "SELECT id, name FROM customers WHERE name <> ''",
        ): Model =
            Model(
                descriptor = ModelDescriptor(id = "test", name = "test"),
                version = ModelVersion(value = version, swappedAt = Instant.now()),
                schemas =
                    mapOf(
                        "db" to
                            DbSchema(
                                namespace = "dbo",
                                tables =
                                    mapOf(
                                        customers to
                                            DbTable(
                                                internalId = "t-customers",
                                                qname = customers,
                                                columns =
                                                    listOf(
                                                        DbColumn(
                                                            internalId = "c-id",
                                                            qname = QualifiedName(SchemaCode.DB, "dbo", "id"),
                                                            table = customers,
                                                            dataType = "int",
                                                            nullable = false,
                                                            isPrimaryKey = true,
                                                        ),
                                                        DbColumn(
                                                            internalId = "c-name",
                                                            qname = QualifiedName(SchemaCode.DB, "dbo", "name"),
                                                            table = customers,
                                                            dataType = "text",
                                                        ),
                                                    ),
                                                primaryKey = listOf("id"),
                                            ),
                                    ),
                            ),
                    ),
                mappings = emptyList(),
                queries =
                    listOf(
                        Query(
                            internalId = "q-ok",
                            qname = okQn,
                            sourceLanguage = "SQL",
                            sourceText = okSql,
                            parseStatus = ParseStatus.ParsePending,
                        ),
                        Query(
                            internalId = "q-bad",
                            qname = badQn,
                            sourceLanguage = "SQL",
                            sourceText = "SELEKT * FROM customers",
                            parseStatus = ParseStatus.ParsePending,
                        ),
                    ).associateBy { it.qname },
            )

        /** A served model with a live parse state, reset as the swap path resets it — nothing parsed yet. */
        fun served(): Triple<Model, QueryParseState, MetadataServiceImpl> {
            val m = model()
            val registry = MetadataRegistry()
            registry.swap(m, ModelGraph.build(m))
            val state = QueryParseState().also { it.reset(m.version.value, m.queries.keys) }
            return Triple(m, state, MetadataServiceImpl(registry = registry, parseState = state))
        }

        suspend fun MetadataServiceImpl.snapshot(ifNoneMatch: String = ""): GetSnapshotResponse =
            getSnapshot(GetSnapshotRequest.newBuilder().setIfNoneMatch(ifNoneMatch).build())

        fun GetSnapshotResponse.query(name: String): QueryDetail =
            snapshot.objectsList.single { it.hasQuery() && it.objectDescriptor.qualifiedName.name == name }.query

        "before the worker has run: the queries are PENDING and carry no canonical form" {
            val (_, _, svc) = served()
            val ok = svc.snapshot().query("ok")
            ok.parseStatus shouldBe ProtoParseStatus.PARSE_STATUS_PENDING
            ok.hasCanonicalForm() shouldBe false
        }

        "a parsed query carries the worker's plan, byte for byte; a failed one carries none" {
            val (m, state, svc) = served()
            QueryParseWorker().also { it.parseAll(m, state).join() }.close()

            val resp = svc.snapshot()
            val ok = resp.query("ok")
            ok.parseStatus shouldBe ProtoParseStatus.PARSE_STATUS_PARSED
            val parsed = state.get(m.version.value, okQn).shouldBeInstanceOf<ParseStatus.ParseSuccess>()
            ok.canonicalForm shouldBe PlanNode.parseFrom(parsed.canonicalFormProtoBytes)
            ok.canonicalForm.nodeCase shouldNotBe PlanNode.NodeCase.NODE_NOT_SET

            val bad = resp.query("bad")
            bad.parseStatus shouldBe ProtoParseStatus.PARSE_STATUS_FAILED
            bad.hasCanonicalForm() shouldBe false
        }

        "the ETag moves as the plans land: the parse-window ETag gets the full snapshot back" {
            val (m, state, svc) = served()
            val duringParse = svc.snapshot()
            QueryParseWorker().also { it.parseAll(m, state).join() }.close()

            val after = svc.snapshot(ifNoneMatch = duringParse.etag)
            after.notModified shouldBe false
            after.etag shouldNotBe duringParse.etag
            after.query("ok").hasCanonicalForm() shouldBe true
        }

        "and settles once parsing is done: the settled ETag is not modified" {
            val (m, state, svc) = served()
            QueryParseWorker().also { it.parseAll(m, state).join() }.close()

            val settled = svc.snapshot()
            val again = svc.snapshot(ifNoneMatch = settled.etag)
            again.notModified shouldBe true
            again.etag shouldBe settled.etag
        }

        "the ETag is not the model version while parse state is live; the snapshot still names it" {
            val (_, _, svc) = served()
            val resp = svc.snapshot()
            resp.etag shouldNotBe "v1"
            resp.snapshot.model.version shouldBe "v1"
        }

        "without a live parse state the snapshot is a function of the model, and the ETag its version" {
            val m = model()
            val registry = MetadataRegistry()
            registry.swap(m, ModelGraph.build(m))
            MetadataServiceImpl(registry).snapshot().etag shouldBe "v1"
        }

        // review-102 F4 — the state answers only for the model it was reset for.

        "the swap window: until the parse state is reset for the new model, it serves no plan from the old one" {
            val v1 = model()
            val registry = MetadataRegistry().also { it.swap(v1, ModelGraph.build(v1)) }
            val state = QueryParseState().also { it.reset(v1.version.value, v1.queries.keys) }
            val svc = MetadataServiceImpl(registry = registry, parseState = state)
            QueryParseWorker().also { it.parseAll(v1, state).join() }.close()
            svc.snapshot().query("ok").hasCanonicalForm() shouldBe true

            // v2 changes the query's text. The registry publishes it, THEN runs its listeners — the
            // search-index rebuild first, the parse-state reset after it. In between:
            val v2 = model(version = "v2", okSql = "SELECT id FROM customers")
            registry.swap(v2, ModelGraph.build(v2))
            val window = svc.snapshot().query("ok")
            window.parseStatus shouldBe ProtoParseStatus.PARSE_STATUS_PENDING
            window.hasCanonicalForm() shouldBe false
        }

        "a stale job from the previous model cannot overwrite the current model's result" {
            val v1 = model()
            val v2 = model(version = "v2", okSql = "SELECT id FROM customers")
            val state = QueryParseState()
            state.reset(v1.version.value, v1.queries.keys)
            state.reset(v2.version.value, v2.queries.keys)
            QueryParseWorker().also { it.parseAll(v2, state).join() }.close()
            val current = state.get("v2", okQn).shouldBeInstanceOf<ParseStatus.ParseSuccess>()

            // v1's job for the same qname lands last — as it can, since jobs are never cancelled.
            state.set("v1", okQn, ParseStatus.ParseFailure("stale"))
            state.get("v2", okQn) shouldBe current
            state.get("v1", okQn) shouldBe null
        }
    })
