// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.resolver.token.ResumeTokenException
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResumeAnswer

/**
 * RG-P6 review C — a resume token is bound to the OBO subject it was issued to. A
 * valid (correctly-signed, unexpired) token replayed by a DIFFERENT principal is
 * refused, closing the "leaked/shared token replayed by another user" hole. Drives
 * the REAL pipeline: turn 0 clarifies as `alice`, turn 1 tries to resume as someone
 * else. Same-subject resume still binds (and reconstructs the MEMBER Domain).
 */
class ResumeSubjectBindingTest :
    StringSpec({

        fun fresh(subject: String) =
            ResolveRequest
                .newBuilder()
                .setConversationId("c-sub")
                .setCallerSubject(subject)
                .setFresh(FreshQuestion.newBuilder().setText("kolik za QT"))
                .build()

        fun resume(
            token: String,
            subject: String,
        ) = ResolveRequest
            .newBuilder()
            .setConversationId("c-sub")
            .setCallerSubject(subject)
            .setResume(ResumeAnswer.newBuilder().setToken(token).setSelectedOptionId("M:er.qstred_df.member#qt-orlak"))
            .build()

        "a token issued to alice cannot be resumed by a different principal" {
            val codec = ConformancePipeline.codec()
            val pipeline = ConformancePipeline.pipeline("ambiguous_member", codec)

            val clar = runBlocking { pipeline.resolve(fresh("alice")) }
            val token = clar.awaiting.resumeToken

            // A different subject with the SAME valid token → refused (RG-RES-002).
            shouldThrow<ResumeTokenException> {
                runBlocking { pipeline.resolve(resume(token, subject = "mallory")) }
            }
            // An empty-subject (dev-network) caller cannot replay a subject-bound token either.
            shouldThrow<ResumeTokenException> {
                runBlocking { pipeline.resolve(resume(token, subject = "")) }
            }
        }

        "the same subject resumes and reconstructs the MEMBER binding" {
            val codec = ConformancePipeline.codec()
            val pipeline = ConformancePipeline.pipeline("ambiguous_member", codec)

            val clar = runBlocking { pipeline.resolve(fresh("alice")) }
            val res = runBlocking { pipeline.resolve(resume(clar.awaiting.resumeToken, subject = "alice")) }

            val domain =
                res.resolution.bindingsList
                    .single()
                    .domain
            domain.resolvedId shouldBe "qt-orlak"
            domain.entityTypeRef shouldBe "er.qstred_df" // review F: no longer empty
        }
    })
