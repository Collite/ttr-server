// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.scope

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.tatrman.meta.v1.GetSnapshotRequest
import org.tatrman.meta.v1.ObjectEntry
import org.tatrman.meta.v1.ResolveTagsRequest
import org.tatrman.metadata.grpc.MetadataServiceImpl
import org.tatrman.metadata.grpc.PackageIndex
import org.tatrman.metadata.read.FilteredBindingSource
import org.tatrman.metadata.refresh.YamlKindSource
import org.tatrman.translate.snapshot.SnapshotModelHandle
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.LoadedFile
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class PackagedYamlBindingSpec :
    StringSpec({
        "a binding cannot borrow a same-name table from another package" {
            val fixture = loadBinding(extraTable = true)
            val snapshot = fixture.snapshot()
            val table = snapshot.objectsList.single { it.contentCase == ObjectEntry.ContentCase.TABLE }
            val entity = snapshot.objectsList.single { it.contentCase == ObjectEntry.ContentCase.ENTITY }
            table.objectDescriptor.packageName shouldBe "demo.reporting"
            SnapshotModelHandle.from(snapshot).entityMapping(entity.objectDescriptor.qualifiedName) shouldBe null
            fixture.scope().warnings.map { it.code } shouldBe listOf("meta/scope-violation")
        }

        "a filtered binding cannot hide a same-name target from another package" {
            val fixture = loadBinding(extraTable = true, filtered = true)
            val snapshot = fixture.snapshot()
            snapshot.objectsList.filter { it.contentCase == ObjectEntry.ContentCase.QUERY }.shouldBeEmpty()
            val entity = snapshot.objectsList.single { it.contentCase == ObjectEntry.ContentCase.ENTITY }
            SnapshotModelHandle.from(snapshot).entityMapping(entity.objectDescriptor.qualifiedName) shouldBe null
            val warning = fixture.scope().warnings.single()
            warning.code shouldBe "meta/scope-violation"
            warning.message.contains("demo.other.db.dbo.sale") shouldBe true
        }

        "a filtered binding collision warns at load even without tags" {
            val fixture = loadBinding(filtered = true, tagTable = false, extraTable = true, tagEntity = false)
            fixture.loadWarnings().shouldBeEmpty()
            val warning = fixture.sourceWarnings().single { "whereFilter is not applied" in it.message }
            warning.file.endsWith("/demo/reporting/binding.yaml") shouldBe true
            warning.line shouldBe 10
            warning.message.contains("package 'demo.reporting'") shouldBe true
            warning.message.contains("demo.other.db.dbo.sale") shouldBe true
        }

        "an exact table key wins when multiple declarations share the bare key" {
            val snapshot = loadBinding().snapshot()
            val table = snapshot.objectsList.single { it.contentCase == ObjectEntry.ContentCase.TABLE }
            val entity = snapshot.objectsList.single { it.contentCase == ObjectEntry.ContentCase.ENTITY }
            val bare = table.objectDescriptor.qualifiedName
            val qualified = bare.toBuilder().setPackage("demo.reporting").build()
            val collision =
                table
                    .toBuilder()
                    .setObjectDescriptor(table.objectDescriptor.toBuilder().setPackageName("demo.other"))
                    .build()
            val ambiguous = snapshot.toBuilder().addObjects(collision)
            SnapshotModelHandle.from(ambiguous.build()).entityMapping(entity.objectDescriptor.qualifiedName) shouldBe
                null

            val exact =
                table
                    .toBuilder()
                    .setObjectDescriptor(table.objectDescriptor.toBuilder().setQualifiedName(qualified))
                    .build()
            SnapshotModelHandle
                .from(ambiguous.addObjects(exact).build())
                .entityMapping(entity.objectDescriptor.qualifiedName) shouldBe EntityMapping.ToTable(qualified)
        }

        "a packaged table binding is used in the snapshot instead of placement" {
            val snapshot = loadBinding().snapshot()
            val entity = snapshot.objectsList.single { it.contentCase == ObjectEntry.ContentCase.ENTITY }
            val table = snapshot.objectsList.single { it.contentCase == ObjectEntry.ContentCase.TABLE }
            SnapshotModelHandle
                .from(snapshot, placedEntities = setOf("ActiveSale"))
                .entityMapping(entity.objectDescriptor.qualifiedName) shouldBe
                EntityMapping.ToTable(table.objectDescriptor.qualifiedName)
        }

        "a packaged filtered binding maps to the generated query and selects it in tag scope" {
            val fixture = loadBinding(filtered = true)
            val snapshot = fixture.snapshot()
            val entity = snapshot.objectsList.single { it.contentCase == ObjectEntry.ContentCase.ENTITY }
            val query = snapshot.objectsList.single { it.contentCase == ObjectEntry.ContentCase.QUERY }
            query.objectDescriptor.qualifiedName.name shouldBe "ActiveSale__filter"
            query.objectDescriptor.qualifiedName.`package` shouldBe "demo.reporting"
            SnapshotModelHandle.from(snapshot).entityMapping(entity.objectDescriptor.qualifiedName) shouldBe
                EntityMapping.ToQuery(query.objectDescriptor.qualifiedName)
            val scope = fixture.scope()
            scope.objects.map { it.qname.name }.toSet() shouldBe setOf("ActiveSale", "sale", "ActiveSale__filter")
            scope.warnings.shouldBeEmpty()
        }

        "dialect fan-out retains package provenance for generated table and entity declarations" {
            val snapshot = loadDialect().snapshot()
            val entity = snapshot.objectsList.single { it.contentCase == ObjectEntry.ContentCase.ENTITY }
            val table = snapshot.objectsList.single { it.contentCase == ObjectEntry.ContentCase.TABLE }
            entity.objectDescriptor.packageName shouldBe "demo.reporting"
            table.objectDescriptor.packageName shouldBe "demo.reporting"
            SnapshotModelHandle.from(snapshot).entityMapping(entity.objectDescriptor.qualifiedName) shouldBe
                EntityMapping.ToTable(table.objectDescriptor.qualifiedName)
        }

        "a bare entity can bind to a package-qualified tagged table without warnings" {
            val fixture = loadBinding(entityRef = "er.entity.ActiveSale")
            fixture.scope().warnings.shouldBeEmpty()
            fixture.loadWarnings().shouldBeEmpty()
            fixture.resolveTags().complete shouldBe true
        }

        "a packaged binding to an untagged table warns at selection and load" {
            val fixture = loadBinding(tagTable = false)
            val scope = fixture.scope()
            scope.objects.map { it.qname.name } shouldBe listOf("ActiveSale")
            scope.warnings.map { it.code } shouldBe listOf("meta/scope-violation")
            fixture
                .loadWarnings()
                .single()
                .message
                .contains("meta/scope-violation") shouldBe true
            val strict = fixture.resolveTags()
            strict.complete shouldBe false
            strict.objectsList.shouldBeEmpty()
        }
    })

private class BindingFixture(
    private val registry: MetadataRegistry,
    private val packages: PackageIndex,
    private val files: List<LoadedFile>,
) {
    fun sourceWarnings() = registry.read()!!.warnings

    fun loadWarnings() = TagScope.loadWarnings(registry.read()!!.model, bindingPackages(files))

    private val service = MetadataServiceImpl(registry, packageIndex = packages)

    suspend fun snapshot() = service.getSnapshot(GetSnapshotRequest.getDefaultInstance()).snapshot

    suspend fun resolveTags() =
        service.resolveTags(
            ResolveTagsRequest
                .newBuilder()
                .addTags("scope")
                .setStrict(true)
                .build(),
        )

    fun scope() = TagScope.resolve(registry.read()!!, setOf("scope"), checkQueries = false)
}

private fun loadBinding(
    entityRef: String = "demo.reporting.er.entity.ActiveSale",
    filtered: Boolean = false,
    tagTable: Boolean = true,
    extraTable: Boolean = false,
    tagEntity: Boolean = true,
): BindingFixture =
    loadYaml {
        write(
            "demo/reporting/db.yaml",
            """
            ttr: "0.22"
            package: demo.reporting
            model: db
            defs: [{kind: table, id: sale, properties: {tags: [${if (tagTable) "scope" else ""}]}}]
            """,
        )
        if (extraTable) {
            write(
                "demo/other/db.yaml",
                """
                ttr: "0.22"
                package: demo.other
                model: db
                defs: [{kind: table, id: sale, properties: {}}]
                """,
            )
        }
        write(
            "demo/reporting/er.yaml",
            """
            ttr: "0.22"
            package: demo.reporting
            model: er
            defs: [{kind: entity, id: ActiveSale, properties: {tags: [${if (tagEntity) "scope" else ""}]}}]
            """,
        )
        val targetPackage = if (extraTable) "demo.other" else "demo.reporting"
        write(
            "demo/reporting/binding.yaml",
            """
            ttr: "0.22"
            package: demo.reporting
            model: binding
            defs:
              - kind: er2db_entity
                id: ActiveSale
                properties:
                  entity: $entityRef
                  target: {table: "$targetPackage.db.dbo.sale"}
                  ${if (filtered) "whereFilter: {sql: 'id = 1'}" else ""}
            """,
        )
    }

private fun loadDialect(): BindingFixture =
    loadYaml {
        write("ttr.yaml", "profile: ./example.yaml\ngrammar: '>=0.22 <1.0'\n")
        write("model.ignore", "example.yaml\n")
        write(
            "example.yaml",
            """
        profile: example/1
        grammar: '>=0.22 <1.0'
        operations:
          - {op: package, mapping: {}, default: demo.reporting}
          - {op: kinds, at: /tables, mapping: {tables: table}}
          - {op: kinds, at: /sales, mapping: {sales: entity}}
          - {op: kinds, at: /sales, mapping: {sales: er2db_entity}}
          - {op: link, in: er2db_entity, at: /${'$'}key, to: {kind: er2db_entity, property: entity}, by: sourceName}
          - {op: link, in: er2db_entity, at: /input/from, to: {kind: er2db_entity, path: [target, table]}, by: sourceName}
        """,
        )
        write(
            "demo/reporting/sales.yaml",
            """
        tables: {sale: {}}
        sales: {ActiveSale: {input: {from: sale}}}
        """,
        )
    }

private fun loadYaml(fixture: FixtureFiles.() -> Unit): BindingFixture {
    val root = Files.createTempDirectory("packaged-yaml-binding")
    try {
        FixtureFiles(root).fixture()
        val source = FilteredBindingSource(YamlKindSource("fixture", 100, LocalFsStorage("fixture", root), null)).load()
        source.errors.shouldBeEmpty()
        val loaded = ModelReconciler(ModelDescriptor("fixture", "fixture", "fixture")).reconcile(listOf(source))
        loaded.errors.shouldBeEmpty()
        val packages =
            PackageIndex().apply {
                record(source)
                commit()
            }
        val registry =
            MetadataRegistry().apply {
                swap(loaded.model, ModelGraph.build(loaded.model), loaded.warnings, files = loaded.files)
            }
        return BindingFixture(registry, packages, loaded.files)
    } finally {
        root.toFile().deleteRecursively()
    }
}

private class FixtureFiles(
    private val root: Path,
) {
    fun write(
        path: String,
        content: String,
    ) {
        root.resolve(path).also { it.parent.createDirectories() }.writeText(content.trimIndent())
    }
}
