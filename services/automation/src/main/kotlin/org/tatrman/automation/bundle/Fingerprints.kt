// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.ttr.metadata.model.AutomationProgram
import org.tatrman.ttr.metadata.model.DeploymentTrigger
import org.tatrman.ttr.metadata.model.RunAsRef
import org.tatrman.ttr.metadata.model.WorldDeployment
import org.tatrman.ttr.parser.model.AutoValue
import java.security.MessageDigest

/**
 * The program and deployment fingerprints of D-60 (the recipe's `text`/`values` come with its manifest). A
 * fingerprint is `sha256:<hex>` over a canonical rendering of the object — its typed reading plus its entries
 * as authored, without source locations, so moving a `def` in its file does not move its fingerprint and
 * changing any value does.
 */
object Fingerprints {
    fun sha256(text: String): String =
        "sha256:" +
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

    fun program(p: AutomationProgram): String = sha256("program ${p.qname.name} {" + entries(p.properties) + "}")

    fun deployment(d: WorldDeployment): String =
        sha256(
            listOf(
                "deployment ${d.qname.name}",
                "program=${d.program}",
                "process=${d.process}",
                "target=${d.target}",
                "run_as=${runAsWire(d.runAs)}",
                "trigger=${triggerWire(d.trigger)}",
                "dry_run=${d.dryRun}",
                "enabled=${d.enabled}",
                "debug=${d.debug}",
                "ungoverned=${d.ungoverned}",
                "granularity=${d.granularity}",
                "allow_restricted_view=${d.allowRestrictedView}",
                "approval_override=${d.approvalOverride}",
                "read_ceiling=${d.readCeiling}",
                "obo_only=${d.oboOnly}",
                "{" + entries(d.properties) + "}",
            ).joinToString("\n"),
        )

    fun runAsWire(r: RunAsRef): String =
        when (r) {
            RunAsRef.Caller -> "caller"
            RunAsRef.Author -> "author"
            is RunAsRef.Identity -> "identity.${r.name}"
        }

    fun triggerWire(t: DeploymentTrigger?): String? =
        when (t) {
            is DeploymentTrigger.Schedule -> "schedule \"${t.spec}\""
            DeploymentTrigger.None, null -> null
        }

    private fun entries(e: List<Pair<String, AutoValue>>): String =
        e.joinToString("; ") { (k, v) -> "$k: ${render(v)}" }

    /** One automation value as text — the authored form, minus whitespace, comments and locations. */
    fun render(v: AutoValue): String =
        when (v) {
            is AutoValue.Str -> JsonPrimitive(v.raw).toString()
            is AutoValue.Num -> v.text
            is AutoValue.Bool -> v.raw.toString()
            is AutoValue.Null -> "null"
            is AutoValue.Id -> v.parts.joinToString(".")
            is AutoValue.Duration -> "${number(v.amount)} ${v.unit}"
            is AutoValue.Tagged -> "${v.tag} ${JsonPrimitive(v.value)}"
            is AutoValue.Call -> "${v.name} {" + v.entries.joinToString(", ") { "${it.key}: ${render(it.value)}" } + "}"
            is AutoValue.Star -> "*"
            is AutoValue.Obj -> "{" + v.entries.joinToString(", ") { "${it.key}: ${render(it.value)}" } + "}"
            is AutoValue.ListV -> "[" + v.items.joinToString(", ") { render(it) } + "]"
        }

    private fun number(d: Double): String =
        if (d % 1.0 == 0.0 &&
            d in -1e15..1e15
        ) {
            d.toLong().toString()
        } else {
            d.toString()
        }
}
