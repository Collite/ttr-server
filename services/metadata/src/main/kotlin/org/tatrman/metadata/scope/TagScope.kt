// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.scope

import org.tatrman.metadata.grpc.toDomain
import org.tatrman.plan.v1.PlanNode
import org.tatrman.translator.joiner.JoinerPlanWalker
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelObject
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.Query
import org.tatrman.ttr.metadata.model.ParseStatus
import org.tatrman.ttr.metadata.model.Er2DbEntityMapping
import org.tatrman.ttr.metadata.model.MappingTarget
import org.tatrman.ttr.metadata.model.Relation
import org.tatrman.ttr.metadata.model.DbForeignKey
import org.tatrman.ttr.metadata.model.DbColumn
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttr.metadata.model.DbView
import org.tatrman.ttr.metadata.source.LoadWarning

/** Default tag closure, implemented from the four-step specification; no agent registry or policy. */
object TagScope {
    data class Warning(
        val code: String,
        val message: String,
        val sourceFile: String,
    )

    data class Result(
        val objects: List<ModelObject>,
        val warnings: List<Warning>,
    )

    fun normalize(tags: List<String>): Set<String> {
        require(tags.none { it.isBlank() }) { "tags must contain nonblank values" }
        return tags.map { it.trim() }.toSortedSet()
    }

    fun resolve(
        model: Model,
        tags: Set<String>,
        parseStatus: (Query) -> ParseStatus = { it.parseStatus },
        checkQueries: Boolean = true,
    ): Result =
        resolve(
            model.objectByQname(),
            model.mappings.filterIsInstance<Er2DbEntityMapping>().groupBy {
                it.entity
            },
            tags,
            parseStatus,
            checkQueries,
        )

    private fun resolve(
        objects: Map<QualifiedName, ModelObject>,
        mappings: Map<QualifiedName, List<Er2DbEntityMapping>>,
        tags: Set<String>,
        parseStatus: (Query) -> ParseStatus,
        checkQueries: Boolean,
    ): Result {
        val selected = linkedSetOf<QualifiedName>()
        val warnings = mutableListOf<Warning>()

        fun add(qname: QualifiedName) {
            if (qname in objects) selected += qname
        }

        fun tagged(obj: ModelObject) = obj.tags.any { it in tags }
        if (tags.isEmpty()) selected += objects.keys else objects.values.filter(::tagged).forEach { add(it.qname) }
        // Only tagged edges pull endpoints. Containment never walks up from a part to its owner.
        if (tags.isNotEmpty()) {
            objects.values.filter(::tagged).forEach { obj ->
                when (obj) {
                    is Relation -> {
                        add(obj.fromEntity)
                        add(obj.toEntity)
                    }
                    is DbForeignKey ->
                        (obj.fromColumns + obj.toColumns).forEach { column ->
                            (objects[column] as? DbColumn)?.let { add(it.table) }
                        }
                    else -> Unit
                }
            }
        }
        selected.toList().forEach { q ->
            when (val obj = objects[q]) {
                is Entity -> {
                    obj.attributes.forEach { add(it.qname) }
                    mappings[q].orEmpty().forEach { mapping ->
                        val target = (mapping.target as? MappingTarget.SqlQuery)?.qname
                        if (target?.name == "${q.name}__filter") add(target)
                    }
                }
                is DbTable -> obj.columns.forEach { add(it.qname) }
                is DbView -> obj.columns.forEach { add(it.qname) }
                else -> Unit
            }
        }

        fun requireRef(
            owner: ModelObject,
            target: QualifiedName,
        ) {
            if (target !in selected) {
                warnings +=
                    Warning(
                        "meta/scope-violation",
                        "${owner.qname.dotted()} requires ${target.dotted()} outside the tag scope",
                        owner.sourceFile,
                    )
            }
        }
        selected.toList().forEach { q ->
            val obj = objects.getValue(q)
            when (obj) {
                is Entity ->
                    mappings[q].orEmpty().forEach { mapping ->
                        when (val target = mapping.target) {
                            is MappingTarget.Table -> requireRef(obj, target.qname)
                            is MappingTarget.View -> requireRef(obj, target.qname)
                            is MappingTarget.SqlQuery -> requireRef(obj, target.qname)
                        }
                    }
                is Query ->
                    if (checkQueries && !obj.skipSecurity) {
                        when (val parsed = parseStatus(obj)) {
                            is ParseStatus.ParseSuccess -> {
                                val plan =
                                    runCatching {
                                        PlanNode.parseFrom(
                                            parsed.canonicalFormProtoBytes,
                                        )
                                    }.getOrNull()
                                if (plan ==
                                    null
                                ) {
                                    warnings +=
                                        Warning(
                                            "meta/scope-unavailable",
                                            "${q.dotted()}: invalid parsed plan",
                                            obj.sourceFile,
                                        )
                                } else {
                                    JoinerPlanWalker.forEachNode(plan) { node ->
                                        when (node.nodeCase) {
                                            PlanNode.NodeCase.TABLE_SCAN ->
                                                requireRef(
                                                    obj,
                                                    node.tableScan.table.toDomain(),
                                                )
                                            PlanNode.NodeCase.SCAN -> requireRef(obj, node.scan.getObject().toDomain())
                                            else -> Unit
                                        }
                                    }
                                }
                            }
                            else ->
                                warnings +=
                                    Warning(
                                        "meta/scope-unavailable",
                                        "${q.dotted()}: query requirements are not available (${if (parsed is ParseStatus.ParseFailure) "failed" else "pending"})",
                                        obj.sourceFile,
                                    )
                        }
                    }
                else -> Unit
            }
        }
        return Result(
            selected.map(objects::getValue).sortedBy {
                it.qname.dotted()
            },
            warnings.distinct().sortedWith(compareBy({ it.code }, { it.message }, { it.sourceFile })),
        )
    }

    /** Structural diagnostics for each authored tag; unions are checked separately on each request. */
    fun loadWarnings(model: Model): List<LoadWarning> {
        val objects = model.objectByQname()
        val mappings = model.mappings.filterIsInstance<Er2DbEntityMapping>().groupBy { it.entity }
        return objects.values.flatMap { it.tags }.toSortedSet().flatMap { tag ->
            resolve(objects, mappings, setOf(tag), { it.parseStatus }, false).warnings.map { warning ->
                LoadWarning("tag-scope", warning.sourceFile, 0, 0, "${warning.code}: tag '$tag': ${warning.message}")
            }
        }
    }
}
