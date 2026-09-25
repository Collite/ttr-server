// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.core.spec.style.StringSpec
import org.tatrman.resolver.token.ResumeOption
import org.tatrman.resolver.token.ResumePayload
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.token.ResumeTokenException

/**
 * RG-P5.S2.T3 — the HMAC resume token (contracts §5, RS-26). Sign/verify round
 * trips; the payload options are the exact signed set; tampering / wrong key /
 * unknown key_id are refused as RG-RES-002; a rotated-out-but-in-window key still
 * verifies; the token is opaque-string-safe for MCP transport.
 */
class ResumeTokenTest :
    StringSpec({

        val k1 = ByteArray(32) { it.toByte() }
        val k2 = ByteArray(32) { (it * 7 + 3).toByte() }

        fun payload(keyId: String = "k1") =
            ResumePayload(
                conversationId = "c-1",
                parseRef = "parse-abc",
                options =
                    listOf(
                        ResumeOption(id = "M:qt-orlak", label = "QT ORLAK", resolvedId = "qt-orlak"),
                        ResumeOption(id = "V:er.branch#term", label = "pobočka", targetRef = "er.branch#term"),
                    ),
                issuedAt = 1_752_000_000,
                keyId = keyId,
            )

        "sign then verify round-trips the exact payload (options are the signed set)" {
            val codec = ResumeTokenCodec(mapOf("k1" to k1), activeKeyId = "k1")
            val token = codec.sign(payload())
            val verified = codec.verify(token).getOrThrow()
            verified shouldBe payload()
            verified.options.map { it.id } shouldBe listOf("M:qt-orlak", "V:er.branch#term")
        }

        "the token is opaque base64url (MCP-transport safe: no framing chars, no padding)" {
            val codec = ResumeTokenCodec(mapOf("k1" to k1), activeKeyId = "k1")
            codec.sign(payload()) shouldMatch Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")
        }

        "a tampered payload is refused as RG-RES-002" {
            val codec = ResumeTokenCodec(mapOf("k1" to k1), activeKeyId = "k1")
            val token = codec.sign(payload())
            val (p, sig) = token.split(".")
            // flip the last payload char to a different valid base64url char
            val tampered = p.dropLast(1) + (if (p.last() == 'A') 'B' else 'A') + "." + sig
            val ex = shouldThrow<ResumeTokenException> { codec.verify(tampered).getOrThrow() }
            ex.message!!.contains("RG-RES-002") shouldBe true
        }

        "a token signed with the wrong key does not verify" {
            val signer = ResumeTokenCodec(mapOf("k1" to k2), activeKeyId = "k1") // k1 slot holds the WRONG bytes
            val verifier = ResumeTokenCodec(mapOf("k1" to k1), activeKeyId = "k1")
            shouldThrow<ResumeTokenException> { verifier.verify(signer.sign(payload())).getOrThrow() }
        }

        "an unknown key_id is refused (RG-RES-002)" {
            val signer = ResumeTokenCodec(mapOf("kX" to k1), activeKeyId = "kX")
            val verifier = ResumeTokenCodec(mapOf("k1" to k1), activeKeyId = "k1")
            shouldThrow<ResumeTokenException> { verifier.verify(signer.sign(payload("kX"))).getOrThrow() }
        }

        "key rotation: a token signed with the rotated-out key still verifies during the window" {
            // new tokens sign with k2, but k1 stays honoured until it ages out.
            val signerOld = ResumeTokenCodec(mapOf("k1" to k1), activeKeyId = "k1")
            val tokenOld = signerOld.sign(payload("k1"))
            val rotated = ResumeTokenCodec(mapOf("k1" to k1, "k2" to k2), activeKeyId = "k2")
            rotated.verify(tokenOld).getOrThrow().keyId shouldBe "k1"
            // and a freshly-signed token uses the active key.
            rotated.verify(rotated.sign(payload("k2"))).getOrThrow().keyId shouldBe "k2"
        }

        "a token past its max-age is refused as RG-RES-002 (bounded replay window)" {
            // issuedAt = 1_752_000_000; clock is 3601s later with a 3600s window → expired.
            val codec =
                ResumeTokenCodec(
                    mapOf("k1" to k1),
                    activeKeyId = "k1",
                    maxAgeSeconds = 3600,
                    clock = { 1_752_000_000 + 3601 },
                )
            val token = codec.sign(payload())
            val ex = shouldThrow<ResumeTokenException> { codec.verify(token).getOrThrow() }
            ex.message!!.contains("RG-RES-002") shouldBe true
            ex.reason.contains("expired") shouldBe true
        }

        "a token within its max-age still verifies" {
            val codec =
                ResumeTokenCodec(
                    mapOf("k1" to k1),
                    activeKeyId = "k1",
                    maxAgeSeconds = 3600,
                    clock = { 1_752_000_000 + 3599 }, // one second inside the window
                )
            codec.verify(codec.sign(payload())).getOrThrow().conversationId shouldBe "c-1"
        }

        "the age check runs only after the signature clears (a tampered expired token is a sig failure)" {
            val codec =
                ResumeTokenCodec(
                    mapOf("k1" to k1),
                    activeKeyId = "k1",
                    maxAgeSeconds = 3600,
                    clock = { 1_752_000_000 + 999_999 },
                )
            val token = codec.sign(payload())
            val (p, sig) = token.split(".")
            val tampered = p.dropLast(1) + (if (p.last() == 'A') 'B' else 'A') + "." + sig
            // A forgery is rejected on integrity (bad signature / unparseable payload) —
            // and is NEVER reported as "expired", proving issued_at is only read after the
            // HMAC clears (no trust in an unverified clock).
            val ex = shouldThrow<ResumeTokenException> { codec.verify(tampered).getOrThrow() }
            ex.message!!.contains("RG-RES-002") shouldBe true
            ex.reason.contains("expired") shouldBe false
        }
    })
