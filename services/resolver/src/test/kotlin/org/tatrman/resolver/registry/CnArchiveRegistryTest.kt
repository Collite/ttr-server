// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.registry

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.tatrman.resolver.CnEstate
import org.tatrman.resolver.CnEstate.replace
import org.tatrman.resolver.model.Lift
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.model.TermSense
import org.tatrman.ttr.lexicon.compile.LexiconPacker
import java.nio.file.Files
import kotlin.io.path.writeBytes

/**
 * CN-P4 S4.4 (cn contracts §5, A-CN-2 / 69; ttr-server#281 item 3, the resolver's half) — the resolver's reader of
 * archive v6. Its registry carries the concept tier verbatim; a change to concept facts alone, which the lexicon's
 * `contentHash` does not cover, is still a new snapshot; and a v5 archive is read under the WARN that names both
 * schemas (readers before producers). lex-matcher's half is `CnArchiveLoadTest`.
 */
class CnArchiveRegistryTest :
    StringSpec({
        fun warnsFrom(body: () -> Unit): List<String> {
            val logger =
                (org.slf4j.LoggerFactory.getILoggerFactory() as LoggerContext)
                    .getLogger(LexiconArchiveRegistrySource::class.java.name)
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            return try {
                body()
                appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
        }

        "the registry carries the concept tier: facts, the reverse index, the authored senses — and every concept" {
            val types = CnEstate.entityTypes().associateBy { it.ref }

            types.getValue("cn.DebtorCustomer").let {
                it.objectKind shouldBe "concept"
                it.stereotype shouldBe "phase"
                it.erTarget shouldBe "er.entity.customer"
                it.ofRef shouldBe "cn.Customer"
            }
            types.getValue("cn.Customer").senses shouldBe
                mapOf("customer" to TermSense(default = true), "zákazník" to TermSense(default = true))
            types.getValue("cn.Item").senses shouldBe
                mapOf("položka" to TermSense(priority = 1.0), "zboží" to TermSense(priority = 1.0))
            types.getValue("er.entity.customer").liftsTo shouldContainExactly listOf(Lift("cn.Customer", "direct"))
            // An md element carries its lift alone; a concept nobody wrote a term for is there for its stereotype.
            types.getValue("md.cubelet.channelSales").liftsTo shouldContainExactly listOf(Lift("cn.Sale", "md-hop"))
            types.getValue("cn.Sale").let {
                it.anchors.shouldBeEmpty()
                it.stereotype shouldBe "event"
            }
        }

        "an estate with no conceptual tier projects no concept and no lift" {
            val plain = CnEstate.entityTypes(CnEstate.pack(CnEstate.build(CnEstate.withoutCn())))
            plain
                .filter { it.objectKind == "concept" || it.liftsTo.isNotEmpty() || it.senses.isNotEmpty() }
                .shouldBeEmpty()
        }

        "a change to concept facts alone is a new snapshot: the registry reloads (A-CN-69)" {
            val path = CnEstate.pack()
            val registry = SnapshotRegistry(LexiconArchiveRegistrySource(path), ResolverThresholds.LIVE)
            val before = runBlocking { registry.current() }
            before.entityTypes.single { it.ref == "cn.TopCustomer" }.ofRef shouldBe "cn.Customer"

            // `TopCustomer` is a phase with no terms. Moving it from Customer to Party moves its facts and no row.
            val estate = CnEstate.build()
            val moved =
                CnEstate.build(
                    CnEstate.variant {
                        it.resolve("model/cn/shop.ttrm").replace(
                            "def phase TopCustomer { of: Customer",
                            "def phase TopCustomer { of: Party",
                        )
                    },
                )
            moved.result.lexicon.entries shouldBe estate.result.lexicon.entries
            moved.result.lexicon.contentHash shouldBe estate.result.lexicon.contentHash

            CnEstate.pack(moved, path)
            val after = runBlocking { registry.current() }
            after.snapshotHash shouldNotBe before.snapshotHash
            after.entityTypes.single { it.ref == "cn.TopCustomer" }.ofRef shouldBe "cn.Party"
        }

        "a v5 archive under this v6 reader is read, with the WARN naming both schemas (readers first, A-CN-2)" {
            val plain = CnEstate.build(CnEstate.withoutCn())
            val v5 =
                plain.result.let { r ->
                    r.copy(
                        lexicon =
                            r.lexicon.copy(
                                header = r.lexicon.header.copy(schemaVersion = "ttr-lexicon-compiled/v5"),
                            ),
                    )
                }
            val path =
                Files
                    .createTempDirectory("cn-lex")
                    .resolve("v5.tar.zst")
                    .also { it.writeBytes(LexiconPacker.pack(v5, CnEstate.SNAPSHOT_ID, "v5-producer").bytes) }
            val source = LexiconArchiveRegistrySource(path)

            val warning = warnsFrom { runBlocking { source.fetch() } }.single { "declares schema" in it }
            warning shouldContain "'ttr-lexicon-compiled/v5'"
            warning shouldContain "'ttr-lexicon-compiled/v6'"
            runBlocking { source.fetch() } shouldBe
                runBlocking { LexiconArchiveRegistrySource(CnEstate.pack(plain)).fetch() }
        }
    })
