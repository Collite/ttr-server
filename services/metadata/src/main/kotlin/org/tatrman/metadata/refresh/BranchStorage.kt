// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import org.slf4j.LoggerFactory
import org.tatrman.metadata.health.redactCredentials
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.metadata.source.ModelStorage
import org.tatrman.ttr.metadata.source.StorageFile
import java.nio.file.Files
import java.nio.file.Path

/** Service-owned ref selection. One fetch/resolution per load; all subsequent reads use that commit. */
internal class BranchStorage(
    override val id: String,
    private val remote: String,
    val checkout: Path,
    val defaultBranch: String,
    subdirectory: String,
    private val username: String? = null,
    private val password: String? = null,
) : ModelStorage {
    private val log = LoggerFactory.getLogger(BranchStorage::class.java)
    var branch: String = defaultBranch
    var attemptedCommit: String = ""
        private set
    private val root = if (subdirectory.isBlank()) checkout else checkout.resolve(subdirectory)
    private val files = LocalFsStorage(id, root)

    data class Checkpoint(
        val branch: String,
        val head: String?,
        val ref: String?,
    )

    fun checkpoint(): Checkpoint =
        if (Files.exists(checkout.resolve(".git"))) {
            Git
                .open(
                    checkout.toFile(),
                ).use { Checkpoint(branch, it.repository.resolve("HEAD")?.name, it.repository.fullBranch) }
        } else {
            Checkpoint(branch, null, null)
        }

    fun restore(point: Checkpoint) {
        branch = point.branch
        if (!Files.exists(checkout.resolve(".git"))) return
        Git.open(checkout.toFile()).use { git ->
            val head =
                point.head ?: git.repository.resolve("refs/remotes/origin/${point.branch}")?.name
                    ?: error("rollback_failed: no local ref for '${point.branch}'")
            detach(git, head)
            git
                .reset()
                .setMode(ResetCommand.ResetType.HARD)
                .setRef(head)
                .call()
            if (point.ref?.startsWith("refs/heads/") == true) {
                git.repository.updateRef("HEAD").link(point.ref)
            }
            git.clean().setCleanDirectories(true).call()
        }
    }

    fun clearAttempt() {
        attemptedCommit = ""
    }

    override fun fetchVersion(): String {
        attemptedCommit = ""
        require(validRef(branch)) {
            "invalid_git_ref"
        }
        val cloned = !Files.exists(checkout.resolve(".git"))
        if (cloned) {
            log.info("Cloning model repository {} (branch {})", redactCredentials(remote), branch)
            Files.createDirectories(checkout)
            Git
                .cloneRepository()
                .setURI(remote)
                .setDirectory(checkout.toFile())
                .setNoCheckout(true)
                .also { if (username != null) it.setCredentialsProvider(credentials()) }
                .call()
                .close()
        }
        return Git.open(checkout.toFile()).use { git ->
            if (!cloned) {
                log.info("Fetching model repository {} (branch {})", redactCredentials(remote), branch)
                git
                    .fetch()
                    .setRemoveDeletedRefs(true)
                    .also {
                        if (username !=
                            null
                        ) {
                            it.setCredentialsProvider(credentials())
                        }
                    }.call()
            }
            val target = if (branch.matches(Regex("[0-9a-fA-F]{40}"))) branch else "refs/remotes/origin/$branch"
            val commit = git.repository.resolve("$target^{commit}") ?: error("git_ref_not_found: $branch")
            attemptedCommit = commit.name
            detach(git, commit.name)
            git
                .reset()
                .setMode(ResetCommand.ResetType.HARD)
                .setRef(commit.name)
                .call()
            git.clean().setCleanDirectories(true).call()
            commit.name
        }
    }

    override fun listFiles(
        extensions: List<String>,
        prefixes: List<String>,
    ): List<StorageFile> = if (Files.isDirectory(root)) files.listFiles(extensions, prefixes) else emptyList()

    override fun read(file: StorageFile): String = files.read(file)

    private fun detach(
        git: Git,
        commit: String,
    ) {
        val update = git.repository.updateRef("HEAD", true)
        update.setNewObjectId(
            org.eclipse.jgit.lib.ObjectId
                .fromString(commit),
        )
        val result = update.forceUpdate()
        check(
            result in
                setOf(
                    org.eclipse.jgit.lib.RefUpdate.Result.NEW,
                    org.eclipse.jgit.lib.RefUpdate.Result.FORCED,
                    org.eclipse.jgit.lib.RefUpdate.Result.FAST_FORWARD,
                    org.eclipse.jgit.lib.RefUpdate.Result.NO_CHANGE,
                ),
        ) {
            "checkout_ref_update_failed: $result"
        }
    }

    companion object {
        fun validRef(ref: String): Boolean =
            ref.matches(Regex("[0-9a-fA-F]{40}")) || Repository.isValidRefName("refs/heads/$ref")
    }

    private fun credentials() = UsernamePasswordCredentialsProvider(username.orEmpty(), password.orEmpty())
}
