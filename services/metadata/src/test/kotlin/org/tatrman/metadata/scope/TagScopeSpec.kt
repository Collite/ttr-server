// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.scope

import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.tatrman.meta.v1.ResolveTagsRequest
import org.tatrman.metadata.grpc.MetadataServiceImpl
import org.tatrman.metadata.grpc.PackageIndex
import org.tatrman.metadata.grpc.toProto
import org.tatrman.metadata.parse.QueryParseState
import org.tatrman.metadata.read.vocabularyRoutes
import org.tatrman.metadata.refresh.GuardedRefresher
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.Model
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
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.SearchHints
import org.tatrman.ttr.metadata.model.Cardinality
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ModelVersion
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.DbSchema
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.TableScanNode
import java.nio.file.Path
import org.tatrman.ttr.metadata.source.ModelStorage
import org.tatrman.ttr.metadata.source.StorageFile
import java.nio.file.Files
import java.time.Instant

class TagScopeSpec :
    StringSpec({
        "OR tags containment whole model and unknown tags have exact deterministic selections" {
            val f = ScopeFixture()
            TagScope.normalize(listOf(" b ", "a", "a")) shouldBe sortedSetOf("a", "b")
            shouldThrow<IllegalArgumentException> { TagScope.normalize(listOf(" ")) }
            f.names("a") shouldBe listOf("er.entity.A", "er.entity.A.id")
            f.names("a", "b") shouldBe listOf("er.entity.A", "er.entity.A.id", "er.entity.B", "er.entity.B.id")
            f.names("unknown") shouldBe emptyList()
            f.names().toSet() shouldBe
                f.model
                    .objectByQname()
                    .keys
                    .map { it.dotted() }
                    .toSet()
            // A tagged part cannot pull its owner or its owner's sibling parts.
            f.names("part") shouldBe listOf("er.entity.A.id")
        }

        "tagged relations and keys pull only endpoints and their parts never untagged neighbours" {
            val f = ScopeFixture()
            f.names("edge") shouldBe
                listOf("er.entity.A", "er.entity.A.id", "er.entity.B", "er.entity.B.id", "er.relation.AB")
            f.names("key") shouldBe listOf("db.dbo.T", "db.dbo.T.id", "db.dbo.U", "db.dbo.U.id", "db.dbo.fk")
            f.names("table") shouldBe listOf("db.dbo.T", "db.dbo.T.id")
        }

        "requirements warn without adding dependencies and strict RPC refuses incomplete scopes" {
            val f = ScopeFixture()
            val scope = TagScope.resolve(f.model, setOf("a"))
            scope.warnings.map { it.code } shouldBe listOf("meta/scope-violation")
            scope.warnings.single().message shouldBe "er.entity.A requires db.dbo.T outside the tag scope"
            val registry = f.registry()
            val service = MetadataServiceImpl(registry, packageIndex = PackageIndex())
            val ordinary = service.resolveTags(request("a"))
            ordinary.objectsList.map { it.qualifiedName.name } shouldBe listOf("A", "A.id")
            ordinary.complete shouldBe false
            ordinary.warningsList.single().code shouldBe "meta/scope-violation"
            service.resolveTags(request("a").toBuilder().setStrict(true).build()).objectsCount shouldBe 0
            val complete = service.resolveTags(request("a", "table").toBuilder().setStrict(true).build())
            complete.complete shouldBe true
            complete.objectsCount shouldBe 4
            registry.read()!!.model shouldBe f.model
            val notReady =
                MetadataServiceImpl(
                    MetadataRegistry(),
                    packageIndex = PackageIndex(),
                ).resolveTags(request("a"))
            notReady.complete shouldBe false
            notReady.messagesList.single().code shouldBe "metadata_not_ready"
            shouldThrow<StatusRuntimeException> { service.resolveTags(request(" ")) }.status.code shouldBe
                Status.Code.INVALID_ARGUMENT
        }

        "query requirements use the captured model version and do not follow parsed reads into the scope" {
            val f = ScopeFixture()
            val state = QueryParseState()
            val registry = f.registry()
            val service = MetadataServiceImpl(registry, parseState = state, packageIndex = PackageIndex())
            state.reset("v1", f.model.queries.keys)
            service
                .resolveTags(request("query"))
                .warningsList
                .single()
                .code shouldBe "meta/scope-unavailable"
            val plan =
                PlanNode
                    .newBuilder()
                    .setTableScan(
                        TableScanNode.newBuilder().setTable(f.t.qname.toProto()),
                    ).build()
            state.set("v1", f.query.qname, ParseStatus.ParseSuccess(plan.toByteArray()))
            val missing = service.resolveTags(request("query"))
            missing.objectsList.map { it.qualifiedName.name } shouldBe listOf("Q")
            missing.warningsList.single().humanMessage shouldBe "db.query.Q requires db.dbo.T outside the tag scope"
            service.resolveTags(request("query", "table")).complete shouldBe true
            state.reset("v2", f.model.queries.keys)
            state.set("v1", f.query.qname, ParseStatus.ParseSuccess(plan.toByteArray()))
            service
                .resolveTags(request("query"))
                .warningsList
                .single()
                .code shouldBe "meta/scope-unavailable"
        }

        "mapped generated filter queries are contained but their underlying table remains a requirement" {
            val f = ScopeFixture()
            val filter = f.query.copy(qname = db("A__filter", "query"), internalId = "filter", tags = emptyList())
            val model =
                f.model.copy(
                    mappings = listOf(f.mapping.copy(target = MappingTarget.SqlQuery(filter.qname))),
                    queries =
                        mapOf(
                            filter.qname to filter,
                        ),
                )
            val scope = TagScope.resolve(model, setOf("a"))
            scope.objects.map { it.qname.dotted() } shouldBe
                listOf("db.query.A__filter", "er.entity.A", "er.entity.A.id")
            scope.warnings.map { it.code } shouldBe listOf("meta/scope-unavailable")
        }

        "HTTP vocabulary uses RPC selection and only authored aliases and search flags with safe input handling" {
            val f = ScopeFixture()
            val registry = f.registry()
            testApplication {
                application { routing { vocabularyRoutes(registry) } }
                val response = client.get("/model/vocabulary?tags=a&tags=table")
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["model_version"]!!.jsonPrimitive.content shouldBe "v1"
                body["warnings"]!!.jsonArray.size shouldBe 0
                val entities = body["entities"]!!.jsonArray
                entities.size shouldBe 1
                val entity = entities.single().jsonObject
                entity["qname"]!!.jsonPrimitive.content shouldBe "er.entity.A"
                entity["aliases"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("Alpha", "First")
                val attr = entity["attributes"]!!.jsonArray.single().jsonObject
                attr["aliases"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("identifier")
                attr["indexed"]!!.jsonPrimitive.boolean shouldBe true
                attr["fuzzy"]!!.jsonPrimitive.boolean shouldBe true
                attr["match_method"]!!.jsonPrimitive.content shouldBe "TOKENS"
                Json
                    .parseToJsonElement(
                        client.get("/model/vocabulary?tags=unknown").bodyAsText(),
                    ).jsonObject["entities"]!!
                    .jsonArray.size shouldBe
                    0
                Json
                    .parseToJsonElement(
                        client.get("/model/vocabulary").bodyAsText(),
                    ).jsonObject["entities"]!!
                    .jsonArray.size shouldBe
                    3
                client.get("/model/vocabulary?tags=").status shouldBe HttpStatusCode.BadRequest
                client.get("/model/vocabulary?tags=a,,b").status shouldBe HttpStatusCode.BadRequest
                client.get("/model/vocabulary?agent=crm").status shouldBe HttpStatusCode.BadRequest
            }
            testApplication {
                application { routing { vocabularyRoutes(MetadataRegistry()) } }
                client.get("/model/vocabulary").status shouldBe HttpStatusCode.ServiceUnavailable
            }
        }

        "same-path files from independent sources retain each object's accepted package" {
            fun source(
                id: String,
                pkg: String,
                name: String,
            ): FileBasedSource {
                val storage =
                    object : ModelStorage {
                        override val id = id

                        override fun fetchVersion() = id

                        override fun listFiles(
                            extensions: List<String>,
                            prefixes: List<String>,
                        ): List<StorageFile> = listOf(StorageFile("/shared/er.ttrm", 1, Path.of("/shared")))

                        override fun read(file: StorageFile) =
                            "package $pkg\nmodel er\ndef entity $name { tags: [\"scope\"], attributes: [def attribute id { type: int }] }"
                    }
                return FileBasedSource(id, 100, storage)
            }
            val snapshots = listOf(source("one", "alpha", "A").load(), source("two", "beta", "B").load())
            val result = ModelReconciler(ModelDescriptor("scope", "scope", "scope")).reconcile(snapshots)
            val registry =
                MetadataRegistry().apply {
                    swap(result.model, ModelGraph.build(result.model), files = snapshots.flatMap { it.loadedFiles })
                }
            val resolved = MetadataServiceImpl(registry, packageIndex = PackageIndex()).resolveTags(request("scope"))
            resolved.packagesList shouldBe listOf("alpha", "beta")
            resolved.objectsList.associate { it.qualifiedName.name to it.packageName } shouldBe
                mapOf("A" to "alpha", "A.id" to "alpha", "B" to "beta", "B.id" to "beta")
        }

        "real guarded publication carries structural scope warnings and retains scoped vocabulary after rejection" {
            val root = Files.createTempDirectory("tag-scope")
            try {
                val file = root.resolve("er.ttrm")
                Files.writeString(
                    file,
                    "package sample\nmodel er\ndef entity A { tags: [\"a\"], binding: { target: { table: db.dbo.T } }, attributes: [def attribute id { type: int }] }",
                )
                Files.writeString(
                    root.resolve("db.ttrm"),
                    "package sample\nmodel db\ndef table T { columns: [def column id { type: int }] }",
                )
                val source = FileBasedSource("content", 100, LocalFsStorage("content", root))
                val registry = MetadataRegistry()
                val index = PackageIndex().commitOnSwap(registry)
                val refresher =
                    GuardedRefresher(
                        listOf(source),
                        listOf("content"),
                        ModelReconciler(ModelDescriptor("scope", "scope", "scope")),
                        registry,
                        true,
                        setOf("content"),
                        packageIndex = index,
                    )
                refresher.refresh("", true).single().success shouldBe true
                val accepted = registry.read()!!
                accepted.warnings.any { it.message.contains("meta/scope-violation") } shouldBe true
                val service = MetadataServiceImpl(registry, packageIndex = index)
                service.resolveTags(request("a")).packagesList shouldBe listOf("sample")
                Files.writeString(file, "def entity !!! broken")
                refresher.refresh("", true).single().success shouldBe false
                (registry.read() === accepted) shouldBe true
                testApplication {
                    application { routing { vocabularyRoutes(registry) } }
                    val body = Json.parseToJsonElement(client.get("/model/vocabulary?tags=a").bodyAsText()).jsonObject
                    body["entities"]!!
                        .jsonArray
                        .single()
                        .jsonObject["name"]!!
                        .jsonPrimitive.content shouldBe "A"
                    body["model_version"]!!.jsonPrimitive.content shouldBe accepted.model.version.value
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    })

private fun request(vararg tags: String) = ResolveTagsRequest.newBuilder().addAllTags(tags.toList()).build()

private fun er(
    name: String,
    ns: String = "entity",
) = QualifiedName(SchemaCode.ER, ns, name)

private fun db(
    name: String,
    ns: String = "dbo",
) = QualifiedName(SchemaCode.DB, ns, name)

private class ScopeFixture {
    val aId =
        Attribute(
            "a.id",
            er("A.id"),
            entity = er("A"),
            type = "int",
            tags = listOf("part"),
            search = SearchHints(indexed = true, matchMethod = "TOKENS", aliases = listOf("identifier")),
        )
    val a =
        Entity(
            "a",
            er("A"),
            tags = listOf("a"),
            attributes = listOf(aId),
            aliases = listOf("Alpha"),
            search = SearchHints(aliases = listOf("First", "Alpha")),
        )
    val b =
        Entity(
            "b",
            er("B"),
            tags = listOf("b"),
            attributes = listOf(Attribute("b.id", er("B.id"), entity = er("B"), type = "int")),
        )
    val c = Entity("c", er("C"))
    val ab =
        Relation(
            "ab",
            er("AB", "relation"),
            tags = listOf("edge"),
            fromEntity = a.qname,
            toEntity = b.qname,
            cardinality = Cardinality(0, -1, 0, -1),
        )
    val bc =
        ab.copy(
            internalId = "bc",
            qname = er("BC", "relation"),
            tags = emptyList(),
            fromEntity = b.qname,
            toEntity = c.qname,
        )
    val t =
        DbTable(
            "t",
            db("T"),
            tags = listOf("table"),
            columns = listOf(DbColumn("t.id", db("T.id"), table = db("T"), dataType = "int")),
        )
    val u =
        t.copy(
            internalId = "u",
            qname = db("U"),
            tags = emptyList(),
            columns = listOf(DbColumn("u.id", db("U.id"), table = db("U"), dataType = "int")),
        )
    val key =
        DbForeignKey(
            "fk",
            db("fk"),
            tags = listOf("key"),
            fromColumns = listOf(t.columns.single().qname),
            toColumns = listOf(u.columns.single().qname),
        )
    val query =
        Query("q", db("Q", "query"), tags = listOf("query"), sourceLanguage = "sql", sourceText = "select * from T")
    val mapping =
        Er2DbEntityMapping(
            "map",
            QualifiedName(SchemaCode.UNSPECIFIED, "er2db", "A"),
            entity = a.qname,
            target = MappingTarget.Table(t.qname),
        )
    val model =
        Model(
            ModelDescriptor("scope", "scope", "scope"),
            ModelVersion("v1", Instant.EPOCH),
            mapOf(
                "er" to ErSchema(listOf(a, b, c).associateBy { it.qname }, listOf(ab, bc).associateBy { it.qname }),
                "db" to DbSchema(tables = listOf(t, u).associateBy { it.qname }, foreignKeys = mapOf(key.qname to key)),
            ),
            listOf(mapping),
            mapOf(
                query.qname to query,
            ),
        )

    fun names(vararg tags: String) = TagScope.resolve(model, tags.toSet()).objects.map { it.qname.dotted() }

    fun registry() = MetadataRegistry().apply { swap(model, ModelGraph.build(model)) }
}
