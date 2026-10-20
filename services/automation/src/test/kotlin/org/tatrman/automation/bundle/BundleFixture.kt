// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.tatrman.automation.api.DescriptorSources
import org.tatrman.automation.config.PreviewsConfig
import org.tatrman.automation.identity.AuthorMap
import org.tatrman.automation.identity.ServiceTokens
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `src/test/resources/bundles/hartland` — the automation repo as `/refresh` reads it (README there). Specs copy it
 * before changing it, and turn a copy into a git repo when they need commits.
 */
object BundleFixture {
    val root: Path = Path.of("src/test/resources/bundles/hartland").toAbsolutePath()

    const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
    val LOADED_AT: Instant = Instant.parse("2026-10-19T06:00:00Z")
    val clock: Clock = Clock.fixed(LOADED_AT, ZoneOffset.UTC)

    /** The deployer of the fixture's commits — mapped to its principal by the author map. */
    val AUTHOR = PersonIdent("Process Owner", "process-owner@hartland.example")
    val authorMap = AuthorMap(mapOf("process-owner@hartland.example" to "po"))

    fun copy(): Path {
        val target = Files.createTempDirectory("automation-fixture")
        Files.walk(root).use { s ->
            s.forEach { src ->
                val dst = target.resolve(root.relativize(src).toString())
                if (Files.isDirectory(
                        src,
                    )
                ) {
                    Files.createDirectories(dst)
                } else {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        return target
    }

    fun checkout(
        dir: Path = root,
        commit: String = COMMIT,
        branch: String = "master",
    ) = Checkout(dir, branch, commit, AUTHOR.name, AUTHOR.emailAddress)

    fun loader() = BundleLoader(authorMap, clock)

    fun load(dir: Path = root): Bundle = loader().load(checkout(dir), BundleKind.SERVED)

    /** Rewrites one file of a fixture copy. */
    fun Path.edit(
        file: String,
        change: (String) -> String,
    ) {
        val f = resolve(file)
        f.writeText(change(f.readText()))
    }

    /** A git repo of a fixture copy on `master`, one commit; answers the repo dir. */
    fun gitRepo(): Path {
        val dir = copy()
        Git.init().setDirectory(dir.toFile()).setInitialBranch("master").call().use { git ->
            git.add().addFilepattern(".").call()
            git
                .commit()
                .setMessage("the hartland automation fixture")
                .setAuthor(AUTHOR)
                .setCommitter(AUTHOR)
                .call()
        }
        return dir
    }

    /** One more commit on [branch] of [repo] (created from the current HEAD when new). */
    fun commit(
        repo: Path,
        branch: String = "master",
        message: String = "change",
        change: Path.() -> Unit,
    ): String =
        Git.open(repo.toFile()).use { git ->
            if (git.repository.branch != branch) {
                val exists = git.repository.findRef("refs/heads/$branch") != null
                git
                    .checkout()
                    .setName(branch)
                    .setCreateBranch(!exists)
                    .call()
            }
            repo.change()
            git.add().addFilepattern(".").call()
            git
                .commit()
                .setMessage(message)
                .setAuthor(AUTHOR)
                .setCommitter(AUTHOR)
                .call()
                .name
        }

    class MemoryBook : BundleBook {
        val rows = linkedMapOf<String, Pair<Bundle, Instant>>()

        override fun record(bundle: Bundle) {
            rows[bundle.id] = bundle to bundle.loadedAt
        }

        override fun touch(
            id: String,
            at: Instant,
        ) {
            rows[id]?.let { rows[id] = it.first to at }
        }
    }

    fun bundles(
        source: BundleSource,
        allowed: List<String> = emptyList(),
        book: BundleBook = MemoryBook(),
    ) = Bundles("master", PreviewsConfig(allowed, 24), source, loader(), book, clock)

    fun sources(
        bundles: Bundles,
        approvals: ApprovalReader = ApprovalReader { _, _, _ -> false },
        veles: VelesHandshake? = null,
    ) = DescriptorSources(
        bundles = bundles,
        registry = Registry(ExecutorType.builtin()),
        approvals = approvals,
        tokens = ServiceTokens(emptyMap(), { error("no token exchange in a descriptor") }),
        veles = veles,
        paused = { false },
        refreshRoles = setOf("ops"),
    )
}
