// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.scope

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.tatrman.meta.v1.GetSnapshotRequest
import org.tatrman.meta.v1.ObjectEntry
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
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
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

        "a bare entity can bind to a package-qualified tagged table without warnings" {
            val fixture = loadBinding(entityRef = "er.entity.ActiveSale")
            fixture.scope().warnings.shouldBeEmpty()
            TagScope.loadWarnings(fixture.model).shouldBeEmpty()
        }

        "a packaged binding to an untagged table warns at selection and load" {
            val fixture = loadBinding(tagTable = false)
            val scope = fixture.scope()
            scope.objects.map { it.qname.name } shouldBe listOf("ActiveSale")
            scope.warnings.map { it.code } shouldBe listOf("meta/scope-violation")
            TagScope.loadWarnings(fixture.model).single().message.contains("meta/scope-violation") shouldBe true
        }
    })

private class BindingFixture(
    private val registry: MetadataRegistry,
    private val packages: PackageIndex,
) {
    val model get() = registry.read()!!.model

    suspend fun snapshot() =
        MetadataServiceImpl(registry, packageIndex = packages)
            .getSnapshot(GetSnapshotRequest.getDefaultInstance())
            .snapshot

    fun scope() = TagScope.resolve(model, setOf("scope"), checkQueries = false)
}

private fun loadBinding(
    entityRef: String = "demo.reporting.er.entity.ActiveSale",
    filtered: Boolean = false,
    tagTable: Boolean = true,
    extraTable: Boolean = false,
): BindingFixture {
    val root = Files.createTempDirectory("packaged-yaml-binding")
    try {
        fun write(path: String, content: String) {
            root.resolve(path).also { it.parent.createDirectories() }.writeText(content.trimIndent())
        }

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
            defs: [{kind: entity, id: ActiveSale, properties: {tags: [scope]}}]
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
        val source = FilteredBindingSource(YamlKindSource("fixture", 100, LocalFsStorage("fixture", root), null)).load()
        source.errors.shouldBeEmpty()
        val loaded = ModelReconciler(ModelDescriptor("fixture", "fixture", "fixture")).reconcile(listOf(source))
        loaded.errors.shouldBeEmpty()
        val packages = PackageIndex().apply {
            record(source)
            commit()
        }
        val registry = MetadataRegistry().apply {
            swap(loaded.model, ModelGraph.build(loaded.model), files = loaded.files)
        }
        return BindingFixture(registry, packages)
    } finally {
        root.toFile().deleteRecursively()
    }
}
