// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.apply.PreviewCalendar
import org.tatrman.automation.bundle.*
import org.tatrman.automation.ledger.Ledger
import org.tatrman.automation.ledger.RunOutput
import org.tatrman.automation.protocol.ProtocolRecorder
import org.tatrman.automation.staging.*
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

class CalendarSnapshotSpec :
    StringSpec({
        val bundle = BundleFixture.load(CalendarFixture.copyTo(BundleFixture.copy()))
        val recipe = bundle.recipes.getValue("release_order")
        val plan = PreviewCalendar.plan(bundle, "demo", recipe.recipe, bundle.actions)!!
        val table = Table(plan.columns, listOf(listOf(LocalDate.of(2026, 10, 28), true)))

        "concurrent calendar consumers share the pinned governed read and journalled Arrow snapshot" {
            coroutineScope {
                val count = AtomicInteger()
                val published = mutableListOf<RunOutput>()
                val ledger = mockk<Ledger>()
                every { ledger.putOutput(any(), any()) } answers {
                    published += firstArg<RunOutput>()
                    Unit
                }
                val context = runnerContext(recipe).copy(calendar = plan)
                val door =
                    Door { read ->
                        count.incrementAndGet()
                        read.world shouldBe context.world
                        read.identity shouldBe context.identity
                        read.modelFp shouldBe context.modelFp
                        read.storage shouldBe "erp_db"
                        read.bindings shouldBe emptyMap()
                        read.objectsRead shouldBe recipe.recipe.objectsRead.toSet()
                        delay(20)
                        ReadResult(table)
                    }
                PartRunner(
                    context,
                    door,
                    Staging(
                        LocalStagingStore(Files.createTempDirectory("calendar-snapshot")),
                        ProtocolRecorder(ledger),
                    ),
                    this,
                ).use { runner ->
                    val results =
                        listOf(
                            async { runner.calendarSnapshot() },
                            async { runner.calendarSnapshot() },
                        ).awaitAll()
                    results.map { it!!.table } shouldBe listOf(table, table)
                    runner.calendarSnapshot()!!.table shouldBe table
                    count.get() shouldBe 1
                    published.size shouldBe 1
                    published.single().island shouldBe "_au_calendar"
                }
            }
        }

        "an authored calendar island and the private snapshot keep separate immutable outputs" {
            coroutineScope {
                val authored =
                    ManifestIsland(
                        "_au_calendar",
                        outputs =
                            listOf(
                                ManifestOutput("rows", "authored.sql", storage = "erp_db", columns = table.columns),
                            ),
                    )
                val loaded =
                    recipe.copy(
                        manifest =
                            recipe.manifest.copy(
                                islands = listOf(authored),
                                waves = listOf(listOf(authored.name)),
                            ),
                        statements = recipe.statements + ("authored.sql" to "authored"),
                    )
                val published = mutableListOf<RunOutput>()
                val ledger = mockk<Ledger>()
                every { ledger.putOutput(any(), any()) } answers {
                    published += firstArg<RunOutput>()
                    Unit
                }
                val other = table.copy(rows = listOf(listOf(LocalDate.of(2026, 10, 29), false)))
                val door = Door { read -> ReadResult(if (read.sql == "authored") other else table) }
                PartRunner(
                    runnerContext(loaded).copy(calendar = plan),
                    door,
                    Staging(LocalStagingStore(Files.createTempDirectory("calendar-island")), ProtocolRecorder(ledger)),
                    this,
                ).use { runner ->
                    runner.calendarSnapshot()!!.table shouldBe table
                    runner.island("_au_calendar").single().table shouldBe other
                    runner.calendarSnapshot()!!.table shouldBe table
                    published.map { it.island }.toSet().size shouldBe 2
                    published.any { it.island == "_au_calendar" } shouldBe true
                    published.size shouldBe 2
                }
            }
        }

        "calendar caps and narrowed views refuse once before staging even with restricted-view opt in" {
            supervisorScope {
                for ((rows, warnings, expected) in listOf(
                    Triple(table.rows + table.rows, emptySet<String>(), ErrorCode.CANDIDATES_TRUNCATED),
                    Triple(table.rows, setOf("top_n_applied"), ErrorCode.CANDIDATES_TRUNCATED),
                    Triple(table.rows, setOf("security_predicate_applied"), ErrorCode.RESTRICTED_VIEW),
                )) {
                    val count = AtomicInteger()
                    val context =
                        runnerContext(
                            recipe,
                        ).copy(calendar = plan, allowRestrictedView = true, readCeiling = 2)
                    val door =
                        Door {
                            count.incrementAndGet()
                            ReadResult(table.copy(rows = rows), warnings)
                        }
                    val ledger = mockk<Ledger>() // Any attempt to stage/journal a refused snapshot fails this test.
                    PartRunner(
                        context,
                        door,
                        Staging(
                            LocalStagingStore(Files.createTempDirectory("calendar-refused")),
                            ProtocolRecorder(ledger),
                        ),
                        this,
                    ).use { runner ->
                        repeat(2) {
                            shouldThrow<AutomationException> { runner.calendarSnapshot() }.code shouldBe
                                expected
                        }
                        count.get() shouldBe 1
                    }
                }
            }
        }
    })
