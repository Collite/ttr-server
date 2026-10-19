// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.BundleFixture.edit
import org.tatrman.automation.config.BundleGitConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AU-server S0.2 T4 — `/refresh` over a real git repo (the fixture committed into a throwaway repo, read by
 * `GitArchiveStorage` the way the service reads the world's repo): all-or-nothing, a no-op for a commit already
 * loaded, previews beside the served bundle, and every refusal leaving the served bundle untouched.
 */
class RefreshSpec :
    StringSpec({
        fun source(repo: Path) =
            GitBundleSource(
                BundleGitConfig(repo.toUri().toString(), "master", "", null),
                Files.createTempDirectory("clones"),
            )

        suspend fun Bundles.refusal(branch: String? = null): AutomationException = shouldThrow { refresh(branch) }

        "the first refresh serves the branch's latest commit; a second with the same commit is a no-op" {
            val repo = BundleFixture.gitRepo()
            val book = BundleFixture.MemoryBook()
            val bundles = BundleFixture.bundles(source(repo), book = book)
            val first = bundles.refresh()
            first.changed shouldBe true
            bundles.served shouldBe first.bundle
            bundles.latestCommit shouldBe first.bundle.commit
            first.bundle.author shouldBe "po" // the commit author, through the author map
            val again = bundles.refresh()
            again.changed shouldBe false
            again.bundle shouldBe first.bundle
            book.rows.keys shouldBe setOf(first.bundle.id)
        }

        "a new commit is loaded in full and swapped in" {
            val repo = BundleFixture.gitRepo()
            val bundles = BundleFixture.bundles(source(repo))
            val before = bundles.refresh().bundle
            val sha =
                BundleFixture.commit(repo) {
                    edit("model/worlds/demo.ttrm") { it.replace("granularity: parts", "granularity: islands") }
                }
            val after = bundles.refresh()
            after.changed shouldBe true
            after.bundle.commit shouldBe sha
            bundles.served!!.deploymentFps.getValue("release_n8n") shouldNotBe
                before.deploymentFps.getValue("release_n8n")
        }

        "an invalid commit is 422 bundle-invalid with every finding, and the served bundle stays" {
            val repo = BundleFixture.gitRepo()
            val bundles = BundleFixture.bundles(source(repo))
            val served = bundles.refresh().bundle
            val bad =
                BundleFixture.commit(repo) {
                    edit(
                        "automation/settle/settle.bundle/manifest.json",
                    ) { it.replace("\"schemaVersion\": 3", "\"schemaVersion\": 2") }
                }
            val e = bundles.refusal()
            e.code shouldBe ErrorCode.BUNDLE_INVALID
            e.detail!!
                .jsonObject["diagnostics"]!!
                .jsonArray
                .map { it.jsonPrimitive.content } shouldContain
                "bundle settle: manifest schemaVersion 2, this Server reads 3"
            bundles.served shouldBe served
            bundles.latestCommit shouldBe bad
            bundles.refreshFailed shouldBe true
        }

        "a failing checkout is 502 checkout-failed and the served bundle stays" {
            val repo = BundleFixture.gitRepo()
            val bundles = BundleFixture.bundles(source(repo))
            val served = bundles.refresh().bundle
            repo.toFile().deleteRecursively()
            bundles.refusal().code shouldBe ErrorCode.CHECKOUT_FAILED
            bundles.served shouldBe served
            bundles.refreshFailed shouldBe true
        }

        "`?branch=` loads a preview beside the served bundle (D-111); a branch outside allowedBranches is 403" {
            val repo = BundleFixture.gitRepo()
            val sha =
                BundleFixture.commit(repo, branch = "preview-tolerance") {
                    edit("automation/automation.ttrm") { it.replace("value: 0.01", "value: 0.02") }
                }
            val bundles = BundleFixture.bundles(source(repo), allowed = listOf("preview-*"))
            val served = bundles.refresh().bundle
            val preview = bundles.refresh("preview-tolerance").bundle
            preview.kind shouldBe BundleKind.PREVIEW
            preview.commit shouldBe sha
            preview.id shouldBe "preview:preview-tolerance@${sha.take(12)}"
            bundles.served shouldBe served
            bundles.previews().map { it.bundle } shouldBe listOf(preview)

            val e = bundles.refusal("feature-x")
            e.code shouldBe ErrorCode.BRANCH_NOT_ALLOWED
            e.message!! shouldContain "feature-x"
        }

        "a branch the repo does not have is 502 checkout-failed" {
            val bundles = BundleFixture.bundles(source(BundleFixture.gitRepo()), allowed = listOf("*"))
            bundles.refusal("nope").code shouldBe ErrorCode.CHECKOUT_FAILED
        }

        "one refresh at a time: a second one meanwhile is 409 refresh-in-progress" {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val slow =
                BundleSource { branch ->
                    entered.complete(Unit)
                    release.await(10, TimeUnit.SECONDS)
                    BundleFixture.checkout(branch = branch)
                }
            val bundles = BundleFixture.bundles(slow)
            runBlocking {
                coroutineScope {
                    val first = async { bundles.refresh() }
                    entered.await()
                    bundles.refusal().code shouldBe ErrorCode.REFRESH_IN_PROGRESS
                    release.countDown()
                    first.await().changed shouldBe true
                }
            }
        }
    })
