// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.RowWrites
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.query.cache.CompiledPlanCache
import org.tatrman.query.client.DispatcherClient
import org.tatrman.query.client.TranslatorClient
import org.tatrman.query.client.TranslatorDetectClient
import org.tatrman.query.client.TranslatorTranslateClient
import org.tatrman.query.client.ValidatorClient
import org.tatrman.query.retry.RetryPolicy
import org.tatrman.query.v1.RunRequest
import org.tatrman.translate.v1.Language
import java.time.Duration

class RowWritesUnsupportedSpec :
    StringSpec({
        fun service() =
            QueryServiceImpl(
                TranslatorClient { error("row writes must not reach translation") },
                TranslatorDetectClient { error("row writes must not reach detection") },
                TranslatorTranslateClient { error("row writes must not reach translation") },
                ValidatorClient { error("row writes must not reach validation") },
                DispatcherClient { error("row writes must not reach dispatch") },
                CompiledPlanCache(10, Duration.ofMinutes(1)),
                RetryPolicy(maxAttempts = 1),
            )

        fun request(
            schema: SchemaCode,
            bypassCache: Boolean,
        ): RunRequest =
            RunRequest
                .newBuilder()
                .setSourceLanguage(Language.REL_NODE)
                .setSourceSchema(schema)
                .setSource(
                    PlanNode
                        .newBuilder()
                        .setRowWrites(RowWrites.getDefaultInstance())
                        .build()
                        .toByteArray()
                        .toString(Charsets.ISO_8859_1),
                ).setBypassCache(bypassCache)
                .setContext(PipelineContext.newBuilder().setCorrelationId("d1-row-writes"))
                .build()

        "Compile refuses ROW_WRITES before downstream calls for every schema and cache mode" {
            val query = service()
            for (schema in listOf(SchemaCode.ER, SchemaCode.DB, SchemaCode.SCHEMA_CODE_UNSPECIFIED)) {
                for (bypassCache in listOf(false, true)) {
                    val response = query.compile(request(schema, bypassCache))
                    response.hasPlan() shouldBe false
                    response.messagesList.single().code shouldBe "row_writes_unsupported"
                    response.messagesList.single().severity shouldBe Severity.ERROR
                    response.context.correlationId shouldBe "d1-row-writes"
                }
            }
        }

        "Run refuses ROW_WRITES in one terminal batch before downstream calls" {
            val query = service()
            for (schema in listOf(SchemaCode.ER, SchemaCode.DB, SchemaCode.SCHEMA_CODE_UNSPECIFIED)) {
                for (bypassCache in listOf(false, true)) {
                    val batch = query.run(request(schema, bypassCache)).toList().single()
                    batch.messagesList.single().code shouldBe "row_writes_unsupported"
                    batch.messagesList.single().severity shouldBe Severity.ERROR
                    batch.isFirst shouldBe true
                    batch.isLast shouldBe true
                    batch.arrowIpc.isEmpty shouldBe true
                    batch.context.correlationId shouldBe "d1-row-writes"
                    query.activeRunCount shouldBe 0
                }
            }
        }
    })
