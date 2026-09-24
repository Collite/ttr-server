// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * RV-P7.3 T1/T3 — **the overlay archive: the one contract that crosses the repo boundary.**
 *
 * The LEARNED overlay is written by kantheon's Golem (which owns `rv_overlay_entries`) and read
 * here. Neither side can call the other — lex-matcher is the Apache-2.0 open runtime and must not
 * require the commercial constellation to serve a layer — so what they share is this document,
 * sealed inside a `ttr-snapshot` archive and handed over as a file.
 *
 * **The twin of this file is `OverlayArchive.kt` in `kantheon/agents/golem`.** They are duplicated
 * on purpose: publishing a library to share four field names would couple a release line of the
 * open runtime to a release line of the constellation, which is a much worse cost than a doc
 * comment. They are held together by a **golden fixture checked into both repos** — the exact
 * bytes the writer produces and the reader parses — so a change on either side breaks a test on
 * both, which is the only pin that actually holds across a boundary no compiler crosses.
 *
 * The archive is a normal snapshot: `SnapshotWriter` is deterministic (same content ⇒ same bytes
 * ⇒ same id), which is what makes the content id usable as the refresh clock.
 */
object OverlayArchive {
    /** `snapshot.json`'s `kind`. Checked on read: a model snapshot in this slot must not look empty. */
    const val KIND: String = "overlay"

    /** The single document, relative to `docs/`. */
    const val OVERLAY: String = "overlay.json"

    /** The document's own schema, so a future shape can be recognised rather than mis-parsed. */
    const val SCHEMA: String = "rv-overlay/v1"

    /**
     * Lenient on unknown keys, deliberately: a Golem from a newer release may add a field, and an
     * overlay that fails to parse costs the estate its entire learned vocabulary. Unknown *values*
     * are a different matter — see [OverlayDoc.entries] — but an unknown field is not a reason to
     * refuse an estate its learning.
     */
    val JSON: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
            prettyPrintIndent = "  "
        }
}

/**
 * One estate's overlay as exported.
 *
 * [version] is the STORE's version (RV-P7.2: a read is a snapshot that names its version), and it
 * is what rides the RV-39 tuple — not the archive's content id, which answers the different
 * question "is this a different file?". Same split as `LexiconArchiveSource.hash()` vs
 * `artifactHash()`, for the same reason.
 */
@Serializable
data class OverlayDoc(
    val schema: String = OverlayArchive.SCHEMA,
    @SerialName("estate_id") val estateId: String,
    val version: Long,
    /**
     * **Every** entry the store holds, including INVALIDATED and PROPOSED ones — not just the
     * servable set.
     *
     * T2(e): the matcher re-checks status rather than trusting presence. An exporter that filtered
     * would make "not served" indistinguishable from "the transport lagged", and the whole point of
     * RV-20's invalidation is that a retired entry stops serving even when something upstream is
     * stale.
     */
    val entries: List<OverlayDocEntry> = emptyList(),
)

/**
 * One entry. Mirrors contracts §5's `OverlayEntry` plus the two fields P7.2's addendum added
 * (`status` as a lifecycle, `conflicted` beside it).
 *
 * [targetClass] is **stated by the producer, never derived here** — the RV-35/38/42 rule that maps
 * a ref's prefix to its class belongs on the side that owns the ref, and `LexiconArchiveSource`
 * already refuses to re-derive it for exactly this reason ("a second, quietly divergent rule").
 * An entry whose class this build does not recognise is dropped by [OverlayArchiveSource]: a row
 * with no class is excluded by the class-scoped filter anyway, so serving it would be serving a
 * candidate that can only ever appear in unscoped lookups.
 */
@Serializable
data class OverlayDocEntry(
    val term: String,
    val lang: String,
    @SerialName("target_ref") val targetRef: String,
    /** `POSITIVE` | `NEGATIVE`. */
    val polarity: String,
    /** `PROPOSED` | `ACTIVE` | `PROMOTION_CANDIDATE` | `INVALIDATED`. */
    val status: String,
    /** `MODEL_OBJECT` | `MEMBER` | `OPERATOR` | `GROUNDING_TRIGGER` | `STRING_PREDICATE`. */
    @SerialName("target_class") val targetClass: String,
    /** How many distinct users have confirmed it. Informative here; the promotion queue reads it. */
    @SerialName("distinct_users") val distinctUsers: Int = 0,
    /** Another servable entry in this (term, lang) group points elsewhere. Informative here. */
    val conflicted: Boolean = false,
)
