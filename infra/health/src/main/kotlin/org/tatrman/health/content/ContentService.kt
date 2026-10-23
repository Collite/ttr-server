// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.content

import org.tatrman.health.config.ProbeConfig
import org.tatrman.health.config.StatusConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Reads every configured status endpoint in parallel and keeps the result for [ttl], so the landing's
 * polling (every viewer, every 60 s) costs each service one status read per [ttl] at most. A read that
 * fails becomes a [Content] with `ok = false` and the reason; it never fails the whole answer.
 *
 * An answer in which any read failed is kept for [failureTtl] only: long enough that the viewers
 * waiting on the lock share it, short enough that the next poll asks again. (Kept for the full [ttl],
 * the timeouts of the first read after a restart stood on the page for 30 s while every service was
 * fine.)
 */
class ContentService(
    private val statuses: Map<String, StatusConfig>,
    private val adapters: Map<String, StatusAdapter> = Adapters.byName,
    private val ttl: Duration = Duration.ofSeconds(30),
    private val failureTtl: Duration = Duration.ofSeconds(5),
    private val clock: Clock = Clock.systemUTC(),
    private val kubernetes: KubernetesReader = KubernetesReader(),
) {
    private var client: HttpClient =
        HttpClient(Apache) {
            engine {
                connectTimeout = 5_000
                socketTimeout = 10_000
            }
        }

    fun replaceClient(newClient: HttpClient) {
        client.close()
        client = newClient
    }

    private val lock = Mutex()

    @Volatile
    private var cached: Pair<Instant, StatusAll>? = null

    val configured: Boolean get() = statuses.isNotEmpty()

    suspend fun all(): StatusAll {
        fresh()?.let { return it }
        return lock.withLock {
            fresh() ?: readAll().also { cached = clock.instant() to it }
        }
    }

    private fun fresh(): StatusAll? =
        cached?.let { (readAt, value) ->
            val keep = if (value.technologies.values.any { it.error != null }) failureTtl else ttl
            value.takeIf { Duration.between(readAt, clock.instant()) < keep }
        }

    private suspend fun readAll(): StatusAll =
        coroutineScope {
            val keys = statuses.keys.toList()
            val contents = keys.map { key -> async { read(statuses.getValue(key)) } }.awaitAll()
            val technologies = keys.zip(contents).toMap()
            StatusAll(at = clock.instant().toString(), summary = summarize(technologies), technologies = technologies)
        }

    /** One technology: its status read, plus a sub-row per configured probe (run alongside). */
    suspend fun read(config: StatusConfig): Content =
        coroutineScope {
            val probes = config.probes.map { async { probe(it) } }
            val content = readStatus(config)
            if (probes.isEmpty()) content else content.copy(children = content.children.orEmpty() + probes.awaitAll())
        }

    private suspend fun probe(probe: ProbeConfig): ContentChild {
        val state =
            withTimeoutOrNull(probe.timeout) {
                try {
                    if (probe.url.startsWith(TCP)) {
                        val target = URI(probe.url)
                        withContext(Dispatchers.IO) {
                            Socket().use {
                                it.connect(
                                    InetSocketAddress(target.host, target.port),
                                    probe.timeout.toInt(),
                                )
                            }
                        }
                        "ready"
                    } else if (client.get(probe.url).status.value in 200..299) {
                        "ready"
                    } else {
                        "notReady"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    "down"
                }
            } ?: "down"
        return ContentChild(name = probe.name, tech = probe.tech, state = state)
    }

    private suspend fun readStatus(config: StatusConfig): Content {
        val adapter = adapters[config.adapter] ?: return Content.failed("unknown adapter '${config.adapter}'")
        return try {
            withTimeoutOrNull(config.timeout) {
                // `k8s:/apis/…` reads the Kubernetes API as the pod's ServiceAccount; anything else is HTTP.
                val response =
                    if (config.url.startsWith(K8S)) {
                        if (!kubernetes.available) return@withTimeoutOrNull Content.failed("not running in a cluster")
                        kubernetes.get(config.url.removePrefix(K8S))
                    } else {
                        client.get(config.url) {
                            config.headers.forEach { (name, value) ->
                                header(name, value)
                            }
                        }
                    }
                if (response.status.value !in 200..299) {
                    Content.failed("status answered ${response.status.value}")
                } else {
                    // The tree parser takes a bare word (an HTML page's `<html>`) as a literal; a status
                    // body is an object or an array.
                    when (val body = Json.parseToJsonElement(response.bodyAsText())) {
                        is JsonObject, is JsonArray -> adapter.read(body, config)
                        else -> Content.failed("status is not JSON")
                    }
                }
            } ?: Content.failed("status timed out after ${config.timeout} ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            Content.failed("status is not JSON: ${e.message?.lineSequence()?.firstOrNull()}")
        } catch (e: Exception) {
            Content.failed(e.message ?: e::class.simpleName ?: "status read failed")
        }
    }

    companion object {
        const val K8S = "k8s:"
        const val TCP = "tcp://"

        /**
         * Whether the content-serving services agree on the model commit. Commits are compared by
         * their first seven characters, since services report them at different lengths.
         */
        fun summarize(technologies: Map<String, Content>): ContentSummary {
            val byService =
                technologies
                    .mapNotNull { (key, content) -> content.commit?.takeIf { it.isNotBlank() }?.let { key to it } }
                    .toMap()
            val distinct = byService.values.groupBy { it.take(7).lowercase() }
            val agree = distinct.size <= 1
            val lastRefresh =
                technologies.values
                    .mapNotNull { content -> content.at?.let { runCatching { Instant.parse(it) }.getOrNull() } }
                    .maxOrNull()
            return ContentSummary(
                commit = if (agree) distinct.values.firstOrNull()?.maxByOrNull { it.length } else null,
                agree = agree,
                byService = byService,
                lastRefresh = lastRefresh?.toString(),
            )
        }
    }
}
