// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.content

import kotlinx.serialization.Serializable

/**
 * What a service says about its content (not whether it runs: that is the health check), in a small
 * vocabulary the landing page renders without knowing the service. Only what the service reports is
 * here: a figure it does not expose is absent, never estimated.
 */
@Serializable
data class Content(
    /** The status read worked and the service reports nothing wrong with its content. */
    val ok: Boolean,
    /** When the content was last loaded or refreshed (ISO-8601), if the service says. */
    val at: String? = null,
    /** The model commit the service serves, if it says. */
    val commit: String? = null,
    /** Label/value pairs for the content line, in order. */
    val summary: List<ContentPair> = emptyList(),
    /** One sentence the page shows as it is (e.g. why the content is not there yet). */
    val note: String? = null,
    /** Per-source rows (metadata: one per model source). */
    val sources: List<ContentSource>? = null,
    /** The largest parts, biggest first. */
    val top: List<ContentBar>? = null,
    /** Parts with their own state (NLP: one per engine). */
    val children: List<ContentChild>? = null,
    /** Why the status could not be read; `ok` is then false. */
    val error: String? = null,
) {
    companion object {
        fun failed(error: String) = Content(ok = false, error = error)
    }
}

/**
 * One figure. [k] is a stable key the page translates (`entries`, `loaded`, …); [kind] says how to
 * format [v]: `count` (a number, grouped by locale), `time` (ISO-8601, shown as local time), `commit`
 * (shortened), `text` (as it is).
 */
@Serializable
data class ContentPair(
    val k: String,
    val v: String,
    val kind: String = "text",
)

@Serializable
data class ContentSource(
    val name: String,
    /** null: the source is not active (shown grey, with [note] saying why). */
    val ok: Boolean?,
    val summary: List<ContentPair> = emptyList(),
    val note: String? = null,
)

@Serializable
data class ContentBar(
    val label: String,
    val value: Long,
)

@Serializable
data class ContentChild(
    val name: String,
    val tech: String? = null,
    /** `ready`, `notReady`, `down` — the landing's state names. */
    val state: String,
)

/** `GET /status/all`: every technology with a `status` block, and whether they serve one commit. */
@Serializable
data class StatusAll(
    val at: String,
    val summary: ContentSummary,
    val technologies: Map<String, Content>,
)

@Serializable
data class ContentSummary(
    /** The commit every content-serving service reports, when they agree. */
    val commit: String? = null,
    /** False when the services report different commits. */
    val agree: Boolean,
    /** Technology → the commit it reports (only those that report one). */
    val byService: Map<String, String> = emptyMap(),
    /**
     * The technologies configured to name the model commit that named none (a boot fixture, no model loaded,
     * an unreadable status): while any is listed the services do not agree.
     */
    val missing: List<String> = emptyList(),
    /** The newest load among the services that name a commit (a secret's sync is not a model refresh). */
    val lastRefresh: String? = null,
)
