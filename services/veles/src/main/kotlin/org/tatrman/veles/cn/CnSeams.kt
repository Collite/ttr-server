// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.cn

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.VocabularyVersion
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.SourceSnapshot
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * The AGEL K seam (T6): every audit row, told to whoever listens — the steward's queue refreshes on it. No UI here;
 * [NONE] unless `metadata.cn.notify-url` is set.
 */
fun interface CandidateNotifier {
    fun notify(
        e: CandidateEvent,
        c: StoredCandidate,
    )

    companion object {
        val NONE = CandidateNotifier { _, _ -> }
    }
}

/**
 * `POST <url>` `{"event", "candidate_id", "state", "producer", "kind"}` — fire and forget: a listener that is down costs
 * its notification (logged), never the call that caused it. The candidate itself is not in it; the listener reads it.
 */
class WebhookNotifier(
    private val url: URI,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : CandidateNotifier {
    override fun notify(
        e: CandidateEvent,
        c: StoredCandidate,
    ) {
        val body =
            buildJsonObject {
                put("event", e.action)
                put("candidate_id", c.id)
                put("state", c.state.word)
                put("producer", c.candidate.provenance.producer)
                put("kind", c.kind)
            }
        val request =
            HttpRequest
                .newBuilder(url)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete { r, err ->
            if (err != null || r.statusCode() !in 200..299) {
                log.warn("cn notify: {} for {} not delivered ({})", e.action, c.id, err?.message ?: r.statusCode())
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(WebhookNotifier::class.java)
    }
}

/**
 * T5 (A-CN-67, A-CN-94) — the registered vocabulary versions as a model source: its snapshot carries only
 * `cn.vocabularyVersions`, which the reconciler concatenates into the published `CnSchema`, so the model Veles serves
 * states every registration and nothing in the model text has to. Its version is a hash of the registrations, so a
 * refresh swaps only when one was added. A store that does not answer keeps the last registrations it read (a refresh
 * never drops them) and says so in the log.
 */
class VocabularySource(
    private val store: CandidateStore,
) : ModelSource {
    @Volatile private var last: List<VocabularyVersion> = emptyList()

    override fun load(): SourceSnapshot {
        val versions =
            try {
                store.vocabularyVersions().also { last = it }
            } catch (e: Exception) {
                log.warn(
                    "cn vocabularies: the store did not answer, keeping {} registration(s): {}",
                    last.size,
                    e.message,
                )
                last
            }
        return SourceSnapshot(
            sourceId = SOURCE_ID,
            priority = 0,
            version = versionOf(versions),
            cn = CnSchema(vocabularyVersions = versions),
        )
    }

    companion object {
        const val SOURCE_ID = "cn-vocabularies"
        private val log = LoggerFactory.getLogger(VocabularySource::class.java)

        private fun versionOf(versions: List<VocabularyVersion>): String {
            val text = versions.joinToString("\n") { "${it.vocabulary}\t${it.version}\t${it.bundle.orEmpty()}" }
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return "vocabularies-" + digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
