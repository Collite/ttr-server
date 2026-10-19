// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ParseStatus
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.veles.parse.QueryParseState
import org.tatrman.veles.parse.QueryParseWorker
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Calcite freezes its literal charset when it first loads, so a body with a literal outside Latin-1 (`'Poštovné'`, a
 * `TRANSLATE` over `čšž`) parses only if Calcite was promoted to UTF-8 before anything touched it. In this test JVM
 * some earlier spec has always loaded Calcite already, so the worker runs in a JVM of its own
 * ([CalciteCharsetFirstUseMain]) where it is the first to use Calcite — as in veles at start-up, where the reference
 * pass of the dependency order (`Translator.savedQueryReferences`) ran before any parse and left Calcite on
 * ISO-8859-1: every such body failed, with a validation error that does not mention the charset.
 */
class CalciteCharsetFirstUseSpec :
    StringSpec({
        "the worker, first to use Calcite in its JVM: bodies with literals outside Latin-1 parse" {
            val root = Files.createTempDirectory("veles-charset")
            Files.writeString(root.resolve("db.ttrm"), DB)
            Files.writeString(root.resolve("queries.ttrm"), QUERIES)
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val process =
                ProcessBuilder(
                    java,
                    "-cp",
                    System.getProperty("java.class.path"),
                    CalciteCharsetFirstUseMain::class.java.name,
                    root.toString(),
                ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(2, TimeUnit.MINUTES) shouldBe true
            withClue(output) {
                process.exitValue() shouldBe 0
                output.lines().filter { it.startsWith("STATUS ") } shouldContainExactlyInAnyOrder
                    listOf("STATUS postage PARSED", "STATUS find_customer PARSED")
            }
        }
    }) {
    private companion object {
        val DB =
            """
            model db schema dbo

            def table customer {
                primaryKey: [c_customer_sk],
                columns: [
                    def column c_customer_sk { type: int, isKey: true },
                    def column c_last_name { type: text, optional: true }
                ]
            }
            """.trimIndent() + "\n"

        // `š`, `č`, `ž` are outside Latin-1; the second mirrors a search-by-name body: a text parameter under
        // CHAR_LENGTH and TRANSLATE, whose untyped `?` makes the translator retry with typed placeholders.
        val QUERIES =
            "model query\n\ndef query postage {\n    sourceText: \"\"\"sql\n" +
                "SELECT c.c_customer_sk FROM customer c WHERE c.c_last_name <> 'Poštovné'\n\"\"\"\n}\n\n" +
                "def query find_customer {\n    sourceText: \"\"\"sql\n" +
                "SELECT c.c_customer_sk FROM customer c WHERE CHAR_LENGTH({q}) >= 2 " +
                "AND TRANSLATE(LOWER(c.c_last_name), 'čšž', 'csz') " +
                "LIKE '%' || TRANSLATE(LOWER({q}), 'čšž', 'csz') || '%'" +
                "\n\"\"\"\n    parameters: [ { name: q, type: text, label: \"Name\" } ]\n}\n"
    }
}

/** The worker over the model in `args[0]`, in a JVM where nothing has loaded Calcite yet. */
object CalciteCharsetFirstUseMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Path.of(args.single())
        val model =
            ModelReconciler(ModelDescriptor(id = "c", name = "c"))
                .reconcile(listOf(FileBasedSource("c", 100, LocalFsStorage("c", root)).load()))
                .model
        val state = QueryParseState().also { it.reset(model.version.value, model.queries.keys) }
        QueryParseWorker().also { runBlocking { it.parseAll(model, state).join() } }.close()
        for (qname in model.queries.keys) {
            val status = state.get(model.version.value, qname)
            println("STATUS ${qname.name} " + if (status is ParseStatus.ParseSuccess) "PARSED" else status.toString())
        }
    }
}
