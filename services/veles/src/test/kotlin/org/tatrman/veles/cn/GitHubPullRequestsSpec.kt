// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.cn

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.concept.candidate.PromotionPatch
import org.tatrman.concept.candidate.PullRequest
import java.net.InetSocketAddress
import java.net.URI
import java.time.Instant

/**
 * CN-P5 S5.3 T4 (A-CN-93) — a promotion's pull request through GitHub's REST Git Data API, no `git`: the patched files
 * committed on a new branch FROM THE BASE THE PATCH WAS GATED AGAINST, under the model's sub-path, then the pull
 * request against the source's branch. Against a fake API that records what it was sent.
 */
class GitHubPullRequestsSpec :
    FunSpec({
        data class Call(
            val method: String,
            val path: String,
            val auth: String?,
            val body: JsonObject?,
        )

        fun fake(failRefs: Boolean = false): Pair<HttpServer, MutableList<Call>> {
            val calls = mutableListOf<Call>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { ex ->
                val text = ex.requestBody.readBytes().decodeToString()
                val path = ex.requestURI.path
                calls +=
                    Call(
                        ex.requestMethod,
                        path,
                        ex.requestHeaders.getFirst("Authorization"),
                        text
                            .takeIf {
                                it.isNotEmpty()
                            }?.let { Json.parseToJsonElement(it).jsonObject },
                    )
                val (status, answer) =
                    when {
                        path == "/repos/clinic/model/git/commits/base0" ->
                            200 to
                                """{"sha":"base0","tree":{"sha":"tree0"}}"""
                        path.endsWith("/git/trees") -> 201 to """{"sha":"tree1"}"""
                        path.endsWith("/git/commits") -> 201 to """{"sha":"commit1"}"""
                        path.endsWith("/git/refs") && failRefs -> 422 to """{"message":"Reference already exists"}"""
                        path.endsWith("/git/refs") -> 201 to """{"ref":"x"}"""
                        path.endsWith("/pulls") -> 201 to """{"html_url":"https://github.com/clinic/model/pull/7"}"""
                        else -> 404 to """{"message":"Not Found"}"""
                    }
                val bytes = answer.toByteArray()
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            server.start()
            return server to calls
        }

        val pending =
            PendingPromotion(
                candidateId = "9a4c7e21-3f58-4b06-a1d7-e2b5c80f6d19",
                patch = PromotionPatch("cn.IschemicPatient", "--- a/cn.ttrm\n", mapOf("cn.ttrm" to "model cn\n")),
                base = "base0",
                pullRequest = PullRequest("cn: promote phase IschemicPatient", "Promotes cn candidate …"),
                at = Instant.parse("2026-10-23T12:34:56Z"),
            )

        test("the files commit on a branch from the gated base, and the pull request opens against the source branch") {
            val (server, calls) = fake()
            try {
                val gh =
                    GitHubPullRequests(
                        repository = "clinic/model",
                        token = "pr-token",
                        baseBranch = "master",
                        pathPrefix = "model/",
                        api = URI.create("http://127.0.0.1:${server.address.port}"),
                    )
                gh.deliver(pending) shouldBe Delivered(pullRequest = "https://github.com/clinic/model/pull/7")
                calls.map { "${it.method} ${it.path}" } shouldBe
                    listOf(
                        "GET /repos/clinic/model/git/commits/base0",
                        "POST /repos/clinic/model/git/trees",
                        "POST /repos/clinic/model/git/commits",
                        "POST /repos/clinic/model/git/refs",
                        "POST /repos/clinic/model/pulls",
                    )
                calls.forEach { it.auth shouldBe "Bearer pr-token" }
                val tree = calls[1].body!!
                tree["base_tree"]!!.jsonPrimitive.content shouldBe "tree0"
                val entry = tree["tree"]!!.jsonArray.single().jsonObject
                entry["path"]!!.jsonPrimitive.content shouldBe "model/cn.ttrm"
                entry["content"]!!.jsonPrimitive.content shouldBe "model cn\n"
                calls[2].body!!["parents"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("base0")
                calls[3].body!!["ref"]!!.jsonPrimitive.content shouldBe "refs/heads/cn/promote-9a4c7e21-20261023123456"
                calls[3].body!!["sha"]!!.jsonPrimitive.content shouldBe "commit1"
                val pull = calls[4].body!!
                pull["head"]!!.jsonPrimitive.content shouldBe "cn/promote-9a4c7e21-20261023123456"
                pull["base"]!!.jsonPrimitive.content shouldBe "master"
                pull["title"]!!.jsonPrimitive.content shouldBe "cn: promote phase IschemicPatient"
            } finally {
                server.stop(0)
            }
        }

        test("a provider refusal is 502 CN_PROMOTION_PROVIDER with GitHub's own message and the step") {
            val (server, _) = fake(failRefs = true)
            try {
                val gh =
                    GitHubPullRequests(
                        "clinic/model",
                        "t",
                        "master",
                        api = URI.create("http://127.0.0.1:${server.address.port}"),
                    )
                val e = shouldThrow<CnRefused> { gh.deliver(pending) }
                e.status shouldBe HttpStatusCode.BadGateway
                e.code shouldBe GitHubPullRequests.CN_PROMOTION_PROVIDER
                e.message.orEmpty() shouldStartWith "GitHub answered 422 (create the branch)"
                e.message.orEmpty() shouldContain "Reference already exists"
            } finally {
                server.stop(0)
            }
        }

        test("owner/name from a github.com remote, credentials or not; nothing from another host") {
            GitHubPullRequests.repositoryOf("https://github.com/Collite/ttr-demo.git") shouldBe "Collite/ttr-demo"
            GitHubPullRequests.repositoryOf("https://x-access-token:abc@github.com/Collite/ttr-demo") shouldBe
                "Collite/ttr-demo"
            GitHubPullRequests.repositoryOf("https://gitlab.example/clinic/model.git") shouldBe null
            shouldThrow<IllegalArgumentException> { GitHubPullRequests("not a repo", "t", "main") }
        }
    })
