// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.refresh

import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.refresh.MetadataRefresher
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.SourceSnapshot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class RefreshSchedulerSpec :
    StringSpec({

        fun snapshot(
            id: String,
            version: String,
            tableName: String,
        ): SourceSnapshot =
            SourceSnapshot(
                sourceId = id,
                priority = 100,
                version = version,
                tables =
                    mapOf(
                        QualifiedName(
                            schemaCode = org.tatrman.ttr.metadata.model.SchemaCode.DB,
                            namespace = "dbo",
                            name = tableName,
                        ) to
                            DbTable(
                                internalId = "$id.$tableName",
                                qname =
                                    QualifiedName(
                                        schemaCode = org.tatrman.ttr.metadata.model.SchemaCode.DB,
                                        namespace = "dbo",
                                        name = tableName,
                                    ),
                            ),
                    ),
            )

        "scheduler ticks pick up source changes" {
            val calls = AtomicInteger(0)
            val src =
                object : ModelSource {
                    override fun load(): SourceSnapshot {
                        // Each successive call produces a distinct version → triggers a swap.
                        val n = calls.incrementAndGet()
                        return snapshot("s1", "v$n", "tableV$n")
                    }
                }
            val registry = MetadataRegistry()
            val refresher =
                MetadataRefresher(
                    sources = listOf(src),
                    sourceIds = listOf("s1"),
                    reconciler = ModelReconciler(ModelDescriptor(id = "t", name = "t")),
                    registry = registry,
                )

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val scheduler = RefreshScheduler(refresher, Duration.ofMillis(80), scope)
            val job = scheduler.start()

            // Wait long enough for ~3 ticks to fire (each at 80ms interval).
            runBlocking { delay(350) }
            job.cancel()

            // Calls increment with each scheduled tick — at least 2 should have happened.
            (calls.get() >= 2) shouldBe true
            // Latest model_version is one of v2/v3/... (i.e. a recent tick swapped).
            val v =
                registry
                    .read()
                    ?.model
                    ?.version
                    ?.value ?: ""
            (v.startsWith("v") || v.contains("v")) shouldBe true
        }

        "scheduler keeps running after a failing tick" {
            val n = AtomicInteger(0)
            val src =
                object : ModelSource {
                    override fun load(): SourceSnapshot {
                        val i = n.incrementAndGet()
                        if (i == 1) throw RuntimeException("transient")
                        return snapshot("s1", "v$i", "tableV$i")
                    }
                }
            val registry = MetadataRegistry()
            val refresher =
                MetadataRefresher(
                    sources = listOf(src),
                    sourceIds = listOf("s1"),
                    reconciler = ModelReconciler(ModelDescriptor(id = "t", name = "t")),
                    registry = registry,
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val job = RefreshScheduler(refresher, Duration.ofMillis(60), scope).start()
            runBlocking { delay(250) }
            job.cancel()
            // n=1 failed; ticks 2..N succeeded → registry has *some* snapshot.
            (registry.read() != null) shouldBe true
        }
    })
