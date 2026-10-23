// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.cn

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.mcp.identity.Verification
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile

/** The store's test world: the clinic golden world's model text, producers of the `clinic` deployment, callers. */
object CnTestKit {
    private val worlds: Path = Path.of(requireNotNull(System.getProperty("cnWorlds")) { "the cnWorlds property" })

    /** A fresh copy of `shared/testdata/cn-worlds/clinic` — a promotion source a test may patch. */
    fun clinicWorld(): Path {
        val src = worlds.resolve("clinic")
        val dir = Files.createTempDirectory("cn-clinic")
        Files.walk(src).use { s ->
            s.filter { it.isRegularFile() }.forEach { f ->
                f.copyTo(dir.resolve(src.relativize(f).toString()).also { it.parent.createDirectories() })
            }
        }
        return dir
    }

    fun candidate(name: String): String =
        requireNotNull(CnTestKit::class.java.getResource("/cn-candidates/$name.json")) { name }.readText()

    /** The evidence example as another proposal: [name] for its name (and a new [id]), any other edit by [edit]. */
    fun phase(
        name: String,
        id: String,
        edit: (String) -> String = { it },
    ): String =
        edit(
            candidate("evidence-phase")
                .replace("9a4c7e21-3f58-4b06-a1d7-e2b5c80f6d19", id)
                .replace("\"IschemicPatient\"", "\"$name\""),
        )

    val policy =
        CnPolicy(
            producerPrincipals =
                mapOf(
                    "clinic:evidence" to setOf("clinic-evidence"),
                    "clinic:span-catalogue" to setOf("clinic-catalogue"),
                    "kleio" to setOf("kleio"),
                    "import:icd" to setOf("icd-importer"),
                ),
        )

    /** An unsigned token the test verifier accepts: a person's, or a service account's (`service-account-<id>`). */
    fun token(
        principal: String,
        vararg roles: String,
        service: Boolean = false,
    ): String {
        fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        val username = if (service) "service-account-$principal" else principal
        val payload =
            """{"sub":"sub-$principal","preferred_username":"$username",""" +
                """"realm_access":{"roles":[${roles.joinToString(",") { "\"$it\"" }}]}}"""
        return b64("""{"alg":"none"}""") + "." + b64(payload) + ".sig"
    }

    val STEWARD = token("a.steward", "cn-steward")
    val READER = token("b.reader", "kantheon-user")
    val EVIDENCE = token("clinic-evidence", service = true)
    val CATALOGUE = token("clinic-catalogue", service = true)
    val IMPORTER = token("icd-importer", "cn-importer", service = true)

    val callers = CnCallers { Verification.Valid }

    /** One second later at every read: submissions get distinct times, so `newest` has an order to show. */
    class TickingClock(
        private var now: Instant = Instant.parse("2026-10-23T12:00:00Z"),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        @Synchronized
        override fun instant(): Instant = now.also { now = now.plusSeconds(1) }
    }

    /** The model a refresh of [dir] would publish: its cn tier. */
    fun loadCn(dir: Path): CnSchema {
        val snapshot = FileBasedSource("clinic", 100, LocalFsStorage(id = "clinic", rootPath = dir)).load()
        val model =
            ModelReconciler(ModelDescriptor(id = "t", name = "t"))
                .reconcile(listOf(BuiltinStockSource().load(), snapshot))
                .model
        return model.schemas["cn"] as CnSchema
    }

    suspend fun HttpClient.submit(
        token: String?,
        body: String,
    ): HttpResponse =
        post("/v1/cn/candidates") {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body)
        }

    suspend fun HttpClient.postAs(
        token: String?,
        path: String,
        body: String = "",
    ): HttpResponse =
        post(path) {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body)
        }

    suspend fun HttpClient.getAs(
        token: String?,
        path: String,
    ): HttpResponse = get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    fun JsonObject.str(vararg path: String): String? {
        var e: Any? = this
        for (p in path) e = (e as? JsonObject)?.get(p)
        return (e as? kotlinx.serialization.json.JsonPrimitive)?.content
    }

    fun JsonObject.arr(key: String): JsonArray = this[key] as JsonArray

    fun JsonObject.ids(): List<String> =
        arr("candidates").map {
            it.jsonObject
                .getValue("candidate")
                .jsonObject
                .getValue("id")
                .jsonPrimitive.content
        }
}
