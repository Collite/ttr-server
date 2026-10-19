// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.e2e

import com.google.protobuf.kotlin.toByteString
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.meta.v1.GetQueryRequest
import org.tatrman.meta.v1.GetQueryResponse
import org.tatrman.meta.v1.ParseStatus
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.ScanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.query.cache.CompiledPlanCache
import org.tatrman.query.client.DispatcherClient
import org.tatrman.query.client.SavedQueryClient
import org.tatrman.query.client.TranslatorClient
import org.tatrman.query.client.TranslatorDetectClient
import org.tatrman.query.client.TranslatorTranslateClient
import org.tatrman.query.client.ValidatorClient
import org.tatrman.query.grpc.QueryServiceImpl
import org.tatrman.query.retry.RetryPolicy
import org.tatrman.query.v1.RunSavedQueryRequest
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseRequest
import org.tatrman.translate.v1.ParseResponse
import org.tatrman.validate.v1.ValidateRequest
import org.tatrman.validate.v1.ValidateResponse
import org.tatrman.worker.v1.ResultBatch
import java.time.Duration

/**
 * LQ-T7·S2 (contract L-31) — RunSavedQuery runs a saved query by name from the plan the metadata service stored: no
 * re-parse of its text, the plan handed to Run as a `REL_NODE` source in its own lane, so the ER half maps a logical
 * query to tables and Validate secures it as any Run. Unknown, still-parsing and failed queries are refused with codes.
 */
class RunSavedQueryE2ESpec :
    StringSpec({
        fun qname(
            schema: SchemaCode,
            namespace: String,
            name: String,
        ): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(schema)
                .setNamespace(namespace)
                .setName(name)
                .build()

        val logical = qname(SchemaCode.ER, "query", "shopper_revenue")
        val dbQuery = qname(SchemaCode.DB, "dbo", "store_revenue")

        /** A logical query's stored plan reads entities. */
        val erStored =
            PlanNode
                .newBuilder()
                .setScan(
                    ScanNode
                        .newBuilder()
                        .setObject(qname(SchemaCode.ER, "entity", "sale"))
                        .addOutputColumns(ColumnRef.newBuilder().setName("revenue")),
                ).build()

        /** A db query's stored plan reads tables. */
        val dbStored =
            PlanNode
                .newBuilder()
                .setTableScan(
                    TableScanNode
                        .newBuilder()
                        .setTable(qname(SchemaCode.DB, "dbo", "store_sales"))
                        .addOutputColumns(ColumnRef.newBuilder().setName("ss_net_paid")),
                ).build()

        fun stored(plan: PlanNode): GetQueryResponse =
            GetQueryResponse
                .newBuilder()
                .setParseStatus(ParseStatus.PARSE_STATUS_PARSED)
                .setCanonicalForm(plan)
                .build()

        class Harness(
            val metadata: (GetQueryRequest) -> GetQueryResponse,
        ) {
            val asked = mutableListOf<GetQueryRequest>()
            val parses = mutableListOf<ParseRequest>()
            val validated = mutableListOf<ValidateRequest>()
            var dispatched = 0
            val service =
                QueryServiceImpl(
                    TranslatorClient { req ->
                        synchronized(parses) { parses += req }
                        // The plan comes back as sent (the ER half), or as the physical plan (the DB re-entry).
                        val plan =
                            if (req.targetSchema ==
                                SchemaCode.DB
                            ) {
                                dbStored
                            } else {
                                PlanNode.parseFrom(req.source.toByteArray(Charsets.ISO_8859_1))
                            }
                        ParseResponse
                            .newBuilder()
                            .setPlan(plan)
                            .setContext(req.context)
                            .build()
                    },
                    TranslatorDetectClient { throw IllegalStateException("a stored plan is not detected") },
                    TranslatorTranslateClient { throw IllegalStateException("translate not used") },
                    ValidatorClient { req ->
                        synchronized(validated) { validated += req }
                        ValidateResponse
                            .newBuilder()
                            .setPlan(req.plan)
                            .setContext(req.context)
                            .build()
                    },
                    DispatcherClient { _ ->
                        dispatched++
                        flowOf(
                            ResultBatch
                                .newBuilder()
                                .setIsFirst(true)
                                .setIsLast(true)
                                .setArrowIpc(ByteArray(0).toByteString())
                                .build(),
                        )
                    },
                    CompiledPlanCache(100, Duration.ofMinutes(60)),
                    RetryPolicy(maxAttempts = 1, initialBackoffMillis = 1, multiplier = 1.0, jitterPercent = 0),
                    rawSavedQueries =
                        SavedQueryClient { req ->
                            synchronized(asked) { asked += req }.let { metadata(req) }
                        },
                )
        }

        val context =
            PipelineContext
                .newBuilder()
                .setUserId("u1")
                .addAuthRoles("analyst")
                .setModelVersion("v1")
                .build()

        fun request(name: QualifiedName): RunSavedQueryRequest =
            RunSavedQueryRequest
                .newBuilder()
                .setQualifiedName(name)
                .setContext(context)
                .build()

        fun errors(batches: List<ResultBatch>): List<ResponseMessage> = batches.flatMap { it.messagesList }

        "a logical query runs from its stored plan: the ER half, both Validate passes, dispatch — no text re-parsed" {
            val h = Harness { stored(erStored) }
            val batches = h.service.runSavedQuery(request(logical)).toList()
            errors(batches).shouldBeEmpty()
            h.asked.single().qualifiedName shouldBe logical
            h.asked.single().includeCanonicalForm shouldBe true
            h.parses.map { it.sourceLanguage to it.targetSchema } shouldBe
                listOf(Language.REL_NODE to SchemaCode.ER, Language.REL_NODE to SchemaCode.DB)
            PlanNode.parseFrom(
                h.parses
                    .first()
                    .source
                    .toByteArray(Charsets.ISO_8859_1),
            ) shouldBe erStored
            h.validated.size shouldBe 2
            h.validated.all { it.context.authRolesList == listOf("analyst") } shouldBe true
            h.dispatched shouldBe 1
        }

        "a db query runs in the DB lane: one REL_NODE parse to DB, one Validate pass" {
            val h = Harness { stored(dbStored) }
            errors(h.service.runSavedQuery(request(dbQuery)).toList()).shouldBeEmpty()
            h.parses.map { it.sourceLanguage to it.targetSchema } shouldBe listOf(Language.REL_NODE to SchemaCode.DB)
            h.validated.size shouldBe 1
            h.dispatched shouldBe 1
        }

        "parameters ride the context to Validate, bound by name" {
            val h = Harness { stored(dbStored) }
            val withParams =
                request(dbQuery)
                    .toBuilder()
                    .setContext(
                        context.toBuilder().addParameters(
                            org.tatrman.plan.v1.ParameterBinding
                                .newBuilder()
                                .setName("min_revenue")
                                .setType("float")
                                .setValue(
                                    org.tatrman.plan.v1.Value
                                        .newBuilder()
                                        .setFloatValue(1000.0),
                                ),
                        ),
                    ).build()
            h.service.runSavedQuery(withParams).toList()
            h.validated
                .single()
                .context.parametersList
                .map { it.name } shouldBe listOf("min_revenue")
        }

        "refused before anything runs: unknown, still parsing, failed, no stored plan" {
            val cases =
                listOf(
                    GetQueryResponse
                        .newBuilder()
                        .addMessages(ResponseMessage.newBuilder().setCode("object_not_found"))
                        .build() to "saved_query_not_found",
                    GetQueryResponse.newBuilder().setParseStatus(ParseStatus.PARSE_STATUS_PENDING).build() to
                        "saved_query_pending",
                    GetQueryResponse
                        .newBuilder()
                        .setParseStatus(ParseStatus.PARSE_STATUS_FAILED)
                        .setParseErrorMessage("lane_mismatch: reads tables")
                        .build() to "saved_query_parse_failed",
                    GetQueryResponse.newBuilder().setParseStatus(ParseStatus.PARSE_STATUS_PARSED).build() to
                        "saved_query_parse_failed",
                )
            for ((response, code) in cases) {
                val h = Harness { response }
                val messages = errors(h.service.runSavedQuery(request(logical)).toList())
                messages.single().code shouldBe code
                h.parses.shouldBeEmpty()
                h.dispatched shouldBe 0
            }
            val failed =
                errors(
                    Harness {
                        GetQueryResponse
                            .newBuilder()
                            .setParseStatus(ParseStatus.PARSE_STATUS_FAILED)
                            .setParseErrorMessage("lane_mismatch: reads tables")
                            .build()
                    }.service.runSavedQuery(request(logical)).toList(),
                ).single()
            failed.humanMessage shouldContain "er.query.shopper_revenue"
            failed.humanMessage shouldContain "lane_mismatch: reads tables"
        }

        "with no metadata service wired: saved_query_unavailable, nothing runs" {
            val service =
                QueryServiceImpl(
                    TranslatorClient { throw IllegalStateException("not reached") },
                    TranslatorDetectClient { throw IllegalStateException("not reached") },
                    TranslatorTranslateClient { throw IllegalStateException("not reached") },
                    ValidatorClient { throw IllegalStateException("not reached") },
                    DispatcherClient { throw IllegalStateException("not reached") },
                    CompiledPlanCache(100, Duration.ofMinutes(60)),
                    RetryPolicy(maxAttempts = 1, initialBackoffMillis = 1, multiplier = 1.0, jitterPercent = 0),
                )
            errors(service.runSavedQuery(request(logical)).toList()).single().code shouldBe "saved_query_unavailable"
        }

        "the lane is read off the stored plan, wherever the entity scan sits" {
            QueryServiceImpl.readsEntities(erStored) shouldBe true
            QueryServiceImpl.readsEntities(dbStored) shouldBe false
            val nested =
                PlanNode
                    .newBuilder()
                    .setFilter(
                        org.tatrman.plan.v1.FilterNode
                            .newBuilder()
                            .setInput(erStored),
                    ).build()
            QueryServiceImpl.readsEntities(nested) shouldBe true
        }
    })
