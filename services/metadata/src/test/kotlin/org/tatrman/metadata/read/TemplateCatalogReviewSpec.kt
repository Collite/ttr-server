// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.ttr.metadata.graph.ModelGraph

class TemplateCatalogReviewSpec :
    StringSpec({
        "R2 YAML 1.2 Core scalars match the report consumer and preserve arbitrary form values" {
            val catalog =
                catalog(
                    report = "title: {en: Yes, cs: Ano}\nrefresh: off\nroles: [yes, no]\n",
                    form =
                        "country: NO\ndefault: 010\n" +
                            "large: 123456789012345678901234567890\nflag: true\nvalue: null\n",
                )
            catalog.issues shouldBe emptyList()
            val summary = catalog.reports.single().summary
            summary.getValue("title") shouldBe Json.parseToJsonElement("""{"en":"Yes","cs":"Ano"}""")
            summary.getValue("refresh").jsonPrimitive.content shouldBe "off"
            summary.getValue("roles") shouldBe Json.parseToJsonElement("""["yes","no"]""")
            val form = catalog.forms.single()
            form.getValue("country").jsonPrimitive.content shouldBe "NO"
            form.getValue("default").jsonPrimitive.content shouldBe "10"
            form.getValue("large").jsonPrimitive.content shouldBe "123456789012345678901234567890"
            form.getValue("flag").jsonPrimitive.isString shouldBe false
            form.getValue("value") shouldBe JsonNull
        }

        "R2 collection aliases are rejected for reports and forms with a source-specific issue" {
            val catalog =
                catalog(report = "common: &f [{name: amount}]\nparams: *f\n", form = "common: &f []\nfields: *f\n")
            catalog.reports shouldBe emptyList()
            catalog.forms shouldBe emptyList()
            catalog.issues.toSet() shouldBe
                setOf(
                    "template source:model/reports/sales/report.yaml: aliases are not allowed",
                    "template source:model/forms/test.yaml: aliases are not allowed",
                )
        }

        "R2 non-finite numbers remain valid YAML and have explicit JSON string representations" {
            val catalog = catalog(form = "positive: .inf\nnegative: -.inf\nnan: .nan\n")
            catalog.issues shouldBe emptyList()
            val form = catalog.forms.single()
            listOf("positive" to ".inf", "negative" to "-.inf", "nan" to ".nan").forEach { (key, value) ->
                form.getValue(key).jsonPrimitive.isString shouldBe true
                form.getValue(key).jsonPrimitive.content shouldBe value
            }
        }

        "N1 collection nesting accepts the limit and rejects one more before composing YAML" {
            fun nested(depth: Int) = "x: " + "[".repeat(depth - 1) + "0" + "]".repeat(depth - 1)
            catalog(form = nested(MAX_TEMPLATE_NESTING)).apply {
                forms.shouldHaveSize(1)
                issues shouldBe emptyList()
            }
            catalog(form = nested(MAX_TEMPLATE_NESTING + 1)).apply {
                forms shouldBe emptyList()
                issues.single() shouldBe
                    "template source:model/forms/test.yaml: nesting deeper than $MAX_TEMPLATE_NESTING"
            }
        }

        "N2 binary and set values have stable JSON representations; unsupported values are diagnosed" {
            catalog(form = "logo: !!binary aGVsbG8=\ns: !!set {a: null, b: null}\n").apply {
                issues shouldBe emptyList()
                forms.single().getValue("logo") shouldBe Json.parseToJsonElement("\"aGVsbG8=\"")
                forms.single().getValue("s") shouldBe Json.parseToJsonElement("[\"a\",\"b\"]")
            }
            catalog(form = "x: !!java.util.Optional foo\n").apply {
                forms shouldBe emptyList()
                issues.single() shouldBe "template source:model/forms/test.yaml: unsupported YAML value (Optional)"
            }
            catalog(form = "max: +.inf\n").apply {
                forms shouldBe emptyList()
                issues.single() shouldBe "template source:model/forms/test.yaml: unsupported `+.inf` (write `.inf`)"
            }
        }

        "R3 explicit-null metadata uses defaults while wrong field types stay consumer-owned" {
            val summary =
                catalog(
                    report = "title:\nparams:\nroles:\nversion:\nprogram:\nrefresh:\n",
                ).reports.single().summary
            summary.getValue("title") shouldBe Json.parseToJsonElement("{}")
            summary.getValue("params") shouldBe Json.parseToJsonElement("[]")
            summary.getValue("roles") shouldBe Json.parseToJsonElement("[]")
            summary.getValue("version").jsonPrimitive.content shouldBe "1"
            summary.getValue("program") shouldBe JsonNull
            summary.getValue("refresh") shouldBe JsonNull
            catalog(report = "params: foo\n")
                .reports
                .single()
                .summary
                .getValue("params")
                .jsonPrimitive.content shouldBe
                "foo"
        }

        "R4 invalid UTF-8, duplicate keys and documents retain source and distinct one-line causes" {
            val files =
                mapOf(
                    "model/forms/encoding.yaml" to byteArrayOf(0xc3.toByte(), 0x28),
                    "model/forms/duplicate.yaml" to "fields: []\nfields: []\n".toByteArray(),
                    "model/forms/documents.yaml" to "fields: []\n---\nfields: []\n".toByteArray(),
                    "model/forms/scalar.yaml" to "hello\n".toByteArray(),
                )
            val first = templateCatalog("one", 100, files)
            val second = templateCatalog("two", 200, files)
            first.issues.toSet() shouldBe
                setOf(
                    "template one:model/forms/encoding.yaml: not UTF-8",
                    "template one:model/forms/duplicate.yaml: duplicate key 'fields'",
                    "template one:model/forms/documents.yaml: more than one document",
                    "template one:model/forms/scalar.yaml: not a mapping",
                )
            second.issues shouldBe first.issues.map { it.replace("template one:", "template two:") }
        }

        "R7 HTTP lists sort raw ids and cached winner maps keep list/detail precedence consistent" {
            TemplateFixture().use { fixture ->
                val files =
                    listOf("a\"b", "a#b")
                        .flatMap { id ->
                            listOf(
                                "model/reports/$id/report.yaml" to "title: {en: Test}\n".toByteArray(),
                                "model/reports/$id/template.xlsx" to byteArrayOf(1),
                                "model/forms/$id.yaml" to "fields: []\n".toByteArray(),
                            )
                        }.toMap()
                val low = templateCatalog("a", 100, files)
                val high = templateCatalog("b", 100, files + ("model/reports/a#b/template.xlsx" to byteArrayOf(2)))
                val model = fixture.registry.readOrError().model
                fixture.registry.swap(
                    model,
                    ModelGraph.build(model),
                    exportSidecars =
                        mapOf(
                            TEMPLATE_SIDECAR_PREFIX + "a" to Json.encodeToString(low),
                            TEMPLATE_SIDECAR_PREFIX + "b" to Json.encodeToString(high),
                        ),
                )
                testApplication {
                    serve(fixture.registry)
                    val reports =
                        Json
                            .parseToJsonElement(
                                client.get("/model/reports").bodyAsText(),
                            ).jsonObject
                            .getValue("reports")
                            .jsonArray
                    reports.shouldHaveSize(2)
                    reports.map {
                        it.jsonObject
                            .getValue("id")
                            .jsonPrimitive.content
                    } shouldBe listOf("a\"b", "a#b")
                    val forms =
                        Json
                            .parseToJsonElement(
                                client.get("/model/forms").bodyAsText(),
                            ).jsonObject
                            .getValue("forms")
                            .jsonArray
                    forms.map {
                        it.jsonObject
                            .getValue("target")
                            .jsonPrimitive.content
                    } shouldBe listOf("a\"b", "a#b")
                    val detail = Json.parseToJsonElement(client.get("/model/report?id=a%23b").bodyAsText()).jsonObject
                    detail.getValue("template").jsonPrimitive.content shouldBe "Ag=="
                }
            }
        }
    })

private fun catalog(
    report: String? = null,
    form: String? = null,
): TemplateCatalog =
    templateCatalog(
        "source",
        100,
        buildMap {
            report?.let {
                put("model/reports/sales/report.yaml", it.toByteArray())
                put("model/reports/sales/template.xlsx", byteArrayOf(0, -1))
            }
            form?.let { put("model/forms/test.yaml", it.toByteArray()) }
        },
    )
