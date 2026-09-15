// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.plan.v1.LimitOffsetNode
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.model.BootFixtureModel
import org.tatrman.translate.model.StaticModelHandleProvider
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseRequest
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translate.v1.UnparseRequest

/**
 * The CALLER'S ROW WINDOW SURVIVES THE REL_NODE → DB PASS.
 *
 * `query` serves a paged read in two passes: parse to an ER plan, wrap the caller's `row_window` onto
 * that plan as a root `LimitOffset` (`QueryServiceImpl.windowed`), then hand the wrapped plan back to
 * this service as `source_language = REL_NODE, target_schema = DB`. Every page after the first is
 * therefore a statement about THIS seam: if the second pass dropped or rewrote the root node, each
 * page would return page one, and both repos' suites would stay green — kantheon's report fetcher
 * would read the same 200 rows forever and print "All 650 movements", because nothing on either side
 * asserted that the node crosses.
 *
 * That gap was written up as review-093's "Guards weaker than their record": the two-pass path's
 * correctness rested on reading `ttr-translator`'s `PlanNodeDecoder` and finding it sound. Reading is
 * not a guard — and the library moved (translator 0.10.1 → 0.10.3) days later.
 *
 * Deliberate choices:
 *   · the `test` lane, not `componentTest`. This spec drives the real translator through
 *     `TranslatorServiceImpl` exactly as the unit specs beside it do, and CI's `./gradlew build` runs
 *     `test`. review-090's only HIGH was a spec that lived in `componentTest` while the stage's
 *     `:test` never ran it.
 *   · the request is built the way the PRODUCTION caller builds it — the plan serialised into
 *     `ParseRequest.source` as an ISO-8859-1 byte string, which is what
 *     `QueryServiceImpl.translateToDbPlain` sends. A tidier encoding would test a path no caller uses.
 *   · the SQL assertion pins the ANSI spelling the translator actually emits
 *     (`OFFSET n ROWS FETCH NEXT m ROWS ONLY`), measured, not the MySQL-style `LIMIT m OFFSET n`.
 */
class RowWindowSeamSpec :
    StringSpec({

        val service = TranslatorServiceImpl(StaticModelHandleProvider(BootFixtureModel.handle()))

        /** Pass 1: the plan a caller's SQL parses to, before any window is applied. */
        suspend fun planFor(sql: String): PlanNode {
            val parsed =
                service.parseToRelNode(
                    ParseRequest
                        .newBuilder()
                        .setSource(sql)
                        .setSourceLanguage(Language.SQL)
                        .setSourceSchema(SchemaCode.DB)
                        .setTargetSchema(SchemaCode.DB)
                        .build(),
                )
            parsed.messagesList shouldHaveSize 0
            return parsed.plan
        }

        /** `QueryServiceImpl.windowed` — the caller's row window as a root LimitOffset. */
        fun windowed(
            plan: PlanNode,
            limit: Long,
            offset: Long,
        ): PlanNode =
            PlanNode
                .newBuilder()
                .setLimitOffset(
                    LimitOffsetNode
                        .newBuilder()
                        .setInput(plan)
                        .setLimit(limit)
                        .setOffset(offset),
                ).build()

        /** Pass 2, encoded as `QueryServiceImpl.translateToDbPlain` encodes it. */
        suspend fun relNodeToDb(plan: PlanNode): PlanNode {
            val parsed =
                service.parseToRelNode(
                    ParseRequest
                        .newBuilder()
                        .setSource(String(plan.toByteArray(), Charsets.ISO_8859_1))
                        .setSourceLanguage(Language.REL_NODE)
                        .setTargetSchema(SchemaCode.DB)
                        .build(),
                )
            parsed.messagesList shouldHaveSize 0
            return parsed.plan
        }

        suspend fun postgres(plan: PlanNode): String {
            val unparsed =
                service.unparseFromRelNode(
                    UnparseRequest
                        .newBuilder()
                        .setPlan(plan)
                        .setTargetLanguage(Language.SQL)
                        .setTargetDialect(SqlDialect.POSTGRESQL)
                        .build(),
                )
            unparsed.messagesList shouldHaveSize 0
            return unparsed.output
        }

        "a caller's row window survives REL_NODE → DB as a root LimitOffset" {
            val page2 = relNodeToDb(windowed(planFor("SELECT id, name FROM QSUBJEKT ORDER BY id"), 200, 400))

            page2.nodeCase shouldBe PlanNode.NodeCase.LIMIT_OFFSET
            page2.limitOffset.limit shouldBe 200L
            page2.limitOffset.offset shouldBe 400L
        }

        // The offset is the half that makes paging paging: a dropped LIMIT yields too many rows and is
        // noticed, a dropped OFFSET yields page one forever and reads as a complete answer.
        "and reaches the emitted SQL, both bounds" {
            val sql = postgres(relNodeToDb(windowed(planFor("SELECT id, name FROM QSUBJEKT ORDER BY id"), 200, 400)))

            sql shouldContain "OFFSET 400"
            sql shouldContain "FETCH NEXT 200"
        }

        // Without this the pair above would pass against a translator that wrapped EVERY plan in some
        // default window — the assertions would be true for the wrong reason.
        "an unwrapped plan comes back unwindowed — the node is the caller's, not the translator's" {
            val plain = relNodeToDb(planFor("SELECT id, name FROM QSUBJEKT ORDER BY id"))

            plain.nodeCase shouldBe PlanNode.NodeCase.SORT
            postgres(plain) shouldContain "ORDER BY"
        }

        // A window of 0 rows is the shape a caller sends when it states no limit (`row_window` unset),
        // and `windowed` skips wrapping then — so if one is wrapped explicitly it must still cross
        // intact rather than being optimised away into "no window at all".
        "an offset-only window crosses too — paging past the end is still a window" {
            val offsetOnly = relNodeToDb(windowed(planFor("SELECT id, name FROM QSUBJEKT ORDER BY id"), 0, 800))

            offsetOnly.nodeCase shouldBe PlanNode.NodeCase.LIMIT_OFFSET
            offsetOnly.limitOffset.offset shouldBe 800L
            postgres(offsetOnly) shouldContain "OFFSET 800"
        }
    })
