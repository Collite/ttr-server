// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.cache

import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.PlanNode
import org.tatrman.translate.v1.Language
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Duration
import java.time.Instant

class CompiledPlanCacheSpec :
    StringSpec({
        fun key(
            modelVersion: String = "v1",
            source: String = "SELECT 1",
            paramSig: String = "noparams",
        ): CacheKey =
            CacheKey(
                modelVersion = modelVersion,
                sourceHash = CompiledPlanCache.sourceHash(source),
                sourceLanguage = Language.SQL,
                paramSignature = paramSig,
            )

        fun cached(fingerprint: String = "fp"): CachedPlan =
            CachedPlan(
                erPlan = PlanNode.getDefaultInstance(),
                predictedSchemaFingerprint = fingerprint,
                cachedAt = Instant.now(),
            )

        "lookup returns null on miss and increments misses" {
            val c = CompiledPlanCache(maxEntries = 100, expireAfterWrite = Duration.ofMinutes(60))
            c.lookup(key()) shouldBe null
            c.stats().misses shouldBe 1
            c.stats().hits shouldBe 0
        }

        "record then lookup returns the cached plan and increments hits" {
            val c = CompiledPlanCache(maxEntries = 100, expireAfterWrite = Duration.ofMinutes(60))
            c.record(key(), cached("hit-fp"))
            val got = c.lookup(key())
            got shouldNotBe null
            got!!.predictedSchemaFingerprint shouldBe "hit-fp"
            c.stats().hits shouldBe 1
        }

        "different sourceHash misses" {
            val c = CompiledPlanCache(maxEntries = 100, expireAfterWrite = Duration.ofMinutes(60))
            c.record(key(source = "SELECT 1"), cached())
            c.lookup(key(source = "SELECT 2")) shouldBe null
        }

        "different paramSignature misses" {
            val c = CompiledPlanCache(maxEntries = 100, expireAfterWrite = Duration.ofMinutes(60))
            c.record(key(paramSig = "sig-a"), cached())
            c.lookup(key(paramSig = "sig-b")) shouldBe null
        }

        "model_version change invalidates the entire cache" {
            val c = CompiledPlanCache(maxEntries = 100, expireAfterWrite = Duration.ofMinutes(60))
            c.record(key(modelVersion = "v1", source = "Q1"), cached())
            c.record(key(modelVersion = "v1", source = "Q2"), cached())
            c.stats().entries shouldBe 2
            c.lookup(key(modelVersion = "v2", source = "Q1")) shouldBe null
            c.stats().invalidations shouldBe 2
            c.stats().currentModelVersion shouldBe "v2"
            c.stats().entries shouldBe 0
        }

        "clear() drops every entry and counts them as invalidations (operator refresh)" {
            val c = CompiledPlanCache(maxEntries = 100, expireAfterWrite = Duration.ofMinutes(60))
            c.record(key(source = "Q1"), cached())
            c.record(key(source = "Q2"), cached())
            c.stats().entries shouldBe 2
            c.clear() shouldBe 2
            c.stats().entries shouldBe 0
            c.stats().invalidations shouldBe 2
            // Model version is left untouched, so a same-version lookup just misses an empty cache
            // (no extra invalidation from the version check).
            c.lookup(key(source = "Q1")) shouldBe null
            c.stats().invalidations shouldBe 2
        }

        "stats.maxEntries reports the configured cap" {
            // We don't assert eviction timing — Caffeine's W-TinyLFU
            // policy is async and probabilistic on tiny caches. The
            // `max-entries` cap is honoured by Caffeine; this test just
            // ensures we expose it through stats.
            val c = CompiledPlanCache(maxEntries = 7, expireAfterWrite = Duration.ofMinutes(60))
            c.stats().maxEntries shouldBe 7
        }

        "sourceHash is stable lowercase hex" {
            val h = CompiledPlanCache.sourceHash("SELECT 1")
            h.length shouldBe 64
            h shouldBe h.lowercase()
            CompiledPlanCache.sourceHash("SELECT 1") shouldBe h
        }

        "paramSignature is order-independent" {
            val a =
                CompiledPlanCache.paramSignature(
                    listOf(
                        ParameterBinding
                            .newBuilder()
                            .setName("x")
                            .setType("int")
                            .build(),
                        ParameterBinding
                            .newBuilder()
                            .setName("y")
                            .setType("text")
                            .build(),
                    ),
                )
            val b =
                CompiledPlanCache.paramSignature(
                    listOf(
                        ParameterBinding
                            .newBuilder()
                            .setName("y")
                            .setType("text")
                            .build(),
                        ParameterBinding
                            .newBuilder()
                            .setName("x")
                            .setType("int")
                            .build(),
                    ),
                )
            a shouldBe b
        }

        "paramSignature differs when type changes for the same name" {
            val a =
                CompiledPlanCache.paramSignature(
                    listOf(
                        ParameterBinding
                            .newBuilder()
                            .setName("x")
                            .setType("int")
                            .build(),
                    ),
                )
            val b =
                CompiledPlanCache.paramSignature(
                    listOf(
                        ParameterBinding
                            .newBuilder()
                            .setName("x")
                            .setType("text")
                            .build(),
                    ),
                )
            a shouldNotBe b
        }
    })
