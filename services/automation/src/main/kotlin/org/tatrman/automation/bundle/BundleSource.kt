// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevWalk
import org.slf4j.LoggerFactory
import org.tatrman.automation.config.BundleGitConfig
import org.tatrman.ttr.metadata.git.GitArchiveStorage
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Where `/refresh` gets a commit from: one checkout per branch, brought to the branch's latest commit. */
fun interface BundleSource {
    /** The branch's latest commit, checked out; throws [CheckoutFailedException] when it cannot. */
    fun checkout(branch: String): Checkout
}

/** `502 checkout-failed`: the repo or the branch could not be reached. */
class CheckoutFailedException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The automation repo over git (D-62: git is the source, the catalog the compiled view) — `ttr-metadata-git`'s
 * [GitArchiveStorage], Metadata's refresh mechanism: clone once per branch under [baseDir], then fetch + hard reset to
 * `origin/<branch>`. The commit's author is read here (the deployer, D-90). A GitHub token is passed the
 * `x-access-token` way.
 */
class GitBundleSource(
    private val config: BundleGitConfig,
    private val baseDir: Path,
) : BundleSource {
    private val storages = ConcurrentHashMap<String, GitArchiveStorage>()

    override fun checkout(branch: String): Checkout {
        val url = config.url ?: throw CheckoutFailedException("automation.bundle.git.url is not configured")
        val dir = baseDir.resolve(branch.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val cloned = !dir.toFile().exists()
        val storage =
            storages.computeIfAbsent(branch) {
                GitArchiveStorage(
                    id = "automation-$branch",
                    remoteUri = url,
                    localCheckoutDir = dir,
                    branch = branch,
                    username = config.token?.let { TOKEN_USER },
                    password = config.token,
                )
            }
        val sha =
            try {
                storage.fetchVersion()
            } catch (e: Exception) {
                // A clone this call began is dropped; an existing one is kept for the next try (a remote that is
                // down for a moment should not cost a full clone).
                if (cloned) forget(branch, dir) else storages.remove(branch)
                throw CheckoutFailedException("checkout of $branch failed: ${e.message}", e)
            }
        return try {
            Git.open(dir.toFile()).use { git ->
                val repo = git.repository
                if (repo.exactRef("refs/remotes/origin/$branch") == null) {
                    throw CheckoutFailedException("branch $branch is not in the automation repo")
                }
                val commit = RevWalk(repo).use { it.parseCommit(repo.resolve(sha)) }
                val root = if (config.path.isBlank()) dir else dir.resolve(config.path)
                Checkout(root, branch, sha, commit.authorIdent.name, commit.authorIdent.emailAddress)
            }
        } catch (e: CheckoutFailedException) {
            // JGit clones a branch the remote lacks without complaint (every object, no checkout): drop that clone,
            // or each unknown branch name asked for would leave a full copy of the repo on disk.
            forget(branch, dir)
            throw e
        } catch (e: Exception) {
            throw CheckoutFailedException("reading $branch at $sha failed: ${e.message}", e)
        }.also { log.info("automation: {} checked out at {}", branch, it.commit.take(12)) }
    }

    private fun forget(
        branch: String,
        dir: Path,
    ) {
        storages.remove(branch)
        if (!dir.toFile().deleteRecursively()) log.warn("automation: could not delete the clone {}", dir)
    }

    private companion object {
        const val TOKEN_USER = "x-access-token"
        val log = LoggerFactory.getLogger(GitBundleSource::class.java)
    }
}
