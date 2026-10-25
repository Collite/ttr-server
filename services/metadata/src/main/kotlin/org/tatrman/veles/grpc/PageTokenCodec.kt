// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import java.util.Base64

/**
 * DF-M08 / Phase 07 B5 — AIP-158-style page tokens that pivot on a stable sort key instead of a
 * raw offset, so pagination stays correct when rows are inserted or deleted between calls.
 *
 * Wire format (v2): `"v2:" + base64(after_key)`. The `after_key` is the sort key the caller saw
 * last (the dotted qname for `List*` RPCs that order by it); the next page returns rows whose
 * key is strictly greater than `after_key`. Empty token starts at the beginning.
 *
 * **Backward compatibility.** The pre-Phase-07 codec emitted a Base64-encoded integer offset
 * ("12" → `"MTI="`). Old clients passing such a token aren't asked to restart; the codec detects
 * the legacy shape and returns it as [PageStart.LegacyOffset], and the call site falls back to
 * offset-based slicing. New responses always emit v2 tokens — the migration is one round-trip:
 * old client gets a v2 token back on the next page, and from then on operates correctly.
 *
 * Malformed / unrecognised tokens → [PageStart.Start] (silent restart) rather than an error;
 * AIP-158 §6 leaves the choice to the implementation but a soft restart is the friendlier UX.
 */
internal object PageTokenCodec {
    private const val V2_PREFIX: String = "v2:"

    sealed interface PageStart {
        data object Start : PageStart

        data class After(
            val key: String,
        ) : PageStart

        data class LegacyOffset(
            val offset: Int,
        ) : PageStart
    }

    fun decode(token: String): PageStart {
        if (token.isEmpty()) return PageStart.Start
        if (token.startsWith(V2_PREFIX)) {
            return runCatching {
                val key = String(Base64.getDecoder().decode(token.removePrefix(V2_PREFIX)))
                PageStart.After(key)
            }.getOrElse { PageStart.Start }
        }
        // Legacy: Base64(stringified-int).
        return runCatching {
            PageStart.LegacyOffset(String(Base64.getDecoder().decode(token)).toInt())
        }.getOrElse { PageStart.Start }
    }

    fun encodeAfter(key: String): String =
        V2_PREFIX +
            Base64
                .getEncoder()
                .encodeToString(key.toByteArray())

    /**
     * Paginate [items] by sort key. Caller supplies [keyOf] (deterministic, totally ordered).
     * Returns the page slice and the encoded next-page token (empty when no more rows).
     */
    fun <T> paginate(
        items: List<T>,
        token: String,
        pageSize: Int,
        keyOf: (T) -> String,
    ): Pair<List<T>, String> {
        if (items.isEmpty()) return emptyList<T>() to ""
        val start: Int =
            when (val decoded = decode(token)) {
                PageStart.Start -> 0
                is PageStart.After ->
                    // First index whose key is strictly greater than decoded.key.
                    items.indexOfFirst { keyOf(it) > decoded.key }.let { if (it < 0) items.size else it }
                is PageStart.LegacyOffset -> decoded.offset.coerceAtLeast(0)
            }
        val slice = items.drop(start).take(pageSize)
        val nextToken =
            if (start + slice.size < items.size && slice.isNotEmpty()) {
                encodeAfter(keyOf(slice.last()))
            } else {
                ""
            }
        return slice to nextToken
    }
}
