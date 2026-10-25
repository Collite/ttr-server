// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.util.Base64

class PageTokenCodecSpec :
    StringSpec({

        "empty token decodes to Start" {
            PageTokenCodec.decode("") shouldBe PageTokenCodec.PageStart.Start
        }

        "v2 token round-trips through encode -> decode" {
            val key = "db.dbo.customers"
            val token = PageTokenCodec.encodeAfter(key)
            token shouldStartWith "v2:"
            PageTokenCodec.decode(token) shouldBe PageTokenCodec.PageStart.After(key)
        }

        "legacy offset token (Base64 of stringified int) decodes to LegacyOffset for back-compat" {
            val legacy = Base64.getEncoder().encodeToString("42".toByteArray())
            PageTokenCodec.decode(legacy) shouldBe PageTokenCodec.PageStart.LegacyOffset(42)
        }

        "malformed token decodes to Start (silent restart)" {
            PageTokenCodec.decode("v2:not-base64!!!") shouldBe PageTokenCodec.PageStart.Start
            PageTokenCodec.decode("totally garbage") shouldBe PageTokenCodec.PageStart.Start
        }

        "paginate from start returns the first page + token pointing past the last key" {
            val items = listOf("a", "b", "c", "d", "e")
            val (slice, next) = PageTokenCodec.paginate(items, token = "", pageSize = 2) { it }
            slice shouldBe listOf("a", "b")
            PageTokenCodec.decode(next) shouldBe PageTokenCodec.PageStart.After("b")
        }

        "paginate continues past the last-seen key (not by offset)" {
            val items = listOf("a", "b", "c", "d", "e")
            val firstNext = PageTokenCodec.paginate(items, "", 2) { it }.second
            val (secondSlice, secondNext) = PageTokenCodec.paginate(items, firstNext, 2) { it }
            secondSlice shouldBe listOf("c", "d")
            PageTokenCodec.decode(secondNext) shouldBe PageTokenCodec.PageStart.After("d")
        }

        "next-page token is empty when there are no more rows" {
            val (slice, next) = PageTokenCodec.paginate(listOf("a", "b"), "", 10) { it }
            slice shouldBe listOf("a", "b")
            next shouldBe ""
        }

        "key-based pagination is correct under inserts at the front (concurrent mutation)" {
            // Page 1 of [a, b, c, d, e] with size 2 → [a, b]; next-token = After("b").
            val pageOne = PageTokenCodec.paginate(listOf("a", "b", "c", "d", "e"), "", 2) { it }
            val token = pageOne.second
            // Concurrent insert at the front: new list is [aa, a, b, c, d, e]. Page 2 with the
            // same token must NOT skip a row — offset-2 would land on `b`, dupe-ing; key-based
            // lands strictly after "b" → "c".
            val pageTwo = PageTokenCodec.paginate(listOf("aa", "a", "b", "c", "d", "e"), token, 2) { it }
            pageTwo.first shouldBe listOf("c", "d")
        }

        "legacy-offset token still works (old client back-compat)" {
            val items = listOf("a", "b", "c", "d", "e")
            val legacyToken = Base64.getEncoder().encodeToString("2".toByteArray())
            val (slice, _) = PageTokenCodec.paginate(items, legacyToken, 2) { it }
            slice shouldBe listOf("c", "d")
        }
    })
