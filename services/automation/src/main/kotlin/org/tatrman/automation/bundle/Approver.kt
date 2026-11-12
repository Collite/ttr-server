// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import java.time.Instant

/**
 * The fingerprints one `approvals` row covers (contracts §2): a recipe's `text` and `values` (both recorded on either
 * of its kinds, so the row names the text its values were approved under), or a program's / deployment's own `fp`.
 */
@Serializable
data class ApprovalFps(
    val text: String? = null,
    val values: String? = null,
    val fp: String? = null,
)

/** One `approvals` row as written (status `approved`). */
@Serializable
data class ApprovalRecord(
    val id: String,
    val objectKind: String,
    val obj: String,
    val kind: String,
    val fps: ApprovalFps,
    val approver: String,
    val at: String,
    val note: String? = null,
    val status: String = "approved",
    /** False when an identical `approved` row already existed and was answered instead of a new one. */
    val created: Boolean = true,
)

/** The ledger's side of [Approver]: one transaction, serialized per (kind, object). */
fun interface ApprovalBook {
    /**
     * Answers the `approved` row of ([ApprovalRecord.kind], [ApprovalRecord.obj]) under the same fingerprint when one
     * exists; else inserts [record] and marks every other `approved` row of that kind and object `superseded`.
     */
    fun approve(record: ApprovalRecord): ApprovalRecord
}

/**
 * D-60 / D-13 / D-89 — the writes behind staleness. An approval is of what is served now: the object must be served,
 * and the fingerprint supplied must be its current one (else the kind's `409 *-stale`, nothing written). A `recipe`
 * approval needs at least one golden case (`tests/`, the manifest's `recipe.tests`; D-89, 16 §2.9) — `422
 * golden-required`. The `values` kind does not (12 §E: the lighter approval). Re-approving the same fingerprint is
 * idempotent; approving a new one supersedes the old rows of that kind and object.
 */
class Approver(
    private val served: () -> Bundle?,
    private val book: ApprovalBook,
    private val now: () -> Instant,
    private val newId: () -> java.util.UUID = java.util.UUID::randomUUID,
) {
    fun approve(
        objectKind: String,
        obj: String,
        fps: ApprovalFps,
        kind: String,
        by: String,
        note: String? = null,
    ): ApprovalRecord {
        if (by.isBlank()) throw AutomationException(ErrorCode.IDENTITY, "an approval names its approver")
        val allowed = KINDS[objectKind] ?: bad("object kind must be one of ${KINDS.keys.sorted()}")
        if (kind !in allowed) bad("a $objectKind approval is of kind ${allowed.sorted().joinToString(" or ")}")
        val bundle = served() ?: throw AutomationException(ErrorCode.RECIPE, "no bundle is served")
        val current: ApprovalFps =
            when (objectKind) {
                "recipe" -> {
                    val recipe =
                        bundle.recipes[obj]?.recipe
                            ?: throw AutomationException(ErrorCode.RECIPE, "no recipe $obj is served")
                    if (kind == "recipe" && recipe.tests.isEmpty()) {
                        throw AutomationException(
                            ErrorCode.GOLDEN_REQUIRED,
                            "recipe $obj has no golden case (tests/); a recipe approval needs at least one (D-89)",
                            buildJsonObject { put("recipe", obj) },
                        )
                    }
                    ApprovalFps(recipe.fingerprints.text, recipe.fingerprints.values)
                }
                "program" -> ApprovalFps(fp = bundle.programFps[obj] ?: bad("no program $obj is served"))
                else ->
                    ApprovalFps(
                        fp =
                            bundle.deploymentFps[obj]
                                ?: throw AutomationException(ErrorCode.DEPLOYMENT, "no deployment $obj is served"),
                    )
            }
        val judged =
            when (kind) {
                "recipe" -> "text" to (fps.text to current.text)
                "values" -> "values" to (fps.values to current.values)
                else -> kind to (fps.fp to current.fp)
            }
        val (dimension, pair) = judged
        val (supplied, served) = pair
        if (supplied != served) {
            val code =
                when (objectKind) {
                    "recipe" -> ErrorCode.RECIPE_STALE
                    "program" -> ErrorCode.PROGRAM_STALE
                    else -> ErrorCode.DEPLOYMENT_STALE
                }
            throw AutomationException(
                code,
                "the $dimension fingerprint supplied is not the one served; nothing was approved",
                buildJsonObject {
                    put("differs", JsonArray(listOf(JsonPrimitive(dimension))))
                    putJsonObject("supplied") { put(dimension, supplied?.let(::JsonPrimitive) ?: JsonPrimitive("")) }
                    putJsonObject("current") { put(dimension, served) }
                },
            )
        }
        return book.approve(
            ApprovalRecord(
                newId().toString(),
                objectKind,
                obj,
                kind,
                current,
                by,
                now().toString(),
                note?.ifBlank { null },
            ),
        )
    }

    private fun bad(message: String): Nothing = throw AutomationException(ErrorCode.BAD_REQUEST, message)

    companion object {
        /** D-60's four kinds by object; `personal` (D-13's personal approvals) is not written by the Server in v1. */
        val KINDS =
            mapOf(
                "recipe" to setOf("recipe", "values"),
                "program" to setOf("program"),
                "deployment" to setOf("deployment"),
            )
    }
}
