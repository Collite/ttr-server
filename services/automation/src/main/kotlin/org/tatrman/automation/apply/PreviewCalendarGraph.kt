// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import java.util.Locale
import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.runner.*
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.world.*
import org.tatrman.ttrp.ast.AsOf
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.emit.sql.*
import org.tatrman.ttrp.expr.*
import org.tatrman.ttrp.graph.capability.BoundWorld
import org.tatrman.ttrp.graph.model.*
import org.tatrman.ttrp.graph.recipe.*
import org.tatrman.ttrp.graph.rewrite.RewriteEngine
import org.tatrman.ttrp.graph.rewrite.Rules

/** Execute the published compiler's relational calendar graph over two already-governed snapshots. */
internal object PreviewCalendarGraph {
    data class Result(
        val expressions: List<Expression>,
        val table: Table,
    )

    fun lower(
        expressions: List<Expression>,
        table: Table,
        bindings: Map<String, BoundValue>,
        snapshot: CalendarSnapshot?,
    ): Result {
        val calls = PreviewCalendar.calls(expressions).distinct()
        if (calls.isEmpty()) return Result(expressions, table)
        requireNotNull(snapshot) { "calendar source was not pinned at open" }
        require(
            table.columns.none { it.name.startsWith("_ttrp_", ignoreCase = true) },
        ) { "reserved compiler helper column" }
        if (calls.any { it.function.name == "age" && "and" !in it.argNames }) {
            require(bindings["as_of"]?.type in setOf("date", "datetime", "timestamp", "timestamptz"))
        }
        val authoredNames =
            expressions
                .flatMap {
                    NodeExpressions.walk(it).filterIsInstance<ColumnRef>().toList()
                }.map { it.column }
        val used = (table.columns.map { it.name } + authoredNames).map { it.lowercase(Locale.ROOT) }.toMutableSet()

        fun fresh() =
            generateSequence(0) { it + 1 }
                .map { "_au_calendar_$it" }
                .first { used.add(it.lowercase(Locale.ROOT)) }
        val index = fresh()
        val asOf = fresh()
        val aliases = calls.map { fresh() }
        val location = SourceLocation.UNKNOWN
        val referenced = calls.flatMap { it.args.filterIsInstance<ColumnRef>() }.map { it.column }.toSet()
        val ordinals = table.columns.indices.filter { table.columns[it].name in referenced }
        val columns =
            ordinals.map { table.columns[it] } + ManifestColumn(index, "int") + ManifestColumn(asOf, "timestamptz")
        val asOfValue =
            bindings["as_of"]?.value.let {
                (it as? java.time.Instant)?.atOffset(java.time.ZoneOffset.UTC) ?: it
            }
        val input =
            Table(
                columns,
                table.rows.mapIndexed {
                    i,
                    row,
                    ->
                    ordinals.map { row[it] } + i.toLong() + asOfValue
                },
            )
        val normalizedCalls =
            calls.map { call ->
                val expected = if (call.function.name == "age") setOf("since", "and") else setOf("between", "and")
                require(call.argNames.distinct().size == call.args.size && call.argNames.all { it in expected })
                require(call.argNames.contains(if (call.function.name == "age") "since" else "between"))
                if (call.function.name == "working_days") require("and" in call.argNames)
                call.args.forEach { arg ->
                    val ref = arg as? ColumnRef ?: error("calendar argument must be a column or bound date")
                    require(ref.port == null)
                    require(
                        input.columns.single { it.name == ref.column }.type in
                            setOf("date", "datetime", "timestamp", "timestamptz"),
                    )
                }
                if (call.function.name == "age" && "and" !in call.argNames) {
                    call.copy(args = call.args + ColumnRef(null, asOf, call.location), argNames = call.argNames + "and")
                } else {
                    call
                }
            }
        val load = Load("input", "input", location, "_au_calendar_input", schemaRef = "_au_calendar_input")
        val project =
            Project(
                "value",
                "value",
                location,
                columns = listOf(ColumnRef(null, index, location)) + normalizedCalls,
                aliases = listOf(index) + aliases,
            )
        val container =
            Container(
                "preview",
                "preview",
                location,
                "preview",
                listOf(load.id, project.id),
                emptyList(),
                mapOf(
                    "out" to PortRef(project.id, PortNames.OUT),
                ),
            )
        val graph =
            TtrpGraph(
                mapOf(load.id to load, project.id to project, container.id to container),
                listOf(Edge(PortRef(load.id, PortNames.OUT), PortRef(project.id, PortNames.IN), EdgeKind.DATA)),
                mapOf(
                    container.id to container,
                ),
            )
        val recipe = RecipeDecl(emptyList(), AsOf.Now, emptyList(), null, null, null, emptyList(), location)
        val rg = RecipeGraph(recipe, mapOf("preview" to container.id), null, null, emptyMap())
        val model =
            RecipeModel(
                "preview",
                emptyList(),
                emptyList(),
                null,
                emptyList(),
                null,
                emptyList(),
                null,
                emptyList(),
                emptyList(),
                RecipeMode.PER_CANDIDATE,
                emptyList(),
            )
        val lowered = SugarLowering(rg, model, snapshot.plan.index).lower(graph)
        require(lowered.diagnostics.none { it.severity == Severity.ERROR } && lowered.derived.isEmpty())
        require(
            lowered.graph.loadSources.values
                .all { it.objectQname == snapshot.plan.entity },
        )
        // These names are local Arrow tables, never physical sources. Only compiler-inserted calendar Loads
        // are remapped; no generated SQL can cause another door read.
        val stagedGraph =
            lowered.graph.copy(
                nodes =
                    lowered.graph.nodes.mapValues { (id, node) ->
                        if (node is Load &&
                            id != load.id
                        ) {
                            node.copy(source = "_au_calendar_source", schemaRef = "_au_calendar_source")
                        } else {
                            node
                        }
                    },
            )

        fun qname(name: String) = QualifiedName(SchemaCode.WORLD, "", name)
        val schemas =
            listOf(
                ResolvedStorageSchema(qname("_au_calendar_input"), columns.associate { it.name to it.type }),
                ResolvedStorageSchema(
                    qname("_au_calendar_source"),
                    snapshot.table.columns.associate { it.name to it.type },
                ),
            )
        val storage = ResolvedStorage(qname("scratch"), null, null, emptyList(), false, schemas, null, emptyMap())
        val world = ResolvedWorld(qname("scratch"), emptyList(), emptyList(), listOf(storage), null, "scratch")
        val bound = BoundWorld(world, emptyMap(), emptyMap(), world.storages, emptyList())
        val normalized = RewriteEngine(Rules.ALL, bound).normalize(stagedGraph)
        require(normalized.diagnostics.none { it.severity == Severity.ERROR })
        val plan =
            SqlGraphEmitter(
                normalized.graph,
                bound,
            ).plansByOutput(normalized.graph.containers.getValue(container.id)).getValue("out")
        val sql =
            SqlTextRender.doubleQuoted(
                CtePlanner(facade = {
                    TranslatorFacade(IslandModelHandle(it), SqlDialect.DUCKDB)
                }).emit(plan, "preview"),
            )
        val result =
            DuckDbCombiner().use { db ->
                db.load("_au_calendar_input", input)
                db.load("_au_calendar_source", snapshot.table)
                db.query(sql, emptyMap())
            }
        require(result.columns.map { it.name } == listOf(index) + aliases)
        require(result.columns.drop(1).all { it.type == "int" }) { "calendar counts must remain integers" }
        require(result.rows.size == table.rows.size)
        val byIndex = result.rows.associateBy { it.first() as Long }
        require(
            byIndex.keys ==
                table.rows.indices
                    .map { it.toLong() }
                    .toSet(),
        )
        val replaced = calls.zip(aliases).toMap()
        return Result(
            expressions.map { expression ->
                NodeExpressions.rewrite(expression) {
                    replaced[it]?.let { alias ->
                        ColumnRef(null, alias, it.location)
                    }
                }
            },
            Table(
                table.columns + result.columns.drop(1),
                table.rows.mapIndexed { i, row ->
                    row +
                        byIndex.getValue(i.toLong()).drop(1)
                },
            ),
        )
    }
}
