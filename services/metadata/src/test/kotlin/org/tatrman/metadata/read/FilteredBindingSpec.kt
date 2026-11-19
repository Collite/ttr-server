// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.tatrman.meta.v1.GetObjectRequest
import org.tatrman.metadata.buildSources
import org.tatrman.metadata.grpc.MetadataServiceImpl
import org.tatrman.metadata.grpc.PackageIndex
import org.tatrman.metadata.grpc.toProto
import org.tatrman.metadata.health.SourceLoadBoard
import org.tatrman.metadata.health.modelHealthRoutes
import org.tatrman.metadata.parse.QueryParseState
import org.tatrman.metadata.parse.QueryParseWorker
import org.tatrman.metadata.refresh.GuardedRefresher
import org.tatrman.ttr.metadata.model.Binding
import org.tatrman.ttr.metadata.model.Er2DbEntityMapping
import org.tatrman.ttr.metadata.model.MappingTarget
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ParseStatus
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import java.nio.file.Files
import java.nio.file.Path

/**
 * MW S4.2 T2b (✅D-MW-67 (c′), MW-P4 contracts §1) — an `er2db_entity` with `whereFilter: {sql: <c>}` and a table target
 * is served as the db query `<entity>__filter` (`SELECT * FROM <table> WHERE <c>`, tags `synthetic`/`where-filter`),
 * its mapping as that query, parsed with the others; any other form is a located warning, and a synthesized name that
 * collides with an authored query is a load error. An invented model; the service's own source path (`buildSources`).
 */
class FilteredBindingSpec :
    StringSpec({
        val filterQuery = QualifiedName(schemaCode = SchemaCode.DB, namespace = "dbo", name = "A__filter")

        "a sql whereFilter is served as <entity>__filter, and the entity's mapping is that query" {
            withModel { root ->
                val f = Fixture(root)
                f.refresh()
                val model = f.registry.readOrError().model
                val query = model.queries.getValue(filterQuery)
                query.sourceText shouldBe "SELECT * FROM T WHERE STAV = 1"
                query.sourceLanguage shouldBe "SQL"
                query.tags shouldContainExactly listOf("synthetic", "where-filter")
                query.binding.shouldBeInstanceOf<Binding.BoundSynthetic>()
                val mappings = model.mappings.filterIsInstance<Er2DbEntityMapping>().associateBy { it.entity.name }
                mappings.getValue("A").target shouldBe MappingTarget.SqlQuery(filterQuery)
                mappings.getValue("C").target.shouldBeInstanceOf<MappingTarget.Table>()
                // The wire: the entity's Er2DbEntityMappingDetail names the query (`sql_query`), not the table.
                val detail =
                    f.service
                        .getObject(
                            GetObjectRequest
                                .newBuilder()
                                .setQualifiedName(mappings.getValue("A").qname.toProto())
                                .build(),
                        ).er2DbEntityMapping
                detail.hasSqlQuery() shouldBe true
                detail.hasTable() shouldBe false
                detail.sqlQuery.name shouldBe "A__filter"
            }
        }

        "the synthesized query is counted and parsed like an authored one" {
            withModel { root ->
                val f = Fixture(root)
                f.refresh()
                val model = f.registry.readOrError().model
                f.status()["queries_total"]!!.jsonPrimitive.int shouldBe model.queries.size
                val state = QueryParseState().also { it.reset(model.version.value, model.queries.keys) }
                QueryParseWorker().also { runBlocking { it.parseAll(model, state).join() } }.close()
                val status = state.get(model.version.value, filterQuery)
                withClue(status.toString()) { status.shouldBeInstanceOf<ParseStatus.ParseSuccess>() }
            }
        }

        "another whereFilter form is a located warning; a name clash with an authored query is a load error" {
            withModel(
                authoredClash = true,
            ) { root ->
                val f = Fixture(root)
                f.refresh()
                val model = f.registry.readOrError().model
                // The authored query stays and the mapping keeps its table: nothing is synthesized over it.
                model.queries.getValue(filterQuery).sourceText shouldContain "authored"
                model.mappings
                    .filterIsInstance<Er2DbEntityMapping>()
                    .single { it.entity.name == "A" }
                    .target
                    .shouldBeInstanceOf<MappingTarget.Table>()
                val issues = f.status()["issues"]!!.jsonArray.map { it.jsonPrimitive.content }
                withClue(issues.joinToString("\n")) {
                    issues.single { "B_map" in it }.let {
                        it shouldContain "binding.ttrm:12:"
                        it shouldContain "whereFilter"
                    }
                    issues.single { "A__filter" in it }.let {
                        it shouldContain "binding.ttrm:6:"
                        it shouldContain "collides"
                    }
                }
                f.registry
                    .readOrError()
                    .warnings
                    .single { "A__filter" in it.message }
                    .let { it.line shouldBe 6 }
            }
        }
    })

/** The invented model: a table, three entities; A filters by SQL, B by a map, C has none. */
private fun withModel(
    authoredClash: Boolean = false,
    block: suspend (Path) -> Unit,
) {
    val root = Files.createTempDirectory("filtered-binding")
    try {
        Files.writeString(
            root.resolve("db.ttrm"),
            "model db schema dbo\n\ndef table T { columns: [ def column STAV { type: int } ] }\n" +
                if (authoredClash) {
                    "\ndef query A__filter {\n    sourceText: \"\"\"sql\nSELECT STAV FROM T -- authored\n\"\"\"\n}\n"
                } else {
                    ""
                },
        )
        Files.writeString(
            root.resolve("er.ttrm"),
            "model er\n\ndef entity A { attributes: [] }\ndef entity B { attributes: [] }\n" +
                "def entity C { attributes: [] }\n",
        )
        Files.writeString(
            root.resolve("binding.ttrm"),
            """
            model binding

            def er2db_entity A_map {
                entity: er.entity.A
                target: { table: db.dbo.T }
                whereFilter: { sql: "STAV = 1" }
            }

            def er2db_entity B_map {
                entity: er.entity.B
                target: { table: db.dbo.T }
                whereFilter: { STAV: 1 }
            }

            def er2db_entity C_map {
                entity: er.entity.C
                target: { table: db.dbo.T }
            }
            """.trimIndent() + "\n",
        )
        runBlocking { block(root) }
    } finally {
        root.toFile().deleteRecursively()
    }
}

private class Fixture(
    root: Path,
) {
    val registry = MetadataRegistry()
    private val board = SourceLoadBoard(strictSwap = false)
    private val index = PackageIndex().commitOnSwap(registry)
    val service = MetadataServiceImpl(registry, packageIndex = index)
    private val slots =
        buildSources(
            ConfigFactory.parseMap(
                mapOf("metadata.sources" to listOf(mapOf("id" to "shop", "type" to "filesystem", "path" to "$root"))),
            ),
            board,
        )
    private val refresher =
        GuardedRefresher(
            slots.map { it.source },
            slots.map { it.id },
            ModelReconciler(ModelDescriptor("filtered", "filtered")),
            registry,
            false,
            board.states().map { it.id }.toSet(),
            board,
            index,
        )

    suspend fun refresh() {
        refresher.refreshOutcome("", true).status shouldBe "ok"
    }

    suspend fun status() =
        run {
            var body = ""
            testApplication {
                environment { config = MapApplicationConfig() }
                application { routing { modelHealthRoutes(registry, board) } }
                body = client.get("/status").body()
            }
            Json.parseToJsonElement(body).jsonObject
        }
}
