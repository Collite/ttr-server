// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.cn

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The inbox (A-CN-93): `<dir>/<candidate id>.diff` (what `git apply` takes at the repository root — the [Promoter] puts
 * the model's sub-path in every path) and `<id>.md` (the pull request's title and body, then the base commit and that
 * command), for an estate with no provider configured — or beside one, as a copy.
 */
class InboxDelivery(
    private val dir: Path,
) : PromotionDelivery {
    override fun deliver(p: PendingPromotion): Delivered {
        dir.createDirectories()
        val diff = dir.resolve("${p.candidateId}.diff")
        diff.writeText(p.patch.diff)
        dir.resolve("${p.candidateId}.md").writeText(
            "# ${p.pullRequest.title}\n\n${p.pullRequest.body}\n---\n\n" +
                "Against `${p.base}`. From the repository root: `git apply ${diff.fileName}`.\n",
        )
        return Delivered(inbox = diff.toString())
    }
}

/**
 * A GitHub pull request without `git` (A-CN-93): the REST Git Data API commits the patched files on a new branch from
 * the promotion's base commit — so the patch applies exactly as gated — and opens the pull request against
 * [baseBranch]. [pathPrefix] is the model root's path in the repository (the source's sub-path). The token needs
 * `contents: write` and `pull_requests: write` on [repository] (`owner/name`); it is not the read token the source
 * clones with.
 */
class GitHubPullRequests(
    private val repository: String,
    private val token: String,
    private val baseBranch: String,
    private val pathPrefix: String = "",
    private val api: URI = URI.create("https://api.github.com"),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : PromotionDelivery {
    init {
        require(REPOSITORY.matches(repository)) { "'$repository' is not owner/name" }
    }

    override fun deliver(p: PendingPromotion): Delivered {
        val baseTree = call("GET", "git/commits/${p.base}", null, "read the base commit").obj("tree").str("sha")
        val tree =
            call(
                "POST",
                "git/trees",
                buildJsonObject {
                    put("base_tree", baseTree)
                    putJsonArray("tree") {
                        for ((path, text) in p.patch.files) {
                            addJsonObject {
                                put("path", repoPath(path))
                                put("mode", "100644")
                                put("type", "blob")
                                put("content", text)
                            }
                        }
                    }
                },
                "write the tree",
            ).str("sha")
        val commit =
            call(
                "POST",
                "git/commits",
                buildJsonObject {
                    put("message", "${p.pullRequest.title}\n\ncn candidate ${p.candidateId}")
                    put("tree", tree)
                    putJsonArray("parents") { add(p.base) }
                },
                "write the commit",
            ).str("sha")
        val branch = "cn/promote-${p.candidateId.take(8)}-${STAMP.format(p.at.atOffset(ZoneOffset.UTC))}"
        call(
            "POST",
            "git/refs",
            buildJsonObject {
                put("ref", "refs/heads/$branch")
                put("sha", commit)
            },
            "create the branch",
        )
        val pull =
            try {
                call(
                    "POST",
                    "pulls",
                    buildJsonObject {
                        put("title", p.pullRequest.title)
                        put("head", branch)
                        put("base", baseBranch)
                        put("body", p.pullRequest.body)
                    },
                    "open the pull request",
                ).str("html_url")
            } catch (e: CnRefused) {
                // No pull request: take the branch back, so a refused promotion leaves nothing behind (review-134 F2).
                runCatching { call("DELETE", "git/refs/heads/$branch", null, "remove the branch") }
                throw e
            }
        return Delivered(pullRequest = pull)
    }

    private fun repoPath(path: String): String = if (pathPrefix.isBlank()) path else "${pathPrefix.trim('/')}/$path"

    private fun call(
        method: String,
        path: String,
        body: JsonObject?,
        step: String,
    ): JsonObject {
        val request =
            HttpRequest
                .newBuilder(URI.create("${api.toString().trimEnd('/')}/repos/$repository/$path"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .method(
                    method,
                    body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) }
                        ?: HttpRequest.BodyPublishers.noBody(),
                ).apply { if (body != null) header("Content-Type", "application/json") }
                .build()
        val response =
            try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                throw providerFailure("GitHub did not answer ($step): ${e.message}")
            }
        if (response.statusCode() !in 200..299) {
            val message =
                runCatching {
                    Json
                        .parseToJsonElement(response.body())
                        .jsonObject["message"]
                        ?.jsonPrimitive
                        ?.content
                }.getOrNull() ?: response.body().take(200)
            throw providerFailure("GitHub answered ${response.statusCode()} ($step): $message")
        }
        if (response.body().isBlank()) return JsonObject(emptyMap())
        return Json.parseToJsonElement(response.body()).jsonObject
    }

    private fun JsonObject.obj(key: String): JsonObject =
        this[key] as? JsonObject ?: throw providerFailure("GitHub's answer has no `$key`")

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: throw providerFailure("GitHub's answer has no `$key`")

    private fun providerFailure(message: String) = CnRefused(HttpStatusCode.BadGateway, CN_PROMOTION_PROVIDER, message)

    companion object {
        const val CN_PROMOTION_PROVIDER = "CN_PROMOTION_PROVIDER"
        private val REPOSITORY = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        private val GITHUB_REMOTE =
            Regex("^https://([^@/]+@)?github\\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(\\.git)?/?$")

        /** `owner/name` from a `https://[user@]github.com/owner/name(.git)` remote; null for any other remote. */
        fun repositoryOf(remoteUri: String): String? = GITHUB_REMOTE.find(remoteUri.trim())?.groupValues?.get(2)
    }
}
