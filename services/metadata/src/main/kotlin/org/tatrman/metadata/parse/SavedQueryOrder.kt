// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.parse

import org.tatrman.plan.v1.QualifiedName as ProtoQualifiedName
import org.tatrman.plan.v1.schemaCodeToToken
import org.tatrman.translate.snapshot.SnapshotModelHandle
import org.tatrman.translate.v1.Language
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.ttr.metadata.model.ParseStatus
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.Query
import org.tatrman.metadata.grpc.toProto

/**
 * LQ §8 (contracts L-20, L-21) — the order a model's saved queries parse in, when one reads another as
 * `obj.<namespace>.<name>`: each parses in a later [waves] entry than every query it reads, so the reader's parse sees
 * the stored plan it inlines. What can be refused before anything parses is in [refused]: a reference to no saved
 * query (`saved_query_unknown`), to an obj name two saved queries share (`saved_query_ambiguous`), and every query on
 * a reference cycle (`query_reference_cycle`). A query that reads a query which then fails is refused when its own
 * turn comes ([failedDependency], `saved_query_failed`). A model whose bodies read no saved query is one wave.
 */
internal class SavedQueryOrder private constructor(
    val waves: List<List<Query>>,
    val refused: Map<QualifiedName, ParseStatus.ParseFailure>,
    private val reads: Map<QualifiedName, List<Pair<ProtoQualifiedName, Query>>>,
) {
    /** A failure for [query] when a query it reads did not parse (read from [statusOf]), else null. */
    fun failedDependency(
        query: Query,
        statusOf: (QualifiedName) -> ParseStatus?,
    ): ParseStatus.ParseFailure? {
        for ((ref, dep) in reads[query.qname].orEmpty()) {
            val status = statusOf(dep.qname)
            if (status !is ParseStatus.ParseSuccess) {
                val why = (status as? ParseStatus.ParseFailure)?.message ?: "not parsed"
                return ParseStatus.ParseFailure(
                    "$SAVED_QUERY_FAILED: query reads ${ref.dotted()} (${dep.qname.toProto().dotted()}), which failed: $why",
                )
            }
        }
        return null
    }

    companion object {
        const val SAVED_QUERY_UNKNOWN = "saved_query_unknown"
        const val SAVED_QUERY_AMBIGUOUS = "saved_query_ambiguous"
        const val QUERY_REFERENCE_CYCLE = "query_reference_cycle"
        const val SAVED_QUERY_FAILED = "saved_query_failed"

        fun of(
            queries: Collection<Query>,
            languageOf: (Query) -> Language?,
        ): SavedQueryOrder {
            val byObjName = queries.groupBy { key(SnapshotModelHandle.objName(it.qname.toProto())) }
            val refused = LinkedHashMap<QualifiedName, ParseStatus.ParseFailure>()
            val reads = LinkedHashMap<QualifiedName, List<Pair<ProtoQualifiedName, Query>>>()
            for (query in queries) {
                val refs =
                    if (languageOf(query) ==
                        Language.SQL
                    ) {
                        Translator.savedQueryReferences(query.sourceText, Language.SQL)
                    } else {
                        emptyList()
                    }
                val deps = mutableListOf<Pair<ProtoQualifiedName, Query>>()
                for (ref in refs) {
                    val named = byObjName[key(ref)].orEmpty()
                    when {
                        named.isEmpty() ->
                            refused[query.qname] =
                                ParseStatus.ParseFailure("$SAVED_QUERY_UNKNOWN: ${ref.dotted()} names no saved query")
                        named.size > 1 ->
                            refused[query.qname] =
                                ParseStatus.ParseFailure(
                                    "$SAVED_QUERY_AMBIGUOUS: ${ref.dotted()} names more than one saved query: " +
                                        named.map { it.qname.toProto().dotted() }.sorted().joinToString(", "),
                                )
                        else -> deps += ref to named.single()
                    }
                    if (query.qname in refused) break
                }
                if (query.qname !in refused && deps.isNotEmpty()) reads[query.qname] = deps
            }
            for ((member, path) in cycles(reads)) {
                refused[member] =
                    ParseStatus.ParseFailure(
                        "$QUERY_REFERENCE_CYCLE: ${path.joinToString(" → ") { it.toProto().dotted() }}",
                    )
            }
            // Level = 1 + the deepest level of the queries read; a refused one counts as already done (level -1).
            val level = HashMap<QualifiedName, Int>()

            fun levelOf(q: QualifiedName): Int =
                if (q in refused) {
                    -1
                } else {
                    level.getOrPut(q) { (reads[q].orEmpty().maxOfOrNull { (_, dep) -> levelOf(dep.qname) } ?: -1) + 1 }
                }
            val waves =
                queries
                    .filter { it.qname !in refused }
                    .groupBy { levelOf(it.qname) }
                    .toSortedMap()
                    .values
                    .toList()
            return SavedQueryOrder(waves, refused, reads)
        }

        /** Every query on a cycle of [reads], with one cycle through it (it → … → it). */
        private fun cycles(
            reads: Map<QualifiedName, List<Pair<ProtoQualifiedName, Query>>>,
        ): Map<QualifiedName, List<QualifiedName>> {
            val deps = reads.mapValues { (_, d) -> d.map { it.second.qname } }
            val found = LinkedHashMap<QualifiedName, List<QualifiedName>>()
            for (start in deps.keys) {
                if (start in deps[start].orEmpty()) {
                    found[start] = listOf(start, start)
                    continue
                }
                // Shortest path back to `start` (breadth-first over the reads graph).
                val previous = HashMap<QualifiedName, QualifiedName>()
                val queue = ArrayDeque(deps[start].orEmpty().onEach { previous.putIfAbsent(it, start) })
                var closed = false
                while (queue.isNotEmpty() && !closed) {
                    val next = queue.removeFirst()
                    for (dep in deps[next].orEmpty()) {
                        if (dep == start) {
                            previous[start] = next
                            closed = true
                            break
                        }
                        if (previous.putIfAbsent(dep, next) == null) queue.addLast(dep)
                    }
                }
                if (!closed) continue
                val path = ArrayDeque(listOf(start))
                var at = previous.getValue(start)
                while (at != start) {
                    path.addFirst(at)
                    at = previous.getValue(at)
                }
                path.addFirst(start)
                found[start] = path.toList()
            }
            return found
        }

        private fun key(q: ProtoQualifiedName): String = "${q.namespace}.${q.name}".lowercase()

        private fun ProtoQualifiedName.dotted(): String =
            listOf(schemaCodeToToken(schemaCode), namespace, name).filter { it.isNotEmpty() }.joinToString(".")
    }
}
