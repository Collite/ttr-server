// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** UD-P3 — a member vocabulary's named-entity profile; the wire twin is fuzzy.proto `EntityProfile`. */
data class EntityProfile(
    val members: Int,
    val entities: List<EntityCount>,
    val nerModel: String,
)

/** Values of one vocabulary carrying at least one entity of this NER kind (label + fine code). */
data class EntityCount(
    val label: String,
    val code: String,
    val members: Int,
)

/**
 * UD-P3 — runs the NER over every value of every member vocabulary when the vocabulary loads, and
 * records which kinds of names it finds there.
 *
 * The resolver asks for it because the NER claims a place or a person in the QUESTION before any
 * vocabulary is asked (`mělo Brno DC nulové zásoby`: „Brno DC“ is typed a place, so the warehouse
 * named that is never looked up). Which vocabularies could hold such a name is a fact about the
 * DATA, and the same NER can measure it here, once per load: a vocabulary none of whose values the
 * NER calls a place (`Nedorazilo včas`, `Parts missing`) is never asked about a place, and one whose
 * values it does (`Brno DC`) is. The resolver reads the profile from `GetStatus`.
 *
 * **Packing.** Values go to nlp many at a time, one per line, each inside a short carrier phrase in
 * the data's language (`v Brno DC.` / `in Dallas DC.`), and each entity is mapped back to the value
 * whose characters it overlaps — an entity that runs across two values is dropped. The carrier gives
 * the NER a sentence to read a bare label in, which
 * is how it meets the name in a question. It leans towards places, and that is the cheap direction to
 * err in: an over-counted vocabulary costs the resolver one more exact-match lookup, while a missed
 * one keeps a real name unreachable.
 *
 * **Cache.** Per category, keyed by the category's content version (which moves with its rows and its
 * read plan) and by the NER's identity from nlp's capability matrix. An unchanged vocabulary under
 * the same NER is never re-profiled while the matcher runs, so a refresh costs one status call. When
 * the identity cannot be read, an entry whose version still matches is kept. The cache lives in
 * memory, so a restart reads every vocabulary again — smallest first, each reported as it is measured
 * ([profile]). Keeping it across restarts is ttr-server#189.
 *
 * **Degradable, like [NlpLemmatizer].** A failed or unsupported nlp call leaves the category without a
 * profile and logs a WARN. A category with no profile is never offered a name by the resolver, so a
 * matcher with no profile behaves exactly as before UD-P3.
 */
class EntityProfiler(
    private val client: NlpBatchClient,
    language: String,
    /** The most characters packed into one nlp call. At least one value goes into each call. */
    private val chunkChars: Int = DEFAULT_CHUNK_CHARS,
    /** Vocabularies with more distinct values than this are not profiled; 0 = no limit (⚑UD-13 (a)). */
    private val maxMembers: Int = 0,
) {
    private val logger = LoggerFactory.getLogger(EntityProfiler::class.java)

    /** The bare language subtag: the routing table and the carriers are keyed on it (`cs-CZ` → `cs`). */
    private val language: String = language.substringBefore('-').lowercase()

    private data class Cached(
        val version: String,
        val identity: String?,
        val profile: EntityProfile,
    )

    private val cache = ConcurrentHashMap<String, Cached>()

    /** A vocabulary the cache could not answer for: it goes to the NER. */
    private class Work(
        val category: String,
        val version: String,
        val values: List<String>,
        val cached: Cached?,
    )

    /**
     * The profiles of [vocabularies] — category → (content version, values). Returns only the
     * categories that have one. Categories not passed are dropped from the cache, so a vocabulary that
     * left the estate does not keep a profile.
     *
     * **Smallest first, reported as it goes.** The cache answers first. Then the NER reads the rest in
     * ascending order of size, and [onProgress] gets everything measured so far: once after the cache
     * hits, then after each vocabulary. A handful of warehouse names takes the NER a second and eighteen
     * thousand product names take it minutes, so a caller that publishes on progress has the small
     * vocabularies named long before the large one is done, which matters most after a restart, when the
     * cache is empty.
     */
    suspend fun profile(
        vocabularies: Map<String, Pair<String, List<String>>>,
        onProgress: (Map<String, EntityProfile>) -> Unit = {},
    ): Map<String, EntityProfile> {
        cache.keys.retainAll(vocabularies.keys)
        val identity =
            try {
                client.nerIdentity(language)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("nlp status unreadable ({}) — keeping entity profiles whose data is unchanged", e.message)
                null
            }
        val out = LinkedHashMap<String, EntityProfile>()
        val todo = mutableListOf<Work>()
        for ((category, entry) in vocabularies) {
            val (version, values) = entry
            val cached = cache[category]
            if (cached != null && cached.version == version && (identity == null || cached.identity == identity)) {
                out[category] = cached.profile
            } else {
                todo += Work(category, version, distinctValues(values), cached)
            }
        }
        if (todo.isEmpty()) return out
        onProgress(LinkedHashMap(out))
        for (work in todo.sortedBy { it.values.size }) {
            measure(work, identity)?.let { out[work.category] = it }
            onProgress(LinkedHashMap(out))
        }
        return out
    }

    /** The profile [work] ends the pass with — measured, kept from the cache, or none. */
    private suspend fun measure(
        work: Work,
        identity: String?,
    ): EntityProfile? {
        val category = work.category
        if (maxMembers in 1 until work.values.size) {
            logger.info(
                "entity profile: {} skipped — {} values over the limit of {}",
                category,
                work.values.size,
                maxMembers,
            )
            cache.remove(category)
            return null
        }
        val profile = profileOf(category, work.values)
        val cached = work.cached
        return when {
            profile != null -> {
                cache[category] = Cached(work.version, identity, profile)
                logger.info(
                    "entity profile: {} {} values, {} model={}",
                    category,
                    profile.members,
                    profile.entities.joinToString(
                        " ",
                        "[",
                        "]",
                    ) { "${it.label}/${it.code.ifBlank { "-" }}:${it.members}" },
                    profile.nerModel.ifBlank { "?" },
                )
                profile
            }
            // Same rows, the NER merely unreadable or changed and now failing: what was measured on
            // these very values still describes them.
            cached != null && cached.version == work.version -> cached.profile
            else -> {
                cache.remove(category)
                null
            }
        }
    }

    /** One vocabulary's profile, or null when nlp could not or would not answer. */
    private suspend fun profileOf(
        category: String,
        values: List<String>,
    ): EntityProfile? {
        val kindsByValue = HashMap<Int, MutableSet<Pair<String, String>>>()
        var model = ""
        for (chunk in pack(values)) {
            val result =
                try {
                    client.entities(chunk.text, language) ?: return null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("entity profile: {} not profiled — nlp NER failed ({})", category, e.message)
                    return null
                }
            if (model.isBlank()) model = result.model
            for (entity in result.entities) {
                // An entity that runs across a value boundary is no value's: NameTag reads a packed batch
                // as running text and chains look-alike lines into one name (`… Lampa MX-32Z.\nv … Lampa
                // MX-4EK`, measured on hartland). A blank line between values does not stop it, so the
                // entity is dropped rather than credited to every value it touches.
                val values = chunk.valuesOverlapping(entity.start, entity.end)
                if (values.size != 1) continue
                kindsByValue.getOrPut(values.single()) { HashSet() } += entity.label to entity.code
            }
        }
        val counts =
            kindsByValue.values
                .flatten()
                .groupingBy { it }
                .eachCount()
                .map { (kind, members) -> EntityCount(kind.first, kind.second, members) }
                .sortedWith(compareBy({ it.label }, { it.code }))
        return EntityProfile(values.size, counts, model)
    }

    /** A packed nlp text and, for each value in it, the value's index and its `[start, end)` in the text. */
    internal class Chunk(
        val text: String,
        private val firstValue: Int,
        private val starts: IntArray,
        private val ends: IntArray,
    ) {
        /** The indices (into the profiled value list) of the values whose characters `[start, end)` overlaps. */
        fun valuesOverlapping(
            start: Int,
            end: Int,
        ): List<Int> = starts.indices.filter { starts[it] < end && start < ends[it] }.map { firstValue + it }
    }

    internal fun pack(values: List<String>): List<Chunk> {
        val (prefix, suffix) = CARRIERS[language] ?: ("" to ".")
        val chunks = mutableListOf<Chunk>()
        var i = 0
        while (i < values.size) {
            val first = i
            val text = StringBuilder()
            val starts = mutableListOf<Int>()
            val ends = mutableListOf<Int>()
            while (i < values.size) {
                val line = prefix.length + values[i].length + suffix.length + 1
                if (starts.isNotEmpty() && text.length + line > chunkChars) break
                text.append(prefix)
                starts += text.length
                text.append(values[i])
                ends += text.length
                text.append(suffix).append('\n')
                i++
            }
            chunks += Chunk(text.toString(), first, starts.toIntArray(), ends.toIntArray())
        }
        return chunks
    }

    companion object {
        const val DEFAULT_CHUNK_CHARS: Int = 8_000

        /** The carrier phrase a value is read in, per data language: a preposition that takes a place. */
        private val CARRIERS: Map<String, Pair<String, String>> =
            mapOf(
                "cs" to ("v " to "."),
                "en" to ("in " to "."),
            )

        private val WHITESPACE = Regex("\\s+")

        /**
         * One line per value: trimmed (a `char(n)` value's padding is not part of the name), inner
         * whitespace collapsed (a line break inside a value would split it), blanks and repeats dropped.
         */
        internal fun distinctValues(values: List<String>): List<String> =
            values
                .map { it.trim().replace(WHITESPACE, " ") }
                .filter { it.isNotEmpty() }
                .distinct()
    }
}
