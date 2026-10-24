// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import org.tatrman.automation.identity.AuthorMap
import org.tatrman.automation.identity.GrantDecl
import org.tatrman.automation.identity.IdentityDecl
import org.tatrman.automation.identity.Who
import org.tatrman.ttr.metadata.model.AutomationSchema
import org.tatrman.ttr.metadata.model.GrantWho
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.RunAsRef
import org.tatrman.ttr.metadata.model.World
import org.tatrman.ttr.metadata.model.WorldSchema
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LoadWarning
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import org.tatrman.automation.identity.RunAsRef as GrantRunAs

/**
 * `/refresh`'s load (AU-server S0.2 T2; contracts §1.1, D-47, D-62): a checked-out commit → the `ttr-metadata`
 * snapshot of `model/` + `automation/` (the generated `catalog/<recipe>.ttrm` of every recipe bundle rides the
 * automation root's walk) → one [LoadedRecipe] per `automation/<r>/<r>.bundle/manifest.json`, its islands read
 * into memory and checked against the manifest's sha256 → the [Bundle] value. Any refusal collects every finding
 * before it throws [BundleInvalidException] (`422 bundle-invalid { diagnostics }`), so one refresh names them all.
 *
 * **What refuses a commit:** a manifest that is not `schemaVersion` 3, carries no `recipe`, names another recipe
 * than its directory, or lists an island whose file is missing or whose sha256 differs; a deployment naming an
 * unknown program or a process its world does not declare, a program — its own or a step's of its process —
 * without a recipe bundle, or a target that is neither `native` nor an executor of its world; two worlds
 * declaring one deployment name; a commit without an author (no deployer, D-90); and every load error of the
 * automation half —
 * a file under `automation/`, a `model world` file, an automation-validator or `ttr/automation-*` / `ttr/states-*`
 * finding (a malformed grant or deployment is DROPPED by `ttr-metadata` and reported, so it refuses here: a
 * deployment must never vanish silently). The model's other issues are warnings: Veles serves that model and
 * owns its diagnostics. The package-layout codes are never findings — the hartland convention (one flat package
 * per area, `layout = "off"`) declares packages that differ from the directory by design.
 */
class BundleLoader(
    private val authorMap: AuthorMap,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun load(
        checkout: Checkout,
        kind: BundleKind,
    ): Bundle {
        val modelRoot = checkout.root.resolve(MODEL_DIR)
        val automationRoot = checkout.root.resolve(AUTOMATION_DIR)
        val findings = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (!automationRoot.isDirectory()) findings += "no `$AUTOMATION_DIR/` directory at ${checkout.commit.take(12)}"

        val snapshots =
            listOf(MODEL_DIR to modelRoot, AUTOMATION_DIR to automationRoot)
                .filter { (_, root) -> root.isDirectory() }
                .map { (id, root) -> FileBasedSource(id, 100, LocalFsStorage(id, root)).load() }
        val result = ModelReconciler(ModelDescriptor(id = "automation", name = "automation")).reconcile(snapshots)
        val worldFiles =
            snapshots
                .flatMap {
                    it.loadedFiles
                }.filter { it.schemaCode == "world" }
                .map { it.storageFile.path }
        for (e in snapshots.flatMap { it.errors } + result.errors) {
            if (LAYOUT_CODES.any { e.message.startsWith(it) }) continue
            if (isAutomationFinding(e, automationRoot, worldFiles)) {
                findings += render(e, checkout.root)
            } else {
                warnings +=
                    render(e, checkout.root)
            }
        }
        val model = result.model

        val automation = model.schemas["automation"] as? AutomationSchema ?: AutomationSchema()
        val worlds = (model.schemas["world"] as? WorldSchema)?.worlds?.values.orEmpty()
        val programs = automation.programs.associateBy { it.qname.name }
        val actions = automation.actions.associateBy { it.qname.name }
        val recipes = loadRecipes(automationRoot, findings)

        val deployments = linkedMapOf<String, org.tatrman.ttr.metadata.model.WorldDeployment>()
        val deploymentWorlds = linkedMapOf<String, String>()
        val scopes = linkedMapOf<String, WorldScope>()
        for (w in worlds) {
            val scope = scope(w)
            scopes[scope.name] = scope
            for (d in w.deployments) {
                val name = d.qname.name
                if (deployments.put(name, d) != null) findings += "deployment $name: declared by two worlds"
                deploymentWorlds[name] = scope.name
                checkDeployment(d, w, scope, recipes.keys, programs, findings)
            }
        }
        for (p in programs.values) {
            val recipe = p.recipe
            if (recipe != null &&
                recipe !in recipes
            ) {
                warnings += "program ${p.qname.name}: recipe $recipe has no bundle yet"
            }
        }

        val author =
            try {
                authorMap.principalOf(checkout.authorEmail, checkout.authorName)
            } catch (e: IllegalArgumentException) {
                findings += "commit ${checkout.commit.take(12)} has no author (name or e-mail): no deployer (D-90)"
                null
            }
        if (findings.isNotEmpty() || author == null) throw BundleInvalidException(findings)
        return Bundle(
            id = "${kind.wire}:${checkout.branch}@${checkout.commit.take(12)}",
            kind = kind,
            branch = checkout.branch,
            commit = checkout.commit,
            author = author,
            modelFp = modelFingerprint(modelRoot),
            loadedAt = clock.instant(),
            model = model,
            recipes = recipes,
            programs = programs,
            actions = actions,
            deployments = deployments,
            deploymentWorlds = deploymentWorlds,
            worlds = scopes,
            programFps = programs.mapValues { Fingerprints.program(it.value) },
            deploymentFps = deployments.mapValues { Fingerprints.deployment(it.value) },
            warnings = warnings,
        ).also {
            log.info(
                "automation: loaded {} — {} recipe(s), {} program(s), {} deployment(s), {} warning(s)",
                it.id,
                it.recipes.size,
                it.programs.size,
                it.deployments.size,
                warnings.size,
            )
        }
    }

    private fun checkDeployment(
        d: org.tatrman.ttr.metadata.model.WorldDeployment,
        world: World,
        scope: WorldScope,
        recipeNames: Set<String>,
        programs: Map<String, org.tatrman.ttr.metadata.model.AutomationProgram>,
        findings: MutableList<String>,
    ) {
        val name = d.qname.name

        // A program the deployment runs — its own, or a step's — must be declared and have its recipe's bundle.
        fun runnable(
            p: String,
            via: String,
        ) {
            if (p !in programs) {
                findings += "deployment $name: ${via}program $p is not declared"
                return
            }
            programs[p]?.recipe?.let { r ->
                if (r !in recipeNames) findings += "deployment $name: ${via}program $p's recipe $r has no bundle"
            }
        }
        d.program?.let { runnable(it, "") }
        d.process?.let { p ->
            val process = scope.processes[p]
            if (process == null) {
                findings += "deployment $name: process $p is not declared in world ${scope.name}"
            } else {
                process.steps.forEach { step -> step.program?.let { runnable(it, "process $p step ${step.name}: ") } }
            }
        }
        if (d.target != Bundle.NATIVE && world.executors.none { it.qname.name == d.target }) {
            findings +=
                "deployment $name: target ${d.target} is neither native nor an executor of world ${world.qname.name}"
        }
    }

    /** Every `automation/<r>/<r>.bundle/manifest.json`, checked and read. */
    private fun loadRecipes(
        automationRoot: Path,
        findings: MutableList<String>,
    ): Map<String, LoadedRecipe> {
        if (!automationRoot.isDirectory()) return emptyMap()
        val out = sortedMapOf<String, LoadedRecipe>()
        Files.list(automationRoot).use { dirs ->
            for (dir in dirs.sorted().toList()) {
                val bundleDir = dir.resolve("${dir.name}.bundle")
                if (!dir.isDirectory() || !bundleDir.isDirectory()) continue
                loadRecipe(dir.name, bundleDir, findings)?.let { out[it.name] = it }
            }
        }
        return out
    }

    private fun loadRecipe(
        name: String,
        bundleDir: Path,
        findings: MutableList<String>,
    ): LoadedRecipe? {
        val where = "bundle $name"
        val manifestFile = bundleDir.resolve("manifest.json")
        if (!Files.isRegularFile(manifestFile)) {
            findings += "$where: no manifest.json"
            return null
        }
        val manifest =
            try {
                RecipeManifest.parse(manifestFile.readText())
            } catch (e: SerializationException) {
                findings += "$where: manifest.json is not a manifest (${e.message?.lineSequence()?.first()})"
                return null
            } catch (e: IllegalArgumentException) {
                findings += "$where: manifest.json is not a manifest (${e.message?.lineSequence()?.first()})"
                return null
            }
        if (manifest.schemaVersion != RecipeManifest.SCHEMA_VERSION) {
            findings +=
                "$where: manifest schemaVersion ${manifest.schemaVersion}, this Server reads ${RecipeManifest.SCHEMA_VERSION}"
            return null
        }
        val recipe = manifest.recipe
        if (recipe == null) {
            findings += "$where: the manifest carries no `recipe` block"
            return null
        }
        if (recipe.name != name) findings += "$where: the manifest's recipe is ${recipe.name}"
        val statements = linkedMapOf<String, String>()
        val files = mutableListOf<Triple<String, String, String>>()
        for (island in manifest.islands) {
            if (island.outputs.isEmpty()) files += Triple(island.name, island.file, island.sha256)
            for (o in island.outputs) {
                files += Triple(island.name, o.file, o.sha256)
                o.combine?.let { c ->
                    files += Triple(island.name + " combine", c.file, c.sha256)
                    for (part in c.parts) {
                        if (recipe.parts.none { it.name == part }) {
                            findings += "$where: island ${island.name} — combine names unknown part $part"
                        }
                    }
                }
            }
        }
        for (p in recipe.parts) files += Triple("part ${p.name}", p.file, p.sha256)
        if (recipe.parts
                .map { it.name }
                .distinct()
                .size !=
            recipe.parts.size
        ) {
            findings += "$where: duplicate part names"
        }
        for ((owner, relative, hash) in files.distinct()) {
            val file = bundleDir.resolve(relative).normalize()
            if (!file.startsWith(bundleDir) || !Files.isRegularFile(file)) {
                findings += "$where: island $owner — $relative is missing"
                continue
            }
            val text = file.readText()
            if (hash.isNotEmpty() && Fingerprints.sha256(text) != hash) {
                findings += "$where: island $owner — $relative does not match its sha256"
                continue
            }
            statements[relative] = text
        }
        return LoadedRecipe(name, manifest, recipe, statements)
    }

    private fun isAutomationFinding(
        e: LoadWarning,
        automationRoot: Path,
        worldFiles: List<String>,
    ): Boolean =
        e.sourceId == AUTOMATION_DIR ||
            e.sourceId == AUTOMATION_VALIDATOR ||
            AUTOMATION_CODES.any { e.message.startsWith(it) } ||
            e.file.startsWith(automationRoot.toString()) ||
            e.file in worldFiles

    private fun render(
        e: LoadWarning,
        root: Path,
    ): String {
        val file =
            runCatching { Path.of(e.file).relativeTo(root).toString() }.getOrDefault(e.file).ifBlank { e.sourceId }
        val at = if (e.line > 0) ":${e.line}" else ""
        return "$file$at: ${e.message}"
    }

    companion object {
        const val MODEL_DIR = "model"
        const val AUTOMATION_DIR = "automation"
        private const val AUTOMATION_VALIDATOR = "automation-validator"
        private val LAYOUT_CODES = listOf("ttr/package-declaration-mismatch", "ttr/package-prefix-divergence")
        private val AUTOMATION_CODES = listOf("ttr/automation-", "ttr/states-")
        private val log = LoggerFactory.getLogger(BundleLoader::class.java)

        /** sha256 over the model root's files, path and content, in path order — what the bundle was built against. */
        fun modelFingerprint(modelRoot: Path): String {
            if (!modelRoot.isDirectory()) return Fingerprints.sha256("")
            val text =
                Files.walk(modelRoot).use { s ->
                    s
                        .filter { Files.isRegularFile(it) && it.name.substringAfterLast('.') in setOf("ttr", "ttrm") }
                        .sorted()
                        .toList()
                        .joinToString("\n") { "${it.relativeTo(modelRoot)}\n${it.readText()}" }
                }
            return Fingerprints.sha256(text)
        }

        /** What [w] declares for its deployments: its processes, identities, grants and executors. */
        fun scope(w: World): WorldScope =
            WorldScope(
                name = w.qname.name,
                processes = w.processes.associateBy { it.qname.name },
                identities = identities(w),
                grants = grants(w),
                executors = w.executors.mapNotNull { x -> x.extendsRef?.let { x.qname.name to it } }.toMap(),
            )

        fun identities(w: World): List<IdentityDecl> =
            w.identities.mapNotNull { i ->
                val principal = i.principal ?: return@mapNotNull null
                val credential = i.credential ?: return@mapNotNull null
                IdentityDecl(i.qname.name, principal, credential)
            }

        fun grants(w: World): List<GrantDecl> =
            w.grants.map { g ->
                GrantDecl(
                    who =
                        when (val who = g.who) {
                            is GrantWho.Role -> Who.Role(who.name)
                            is GrantWho.User -> Who.User(who.name)
                            is GrantWho.Identity -> Who.Identity(who.name)
                            GrantWho.Anyone -> Who.Anyone
                        },
                    program = g.program ?: GrantDecl.ANY_PROGRAM,
                    runAs = runAs(g.runAs),
                )
            }

        fun runAs(r: RunAsRef): GrantRunAs =
            when (r) {
                RunAsRef.Caller -> GrantRunAs.Caller
                RunAsRef.Author -> GrantRunAs.Author
                is RunAsRef.Identity -> GrantRunAs.Named(r.name)
            }
    }
}
