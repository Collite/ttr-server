// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import org.tatrman.metadata.grpc.toProto
import org.tatrman.metadata.scope.bindingPackages
import org.tatrman.metadata.scope.bindingNames
import org.tatrman.ttr.metadata.model.Binding
import org.tatrman.ttr.metadata.model.Er2DbEntityMapping
import org.tatrman.ttr.metadata.model.MappingTarget
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.Query
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.model.WhereFilter
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LoadWarning
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.SourceSnapshot

/**
 * MW S4.2 T2b (✅D-MW-67 (c′), MW-P4 contracts §1) — filtered bindings, for every source kind. Each `er2db_entity` whose
 * `whereFilter` is `{sql: <c>}` and whose target is a table or a view is served as the db query `<entity>__filter` in
 * the entity's package: `SELECT * FROM <target's physical name> WHERE <c>`, tags `synthetic` / `where-filter`, and the
 * mapping targets that query (`sql_query` on the wire). The query is an ordinary member of the snapshot, so the
 * reconciler, the parse worker and `/status` treat it like an authored one. This is the legacy loader's synthesis
 * (`ParseGate` S-6) and ttr-gen's output, with the same name and SQL.
 *
 * Never silent: any other `whereFilter` form, or one on a query target, is a warning at the authored `whereFilter`; a
 * synthesized name that is already a query in the snapshot is a load error there, and the authored query and the
 * table target stay as they are.
 */
internal class FilteredBindingSource(
    private val source: ModelSource,
) : ModelSource {
    override fun load(): SourceSnapshot = synthesizeFilteredBindings(source.load())
}

internal fun synthesizeFilteredBindings(snapshot: SourceSnapshot): SourceSnapshot {
    val filtered = snapshot.mappings.filterIsInstance<Er2DbEntityMapping>().filter { it.whereFilter != null }
    if (filtered.isEmpty()) return snapshot
    val packageOf = bindingPackages(snapshot.loadedFiles)

    val tableNames = snapshot.tables.values.bindingNames(packageOf)
    val viewNames = snapshot.views.values.bindingNames(packageOf)
    val queries = LinkedHashMap(snapshot.queries)
    val retargeted = HashMap<Er2DbEntityMapping, Er2DbEntityMapping>()
    val warnings = mutableListOf<LoadWarning>()
    val errors = mutableListOf<LoadWarning>()
    for (mapping in filtered) {
        val filter = mapping.whereFilter!!

        fun at(message: String) =
            LoadWarning(snapshot.sourceId, mapping.sourceFile, filter.line, filter.column, message)
        val label = "er2db_entity ${mapping.qname.name}"
        val physical =
            when (val target = mapping.target) {
                is MappingTarget.Table -> target.qname
                is MappingTarget.View -> target.qname
                is MappingTarget.SqlQuery -> null
            }
        when {
            filter is WhereFilter.Fields ->
                warnings +=
                    at(
                        "$label: whereFilter {${filter.fields.keys.joinToString(", ")}} is not applied — only " +
                            "{sql: <condition>} becomes ${mapping.entity.name}$SUFFIX (MW-P4 contracts §1)",
                    )
            physical == null ->
                warnings +=
                    at("$label: whereFilter is not applied — its target is a query, not a table or a view")
            else -> {
                val names = if (mapping.target is MappingTarget.Table) tableNames else viewNames
                val reference = physical.toProto()
                // Keep the authored target visible to scope checks when an unqualified SQL scan
                // would read the same-name declaration from a different package.
                val bare = reference.toBuilder().clearPackage().build()
                if (reference != bare && names.resolve(bare) != null && names.resolve(reference) != bare) continue

                val sql = (filter as WhereFilter.Sql).sql
                val name =
                    QualifiedName(
                        schemaCode = SchemaCode.DB,
                        namespace = physical.namespace,
                        name = mapping.entity.name + SUFFIX,
                        `package` = packageOf(snapshot, mapping.entity),
                    )
                if (queries.keys.any { it.sameQuery(name) }) {
                    errors +=
                        at(
                            "$label: the synthesized query ${name.name} collides with a query of the same name " +
                                "in package '${name.`package`}'; it is not synthesized",
                        )
                    continue
                }
                queries[name] =
                    Query(
                        internalId = FileBasedSource.idFor("query", name),
                        qname = name,
                        description = "The row filter of ${mapping.entity.name}'s er2db_entity, served as a query.",
                        tags = TAGS,
                        sourceFile = mapping.sourceFile,
                        binding = Binding.BoundSynthetic("whereFilter on $label"),
                        sourceLanguage = "SQL",
                        sourceText = "SELECT * FROM ${physical.name} WHERE ${sql.trim().trimEnd(';').trimEnd()}",
                    )
                retargeted[mapping] = mapping.copy(target = MappingTarget.SqlQuery(name))
            }
        }
    }
    return snapshot.copy(
        queries = queries,
        mappings = snapshot.mappings.map { m -> (m as? Er2DbEntityMapping)?.let { retargeted[it] } ?: m },
        warnings = snapshot.warnings + warnings,
        errors = snapshot.errors + errors,
    )
}

private const val SUFFIX = "__filter"
private val TAGS = listOf("synthetic", "where-filter")

/** One query name per package: the namespace a reader resolves a query through is not part of its identity here. */
private fun QualifiedName.sameQuery(other: QualifiedName) =
    schemaCode == other.schemaCode && name == other.name && `package` == other.`package`

/**
 * The entity's package: the mapping's reference when it is qualified, else the one entity of that name this snapshot
 * declares (ttr-metadata keys a bare `er.entity.E` package-less; the qualified qname is the source of truth).
 */
private fun packageOf(
    snapshot: SourceSnapshot,
    entity: QualifiedName,
): String =
    entity.`package`.ifEmpty {
        snapshot.entities.keys
            .filter { it.name == entity.name && it.namespace == entity.namespace }
            .map { it.`package` }
            .distinct()
            .singleOrNull()
            .orEmpty()
    }
