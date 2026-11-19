// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.jgit.api.Git
import org.tatrman.meta.v1.GetStatusRequest
import org.tatrman.metadata.buildSources
import org.tatrman.metadata.promotionTarget
import org.tatrman.metadata.export.metadataExportRoutes
import org.tatrman.metadata.grpc.MetadataServiceImpl
import org.tatrman.metadata.grpc.PackageIndex
import org.tatrman.metadata.health.SourceLoadBoard
import org.tatrman.metadata.health.modelHealthRoutes
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.yaml.CanonicalYaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * MW S4.2 T1 (MW-P4 contracts §1, ✅D-MW-60) — the service's `kind: yaml` source: canonical and dialect trees, the
 * `profile` / `METADATA_PROFILE` rule, profile errors under both swap policies, the status fields. The promotion
 * guard is [org.tatrman.metadata.cn.PromotionGuardSpec]'s.
 */
class YamlSourceKindSpec :
    StringSpec({
        "a git yaml source serves a canonical tree as the same snapshot as its .ttrm twin" {
            val ttrm = Files.createTempDirectory("yaml-kind-ttrm")
            val repo = Files.createTempDirectory("yaml-kind-remote")
            val checkout = Files.createTempDirectory("yaml-kind-checkout")
            try {
                copyTree(MV_ROOT, ttrm)
                canonicalTwin(MV_ROOT, repo.resolve("model"))
                commitAll(repo)
                val canonical =
                    Fixture(listOf(git("canon", repo, checkout) + mapOf("kind" to "yaml")), strict = true)
                val twin = Fixture(listOf(mapOf("id" to "canon", "type" to "filesystem", "path" to ttrm.toString())))
                canonical.ref.refreshOutcome("", true).status shouldBe "ok"
                twin.ref.refreshOutcome("", true).status shouldBe "ok"
                // The ETag is the model version: the source versions (a commit, a file hash), never the content, so
                // two trees differ in it by construction. The export is equal once each side's version is masked.
                val (yamlTag, yamlBody) = canonical.export()
                val (ttrmTag, ttrmBody) = twin.export()
                yamlTag shouldBe canonical.version()
                ttrmTag shouldBe twin.version()
                yamlBody.replace(yamlTag!!, "<snapshot>") shouldBe ttrmBody.replace(ttrmTag!!, "<snapshot>")
                canonical.origin("canon").kind shouldBe "yaml"
                canonical.origin("canon").profile shouldBe CANONICAL_PROFILE
            } finally {
                listOf(ttrm, repo, checkout).forEach { it.toFile().deleteRecursively() }
            }
        }

        "a dialect tree loads through its ttr.yaml; METADATA_PROFILE applies only without one" {
            val repo = Files.createTempDirectory("yaml-kind-dialect")
            val checkout = Files.createTempDirectory("yaml-kind-dialect-checkout")
            val bare = Files.createTempDirectory("yaml-kind-undeclared")
            try {
                dialectTree(repo.resolve("model"), declared = true)
                commitAll(repo)
                // The catalog is read from the checkout the load fetched: the git tree is not on disk before it.
                val declared = Fixture(listOf(git("shop", repo, checkout) + mapOf("kind" to "yaml")))
                declared.ref.refreshOutcome("", true).status shouldBe "ok"
                declared.tables() shouldBe setOf("Warehouse")
                declared.origin("shop").profile shouldBe "shop/1"

                dialectTree(bare, declared = false)
                val configured =
                    mapOf(
                        "id" to "shop",
                        "type" to "filesystem",
                        "path" to bare.toString(),
                        "kind" to "yaml",
                    )
                val viaEnv = Fixture(listOf(configured + mapOf("profile" to "shop/1")))
                viaEnv.ref.refreshOutcome("", true).status shouldBe "ok"
                viaEnv.tables() shouldBe setOf("Warehouse")
                viaEnv.origin("shop").profile shouldBe "shop/1"
                // Without either, the dialect file is not canonical YAML: nothing is served from it.
                Fixture(listOf(configured), strict = true).ref.refreshOutcome("", true).status shouldBe "failed"

                // Both: a filesystem tree fails at startup, a git tree fails its load. Neither silently wins.
                Files.writeString(bare.resolve(TREE_DECLARATION), "profile: shop/1\ngrammar: '>=0.22 <1.0'\n")
                shouldThrow<IllegalArgumentException> {
                    Fixture(listOf(configured + mapOf("profile" to "shop/1")))
                }.message shouldContain YamlKindSource.PROFILE_CONFLICT
                val both = Fixture(listOf(git("shop", repo, checkout) + mapOf("kind" to "yaml", "profile" to "shop/1")))
                both.ref.refreshOutcome("", true).status shouldBe "failed"
                both.board
                    .states()
                    .single()
                    .lastError shouldContain YamlKindSource.PROFILE_CONFLICT

                // A profile needs kind yaml and stays inside the source root.
                val entry = mapOf("id" to "m", "type" to "filesystem", "path" to "/nonexistent")
                shouldThrow<IllegalArgumentException> { Fixture(listOf(entry + mapOf("profile" to "shop/1"))) }
                for (bad in listOf("../outside.yaml", "/etc/profile.yaml")) {
                    shouldThrow<IllegalArgumentException> {
                        Fixture(listOf(entry + mapOf("kind" to "yaml", "profile" to bad)))
                    }.message shouldContain "invalid_source_config"
                }
            } finally {
                listOf(repo, checkout, bare).forEach { it.toFile().deleteRecursively() }
            }
        }

        "a profile error on one file: non-strict serves the rest with the located issue, strict keeps the last good" {
            val tree = Files.createTempDirectory("yaml-kind-broken")
            try {
                dialectTree(tree, declared = true)
                val entry = mapOf("id" to "shop", "type" to "filesystem", "path" to tree.toString(), "kind" to "yaml")
                val strict = Fixture(listOf(entry), strict = true)
                strict.ref.refreshOutcome("", true).status shouldBe "ok"
                val good = strict.registry.read()
                Files.writeString(tree.resolve("broken.yaml"), "tabulka:\n  Broken: {nesmysl: 1}\n")
                strict.ref.refreshOutcome("", true).status shouldBe "failed"
                (strict.registry.read() === good) shouldBe true
                strict.board
                    .states()
                    .single()
                    .issues
                    .joinToString() shouldContain "ys/unknown-key"
                strict.board
                    .states()
                    .single()
                    .issues
                    .joinToString() shouldContain "broken.yaml:2:12:"

                val lenient = Fixture(listOf(entry), strict = false)
                lenient.ref.refreshOutcome("", true).status shouldBe "ok"
                lenient.tables() shouldBe setOf("Warehouse")
                val issues = lenient.statusJson()["issues"]!!.jsonArray.map { it.jsonPrimitive.content }
                // T3: the profile engine's code, located file:line:col.
                withClue(issues) {
                    issues.any { it.contains("ys/") && Regex("""broken\.yaml:2:\d+""").containsMatchIn(it) } shouldBe
                        true
                }
            } finally {
                tree.toFile().deleteRecursively()
            }
        }

        "/status sources[] and GetStatus.sources carry kind and profile" {
            val tree = Files.createTempDirectory("yaml-kind-status")
            val ttrm = Files.createTempDirectory("yaml-kind-status-ttrm")
            try {
                dialectTree(tree, declared = true)
                // Declared by path: reported as the catalog identity the path names (name/major).
                Files.writeString(
                    tree.resolve(TREE_DECLARATION),
                    "profile: ./profiles/shop.yaml\ngrammar: '>=0.22 <1.0'\n",
                )
                Files.writeString(ttrm.resolve("db.ttrm"), "model db\ndef table other {}")
                val f =
                    Fixture(
                        listOf(
                            mapOf("id" to "shop", "type" to "filesystem", "path" to tree.toString(), "kind" to "yaml"),
                            mapOf("id" to "plain", "type" to "filesystem", "path" to ttrm.toString()),
                        ),
                    )
                f.ref.refreshOutcome("", true).status shouldBe "ok"
                val rpc =
                    MetadataServiceImpl(f.registry, packageIndex = f.index, loadBoard = f.board, refresher = f.ref)
                        .getStatus(GetStatusRequest.getDefaultInstance())
                promotionTarget(f.config, "shop")!!.let { it.kind to it.profile } shouldBe ("yaml" to null)
                rpc.sourcesList.associate { it.id to (it.kind to it.profile) } shouldBe
                    mapOf("shop" to ("yaml" to "shop/1"), "plain" to ("ttr" to ""))
                f.statusJson()["sources"]!!.jsonArray.associate {
                    val o = it.jsonObject
                    o["id"]!!.jsonPrimitive.content to
                        (o["kind"]!!.jsonPrimitive.content to o["profile"]!!.jsonPrimitive.content)
                } shouldBe mapOf("shop" to ("yaml" to "shop/1"), "plain" to ("ttr" to ""))
            } finally {
                listOf(tree, ttrm).forEach { it.toFile().deleteRecursively() }
            }
        }

        "a tree read through a profile over metadata.yaml.maxInputCodePoints fails its load; canonical is not counted" {
            val dialect = Files.createTempDirectory("yaml-kind-budget")
            val canonical = Files.createTempDirectory("yaml-kind-budget-canonical")
            try {
                dialectTree(dialect, declared = true)
                canonicalTwin(MV_ROOT, canonical)

                fun fixture(
                    root: Path,
                    limit: Int,
                ) = Fixture(
                    listOf(mapOf("id" to "shop", "type" to "filesystem", "path" to "$root", "kind" to "yaml")),
                    extra = mapOf("metadata.yaml.maxInputCodePoints" to limit),
                )
                // sklad.yaml is the one input (69 code points): profiles/, model.ignore and ttr.yaml are not inputs.
                val over = fixture(dialect, 68)
                over.ref.refreshOutcome("", true).status shouldBe "failed"
                over.board
                    .states()
                    .single()
                    .lastError shouldContain "${YamlKindSource.INPUT_BUDGET}: source 'shop' reads more than 68"
                fixture(dialect, 69).ref.refreshOutcome("", true).status shouldBe "ok"
                // A canonical tree runs no engine: the same limit does not apply to it.
                fixture(canonical, 68).ref.refreshOutcome("", true).status shouldBe "ok"
                // 0 (the default) derives the limit from the heap: half of it at ~500 B per code point.
                YamlKindSource.inputBudget(0, 512L shl 20) shouldBe 536_870
                YamlKindSource.inputBudget(42, 512L shl 20) shouldBe 42
            } finally {
                listOf(dialect, canonical).forEach { it.toFile().deleteRecursively() }
            }
        }
    })

private val MV_ROOT: Path = Path.of("src/test/resources/fixture-member-vocab")

private fun copyTree(
    from: Path,
    to: Path,
) {
    Files.walk(from).use { paths ->
        paths.filter { it.isRegularFile() }.forEach { file ->
            val target = to.resolve(from.relativize(file).toString())
            Files.createDirectories(target.parent)
            Files.copy(file, target)
        }
    }
}

/** Every `.ttrm` of [from] as canonical YAML under [to], through the library's writer (the P2 path). */
private fun canonicalTwin(
    from: Path,
    to: Path,
) {
    Files.walk(from).use { paths ->
        paths.filter { it.isRegularFile() && it.extension == "ttrm" }.forEach { file ->
            val relative = from.relativize(file).toString().removeSuffix(".ttrm") + ".yaml"
            val target = to.resolve(relative)
            Files.createDirectories(target.parent)
            Files.writeString(target, CanonicalYaml.write(CanonicalYaml.fromTtr(Files.readString(file), relative)))
        }
    }
}

/** An invented dialect: `tabulka` records are tables, read through `shop/1` from the tree's own catalog. */
private fun dialectTree(
    root: Path,
    declared: Boolean,
) {
    Files.createDirectories(root.resolve("profiles"))
    Files.writeString(
        root.resolve("profiles/shop.yaml"),
        """
        profile: shop/1
        grammar: '>=0.22 <1.0'
        operations:
          - {op: package, mapping: {}, default: shop}
          - {op: kinds, at: /tabulka, mapping: {tabulka: table}}
          - {op: kinds, in: table, at: /sloupce, mapping: {sloupec: column}}
          - {op: rename, in: table, at: /popis, to: {kind: table, property: description}}
          - {op: rename, in: column, at: /typ, to: {kind: column, property: type}}
        """.trimIndent() + "\n",
    )
    Files.writeString(root.resolve("model.ignore"), "profiles/\n")
    Files.writeString(
        root.resolve("sklad.yaml"),
        "tabulka:\n  Warehouse: {popis: Sklad, sloupce: {code: {typ: string}}}\n",
    )
    if (declared) Files.writeString(root.resolve(TREE_DECLARATION), "profile: shop/1\ngrammar: '>=0.22 <1.0'\n")
}

private fun commitAll(repo: Path) {
    val git =
        Git
            .init()
            .setDirectory(repo.toFile())
            .setInitialBranch("main")
            .call()
    git.use {
        it.add().addFilepattern(".").call()
        it
            .commit()
            .setMessage("tree")
            .setAuthor("t", "t@example.invalid")
            .setCommitter("t", "t@example.invalid")
            .call()
    }
}

private fun git(
    id: String,
    repo: Path,
    checkout: Path,
): Map<String, Any> =
    mapOf(
        "id" to id,
        "type" to "git",
        "remote-uri" to repo.toUri().toString(),
        "branch" to "main",
        "subdirectory" to "model",
        "local-checkout-dir" to checkout.resolve(id).toString(),
    )

private class Fixture(
    entries: List<Map<String, Any>>,
    strict: Boolean = true,
    extra: Map<String, Any> = emptyMap(),
) {
    val config = ConfigFactory.parseMap(mapOf("metadata.sources" to entries) + extra)
    val registry = MetadataRegistry()
    val board = SourceLoadBoard(strictSwap = strict)
    val index = PackageIndex().commitOnSwap(registry)
    val slots = buildSources(config, board)
    val ref =
        GuardedRefresher(
            slots.map { it.source },
            slots.map { it.id },
            ModelReconciler(ModelDescriptor("yaml-kind", "yaml-kind")),
            registry,
            strict,
            board.states().map { it.id }.toSet(),
            board,
            index,
        )

    fun origin(id: String): SourceOrigin =
        ref
            .status()
            .sources
            .single { it.id == id }
            .origin

    fun tables(): Set<String> =
        registry
            .readOrError()
            .model
            .objectByQname()
            .values
            .filter { it.kind == "table" }
            .map { it.qname.name }
            .toSet()

    fun version(): String =
        registry
            .readOrError()
            .model.version.value

    /** `/model/export`'s ETag and body (`defs.json` of the whole world). */
    suspend fun export(): Pair<String?, String> {
        var result: Pair<String?, String>? = null
        testApplication {
            environment { config = MapApplicationConfig() }
            application { routing { metadataExportRoutes(registry) } }
            val response = client.get("/model/export?model=world&format=defs.json")
            result = response.headers[HttpHeaders.ETag] to response.body<String>()
        }
        return result!!
    }

    suspend fun statusJson() =
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
