// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.tatrman.chrono.grpc.ChronoGroundingService
import org.tatrman.grounding.lexicon.GroundingSlice
import org.tatrman.grounding.lexicon.GroundingSliceSource
import org.tatrman.grounding.lexicon.GroundingTerm
import org.tatrman.grounding.lexicon.TriggerMethod
import org.tatrman.grounding.v1.GetStatusRequest
import org.tatrman.grounding.v1.GroundingContext
import org.tatrman.grounding.v1.ScanRequest
import org.tatrman.text.Normalization

/** #405 / TS S0.7: exercise the service's real locale/slice/scanner path, with a populated source. */
class ScanSliceServiceSpec :
    StringSpec({
        val nouns = listOf("měsíc", "měsíci", "měsících", "rok", "roku", "roce", "týden", "čtvrtletí", "pololetí")
        val slice =
            GroundingSlice(
                "chrono",
                nouns.map { GroundingTerm(Normalization.fold(it), it, TriggerMethod.Typos(1), "cs") } +
                    GroundingTerm(Normalization.fold("fiskální rok"), "fiskální rok", TriggerMethod.Tokens, "cs") +
                    listOf("year", "month", "week", "quarter").map {
                        GroundingTerm(it, it, TriggerMethod.Exact, "en")
                    },
                "sha256:s07-service-slice",
            )
        // Only the archive source is a fixture; GroundingSlice matching and service locale narrowing are real.
        val source = mockk<GroundingSliceSource>()
        every { source.current() } returns slice
        val populated = ChronoGroundingService(FakeMetadataClient(emptyList()), llmFallback = null, triggers = source)
        val empty = ChronoGroundingService(FakeMetadataClient(emptyList()), llmFallback = null)

        fun request(
            text: String,
            pkg: String = "prodej",
            locale: String = "cs-CZ",
        ): ScanRequest =
            ScanRequest
                .newBuilder()
                .setText(text)
                .setPackage(pkg)
                .setContext(
                    GroundingContext
                        .newBuilder()
                        .setReferenceDatetime("2026-11-15T10:00:00+01:00")
                        .setTimezone("Europe/Prague")
                        .setLocale(locale),
                ).build()

        val negatives =
            listOf(
                "Kolik je dní v měsíci",
                "kolik má měsíc dní",
                "tržby po měsících",
                "průměr na měsíc",
                "v roce",
                "kolik má rok dní",
                "týden",
                "Tržby z tržiště po čtvrtletích",
                "roční tržby podle prodejny",
                "čtvrtletí",
                "pololetí",
                "by month",
                "the year",
                "week",
            )

        "bare period nouns are not service Scan spans with a populated or empty slice" {
            nouns.forEach { slice.forLang("cs-CZ").matches(it) shouldBe true }
            for (service in listOf(populated, empty)) {
                for (text in negatives) {
                    withClue(text) {
                        val locale = if (text in negatives.takeLast(3)) "en" else "cs-CZ"
                        val response = service.scan(request(text, locale = locale))
                        response.spansList.shouldBeEmpty()
                        response.messagesList.shouldBeEmpty()
                    }
                }
            }
        }

        "the mixed year and by-month question retains only its authored year" {
            for (service in listOf(populated, empty)) {
                val spans = service.scan(request("Tržby v roce 2025 po měsících")).spansList
                spans.map { Triple(it.start, it.end, it.text) } shouldBe listOf(Triple(8, 17, "roce 2025"))
            }
        }

        "relative and qualified periods still scan, including a reading enabled by the slice" {
            for (text in listOf("minulý měsíc", "tento týden", "3. čtvrtletí 2025", "fiskální rok 2025")) {
                withClue(text) {
                    populated.scan(request(text)).spansList.map { it.text } shouldBe listOf(text)
                }
            }
            // This reading needs the slice: guarding bare nouns must not turn off slice use.
            val fiscal = populated.scan(request("fiskální rok")).spansList.single()
            fiscal.text shouldBe "fiskální rok"
            fiscal.confidence shouldBe 0.8f
            fiscal.trigger shouldBe "rok"
            empty.scan(request("fiskální rok")).spansList.shouldBeEmpty()
        }

        "the same request and populated slice repeat byte-identically and remain loaded" {
            val status = GetStatusRequest.getDefaultInstance()
            val before = populated.getStatus(status)
            before.capabilitiesMap["lexicon_slice"] shouldBe slice.version
            before.capabilitiesMap["lexicon_slice_terms"] shouldBe slice.terms.size.toString()
            val req = request("Tržby v roce 2025 po měsících")
            val first = populated.scan(req)
            first.spansList.map { it.text } shouldBe listOf("roce 2025")
            populated.scan(req).toByteArray().contentEquals(first.toByteArray()) shouldBe true
            populated.getStatus(status).capabilitiesMap shouldBe before.capabilitiesMap
        }

        "empty and named packages obey the bare-noun rule through the same service slice" {
            for (pkg in listOf("", "prodej")) {
                withClue("package='$pkg'") {
                    for (text in negatives) {
                        populated
                            .scan(request(text, pkg, if (text in negatives.takeLast(3)) "en" else "cs-CZ"))
                            .spansList
                            .shouldBeEmpty()
                    }
                    populated.scan(request("Tržby v roce 2025 po měsících", pkg)).spansList.map { it.text } shouldBe
                        listOf("roce 2025")
                    populated.scan(request("minulý měsíc", pkg)).spansList.map { it.text } shouldBe
                        listOf("minulý měsíc")
                }
            }
        }
    })
