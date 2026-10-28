// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.every
import io.mockk.mockk
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import org.tatrman.metadata.grpc.PackageIndex
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.search.SearchAlgorithmRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.nio.file.Path

class StructuralReadProjectionReviewSpec :
    StringSpec({
        "R1 one explicit binding prefers its query-path target over the definition resolver" {
            withWorld(
                mapOf(
                    "db/dbo/tables.ttrm" to "model db schema dbo\ndef table buyers {}",
                    "db/retail/tables.ttrm" to "model db schema retail\ndef table buyers {}",
                    "er/buyer.ttrm" to "model er\ndef entity buyer {}",
                    "db/retail/binding.ttrm" to """
                package db.retail
                import db.retail.*
                import er.*
                model binding
                def er2db_entity b { entity: er.entity.buyer, target: { table: buyers } }
            """,
                ),
            ) { world ->
                withHttp(world) { get ->
                    val edges = get("/model/graph").readEdges()
                    edges.filter { it.first == "er.entity.buyer" && it.third == "MAPS_TO" }.map { it.second } shouldBe
                        listOf("db.dbo.buyers")
                    edges.any { it.first == "er2db_entity.er2db_entity.b" && it.second == "db.dbo.buyers" } shouldBe
                        true
                    val refs =
                        get(
                            "/model/object?qname=er.entity.buyer",
                        )["references"]!!.jsonArray.map { it.jsonPrimitive.content }
                    refs.contains("db.dbo.buyers") shouldBe true
                    refs.contains("db.retail.buyers") shouldBe false
                }
            }
        }

        "R2 colliding export ids never move a binding to another ER schema in either file order" {
            for (defaultFile in listOf("z.ttrm", "a.ttrm")) {
                withProjectionLogs { logs ->
                    withWorld(
                        mapOf(
                            "db/retail/tables.ttrm" to
                                "model db schema retail\ndef table wh_main {}\ndef table wh_store {}",
                            "er/$defaultFile" to "model er\ndef entity warehouse {}",
                            "er/storage.ttrm" to "model er schema storage\ndef entity warehouse {}",
                            "binding/mappings.ttrm" to """
                        import er.*
                        import db.retail.*
                        model binding
                        def er2db_entity main_wh { entity: er.entity.warehouse, target: { table: db.retail.wh_main } }
                    """,
                        ),
                    ) { world ->
                        withHttp(world) { get ->
                            val edges = get("/model/graph").readEdges()
                            edges.contains(Triple("er.entity.warehouse", "db.retail.wh_main", "MAPS_TO")) shouldBe true
                            edges.none { it.first == "er.storage.warehouse" && it.third == "MAPS_TO" } shouldBe true
                            get("/model/graph")
                            logs.warnings().size shouldBe 1
                            logs.warnings().single().formattedMessage shouldContain "er.entity.warehouse"
                            logs.warnings().single().formattedMessage shouldContain "storage.ttrm"
                        }
                    }
                }
            }
        }

        "R1 duplicate binding export ids cannot choose a definition fallback" {
            withWorld(
                mapOf(
                    "db/tables.ttrm" to "model db\ndef table ta {}\ndef table tb {}",
                    "er/entities.ttrm" to "model er\ndef entity a {}\ndef entity b {}",
                    "binding/a.ttrm" to
                        "import er.*\nimport db.*\nmodel binding\ndef er2db_entity same { entity: er.a, target: { table: db.dbo.ta } }",
                    "binding/b.ttrm" to
                        "import er.*\nimport db.*\nmodel binding\ndef er2db_entity same { entity: er.b, target: { table: db.dbo.tb } }",
                ),
            ) { world ->
                val cache = StructuralReadProjectionCache(world.registry, world.packages)
                val result = cache.read(world.registry.readOrError())
                result.edges.none { it.from in setOf("er.entity.a", "er.entity.b") && it.type == "MAPS_TO" } shouldBe
                    true
                result.diagnostics.omittedEdges shouldBe 1
                result.diagnostics.ambiguousIds.keys
                    .contains("er2db_entity.same") shouldBe true
            }
        }

        "R2 a partially joined export id is omitted rather than assigned to its only joined declaration" {
            withProjectionLogs { logs ->
                withWorld(
                    mapOf(
                        "db/tables.ttrm" to "model db\ndef table warehouse_table {}",
                        "er/a.ttrm" to "model er\ndef entity warehouse {}",
                        "er/b.ttrm" to "model er schema storage\ndef entity warehouse {}",
                        "binding/b.ttrm" to
                            "import er.*\nimport db.*\nmodel binding\ndef er2db_entity b { entity: er.warehouse, target: { table: db.dbo.warehouse_table } }",
                    ),
                ) { world ->
                    val snap = world.registry.readOrError()
                    val er = snap.model.schemas.getValue("er") as org.tatrman.ttr.metadata.model.ErSchema
                    val partial =
                        snap.model.copy(
                            schemas =
                                snap.model.schemas + (
                                    "er" to
                                        er.copy(
                                            entities = er.entities.filterKeys { it.namespace == "storage" },
                                        )
                                ),
                        )
                    val files = snap.definitions
                    world.registry.swap(partial, ModelGraph.build(partial))
                    // Keep real parsed declarations to exercise a mixed joined/unjoined identity.
                    val partialSnap = world.registry.readOrError().copy(definitions = files)
                    val cache = StructuralReadProjectionCache(world.registry, world.packages)
                    val result = cache.read(partialSnap)
                    result.edges.none { it.from == "er.storage.warehouse" && it.type == "MAPS_TO" } shouldBe true
                    result.diagnostics.omittedEdges shouldBe 1
                    result.diagnostics.ambiguousIds.keys
                        .contains("er.entity.warehouse") shouldBe true
                    // An uncached request does not emit publication warnings.
                    logs.warnings().size shouldBe 0
                }
            }
        }

        "R3 same-named inline measures retain the first declaration and warn only on publication" {
            withProjectionLogs { logs ->
                withWorld(
                    mapOf(
                        "db/tables.ttrm" to "model db\ndef table anchor {}",
                        "md/model.ttrm" to """
                    model md
                    def domain Money { type: decimal }
                    def cubelet sales { grain: [], measures: [def measure amount { domain: md.Money, description: "sales amount" }] }
                    def cubelet returns { grain: [], measures: [def measure amount { domain: md.Money, description: "returns amount" }] }
                """,
                    ),
                ) { world ->
                    withHttp(world) { get ->
                        val nodes = get("/model/graph")["nodes"]!!.jsonArray.map { it.jsonObject }
                        nodes.count { it["qname"]!!.jsonPrimitive.content == "md.measure.amount" } shouldBe 1
                        nodes
                            .single { it["qname"]!!.jsonPrimitive.content == "md.measure.amount" }["description"]!!
                            .jsonPrimitive.content shouldBe "sales amount"
                        get("/model/graph")
                        logs.warnings().size shouldBe 1
                        logs.warnings().single().formattedMessage shouldContain "md.measure.amount"
                    }
                }
            }
        }

        "R6 hierarchy node preserves authored level order in graph and detail" {
            withWorld(
                mapOf(
                    "db/tables.ttrm" to "model db\ndef table anchor {}",
                    "md/calendar.ttrm" to """
                model md
                def domain DatePart { type: int }
                def dimension Cal { key: day, attributes: [
                    def attribute year { domain: md.DatePart },
                    def attribute month { domain: md.DatePart },
                    def attribute day { domain: md.DatePart, isKey: true }
                ] }
                def hierarchy Rollup { dimension: md.Cal, levels: [year, month, day] }
            """,
                ),
            ) { world ->
                withHttp(world) { get ->
                    val node =
                        get("/model/graph")["nodes"]!!
                            .jsonArray
                            .map { it.jsonObject }
                            .single { it["qname"]!!.jsonPrimitive.content == "md.hierarchy.Rollup" }
                    node["levels"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
                        listOf("md.dimension.Cal.year", "md.dimension.Cal.month", "md.dimension.Cal.day")
                    get("/model/object?qname=md.hierarchy.Rollup")["object"] shouldBe node
                }
            }
        }

        "R7 publication caches the full projection and old reads cannot evict the current result" {
            withWorld(mapOf("db/tables.ttrm" to "model db\ndef table anchor {}")) { world ->
                val cache = StructuralReadProjectionCache(world.registry, world.packages)
                val old = world.registry.readOrError()
                val oldResult = cache.read(old)
                (cache.read(old) === oldResult) shouldBe true
                Files.writeString(world.root.resolve("db/tables.ttrm"), "model db\ndef table replacement {}\n")
                world.reload()
                val current = world.registry.readOrError()
                val currentResult = cache.read(current)
                (cache.read(current) === currentResult) shouldBe true
                currentResult.objects.getValue("db.dbo.replacement").pkg shouldBe "db"
                (cache.read(old) === oldResult) shouldBe false
                (cache.read(old) === cache.read(old)) shouldBe false
                (cache.read(current) === currentResult) shouldBe true
            }
        }

        "R7 listener build failures stay uncached and uncached failures remain visible" {
            withProjectionLogs { logs ->
                withWorld(mapOf("db/tables.ttrm" to "model db\ndef table anchor {}")) { world ->
                    val packages = mockk<PackageIndex>()
                    every { packages.packageOf(any()) } returns "db"
                    val cache = StructuralReadProjectionCache(world.registry, packages)
                    every { packages.packageOf(any()) } throws IllegalStateException("package probe")
                    world.reload()
                    val snap = world.registry.readOrError()
                    shouldThrow<IllegalStateException> { cache.read(snap) }
                    logs.warnings().size shouldBe 1
                    logs.warnings().single().formattedMessage shouldContain "projection failed"
                    every { packages.packageOf(any()) } returns "db"
                    (cache.read(snap) === cache.read(snap)) shouldBe false
                    world.reload()
                    val repaired = world.registry.readOrError()
                    (cache.read(repaired) === cache.read(repaired)) shouldBe true
                }
            }
        }

        "R7 listener installed before initial load serves the committed package index" {
            withWorld(mapOf("db/tables.ttrm" to "model db\ndef table anchor {}"), load = false) { world ->
                val cache = StructuralReadProjectionCache(world.registry, world.packages)
                world.reload()
                val snap = world.registry.readOrError()
                val result = cache.read(snap)
                result.objects.getValue("db.dbo.anchor").pkg shouldBe "db"
                (cache.read(snap) === result) shouldBe true
            }
        }
    })

private class ReviewWorld(
    val root: Path,
) {
    val registry = MetadataRegistry()
    val packages = PackageIndex().commitOnSwap(registry)

    fun reload() {
        val source = FileBasedSource("review-177", 100, LocalFsStorage("review-177", root))
        val loaded = MetadataLoader(listOf(packages.recording("review-177", source))).load()
        loaded.errors shouldBe emptyList()
        val model = requireNotNull(loaded.model)
        registry.swap(model, ModelGraph.build(model), files = loaded.files)
    }
}

private suspend fun withWorld(
    files: Map<String, String>,
    load: Boolean = true,
    block: suspend (ReviewWorld) -> Unit,
) {
    val root = Files.createTempDirectory("metadata-review-177")
    try {
        for ((path, content) in files) {
            val target = root.resolve(path)
            Files.createDirectories(target.parent)
            Files.writeString(target, content.trimIndent() + "\n")
        }
        val world = ReviewWorld(root)
        if (load) world.reload()
        block(world)
    } finally {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}

private suspend fun withHttp(
    world: ReviewWorld,
    block: suspend (suspend (String) -> JsonObject) -> Unit,
) {
    testApplication {
        environment { config = MapApplicationConfig() }
        application {
            routing {
                metadataReadRoutes(
                    world.registry,
                    SearchAlgorithmRegistry(emptyMap()),
                    null,
                    world.packages,
                    emptySet(),
                )
            }
        }
        block { path ->
            val response = client.get(path)
            response.status shouldBe HttpStatusCode.OK
            Json.parseToJsonElement(response.bodyAsText()).jsonObject
        }
    }
}

private fun JsonObject.readEdges(): List<Triple<String, String, String>> =
    getValue("edges").jsonArray.map {
        val e = it.jsonObject
        Triple(
            e.getValue("from").jsonPrimitive.content,
            e.getValue("to").jsonPrimitive.content,
            e.getValue("type").jsonPrimitive.content,
        )
    }

private fun List<ILoggingEvent>.warnings() = filter { it.level == Level.WARN }

private suspend fun withProjectionLogs(block: suspend (List<ILoggingEvent>) -> Unit) {
    val logger = LoggerFactory.getLogger(StructuralReadProjectionCache::class.java) as Logger
    val appender = ListAppender<ILoggingEvent>()
    appender.start()
    logger.addAppender(appender)
    try {
        block(appender.list)
    } finally {
        logger.detachAppender(appender)
        appender.stop()
    }
}
