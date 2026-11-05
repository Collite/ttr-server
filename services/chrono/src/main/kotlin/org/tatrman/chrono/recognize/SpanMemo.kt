// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

/**
 * A small cache of a pure function of the folded span. Every rule asks the same tables of the same text — the
 * numbered periods, the trailing and part-of phrases, the relative words — so one read asks each of them many times,
 * and `Scan` reads every window of a question (S0.3). Per thread, so nothing is shared or locked; bounded, least
 * recently used first out.
 */
internal class SpanMemo<T>(
    private val capacity: Int = 512,
    private val compute: (String) -> T,
) {
    private val cache =
        ThreadLocal.withInitial {
            object : LinkedHashMap<String, T>(capacity, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?): Boolean = size > capacity
            }
        }

    operator fun invoke(n: String): T {
        val map = cache.get()
        map[n]?.let { return it }
        return compute(n).also { map[n] = it }
    }
}
