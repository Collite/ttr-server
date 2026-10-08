// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.policy

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.slf4j.LoggerFactory
import org.tatrman.plan.v1.parseSchemaCode

/**
 * Loads row-level-security [Policy] instances from HOCON (DF-S01). Replaces the hardcoded
 * [DefaultPolicies.all] in production; [DefaultPolicies] survives as a test fixture only.
 *
 * Config shape — `validate.policies` is a list of policy objects:
 *
 * ```hocon
 * validate.policies = [
 *   {
 *     id = "tenant_isolation"
 *     description = "Restrict rows to the calling user's tenant"   # optional
 *     roles = ["tenant-scoped"]                                     # optional — applies only to holders
 *     exempt-roles = ["data-all"]                                   # optional — never applies to holders
 *     subject-attribute = "tenant_id"                               # optional — applies only to carriers
 *     match { type = "namespace", schema = "db", namespace = "dbo" }
 *     predicate {
 *       type = "eq"
 *       column = "tenant_id"
 *       value { kind = "user-attr", attribute = "tenant_id" }
 *     }
 *     column-rules = [                                              # optional (DF-S02)
 *       { column = "ssn",    action = "deny" }
 *       { column = "salary", action = "mask", mask-value { kind = "literal", value = "***", literal-type = "text" } }
 *     ]
 *   }
 * ]
 * ```
 *
 * `match.type` ∈ { `all`, `exact` (+ `qname = "schema.namespace.name"`), `namespace` (+ `schema`, `namespace`) }.
 * `predicate.type` ∈ { `eq` (+ `column`, `value`), `in` (+ `column`, `values = [...]`), `and`/`or` (+ `left`, `right`), `not` (+ `child`) }.
 * `value.kind` ∈ { `literal` (+ `value`, `literal-type`), `user-attr` (+ `attribute`) }.
 * `column-rules[].action` ∈ { `deny`, `mask` (+ optional `mask-value` = a `value`; absent = NULL) }.
 * `roles` / `exempt-roles` (LR C-5·1): non-empty lists of role names, matched exactly against the
 * caller's `auth_roles`. No `roles` = the policy applies to every caller; an exempt role always wins.
 * An empty list, or a name with surrounding whitespace, is a boot error: neither can be what the
 * author meant, and both fail silently at run time (an empty `roles` would gate every caller IN).
 * `subject-attribute`: the policy applies only to a caller whose identity carries this attribute
 * ([Policy.subjectAttribute]).
 *
 * A malformed policy aborts startup with a message naming the offending policy id (fail-fast).
 * If `validate.policies` is absent, returns an empty list (the service then applies no row-level
 * policies) — a warning is logged.
 */
object PolicyConfigLoader {
    private val logger = LoggerFactory.getLogger(PolicyConfigLoader::class.java)

    fun load(config: Config): List<Policy> {
        if (!config.hasPath(POLICIES_PATH)) {
            logger.warn("No '{}' configured — Validate will apply no row-level policies", POLICIES_PATH)
            return emptyList()
        }
        val entries = config.getConfigList(POLICIES_PATH)
        return entries.mapIndexed { index, entry ->
            val id = entry.optString("id") ?: "policy[$index]"
            try {
                parsePolicy(id, entry)
            } catch (e: PolicyConfigException) {
                throw e
            } catch (e: Exception) {
                throw PolicyConfigException("policy '$id': ${e.message}", e)
            }
        }
    }

    private fun parsePolicy(
        id: String,
        c: Config,
    ): Policy {
        require(c.hasPath("id")) { "policy at index has no 'id'" }
        require(c.hasPath("match")) { "policy '$id' has no 'match' block" }
        require(c.hasPath("predicate")) { "policy '$id' has no 'predicate' block" }
        return Policy(
            id = id,
            tableMatch = parseMatch(id, c.getConfig("match")),
            predicate = parsePredicate(id, c.getConfig("predicate")),
            description = c.optString("description") ?: "",
            columnRules =
                if (c.hasPath("column-rules")) {
                    c.getConfigList("column-rules").map { parseColumnRule(id, it) }
                } else {
                    emptyList()
                },
            roles = roleList(id, c, "roles"),
            exemptRoles = roleList(id, c, "exempt-roles"),
            subjectAttribute =
                c.optString("subject-attribute")?.also {
                    if (it.isBlank() || it != it.trim()) {
                        throw PolicyConfigException(
                            "policy '$id': 'subject-attribute' must be an attribute name, got '$it'",
                        )
                    }
                },
        )
    }

    /**
     * `roles` / `exempt-roles`: absent → empty; anything but a non-empty list of names without
     * surrounding whitespace → a boot error.
     */
    private fun roleList(
        id: String,
        c: Config,
        path: String,
    ): List<String> {
        if (!c.hasPath(path)) return emptyList()
        val roles =
            try {
                c.getStringList(path)
            } catch (e: ConfigException.WrongType) {
                throw PolicyConfigException(
                    "policy '$id': '$path' must be a list of role names, e.g. $path = [\"analyst\"]",
                    e,
                )
            }
        if (roles.isEmpty()) {
            throw PolicyConfigException(
                "policy '$id': '$path' is empty — name at least one role, or leave '$path' out",
            )
        }
        if (roles.any { it.isBlank() }) {
            throw PolicyConfigException("policy '$id': '$path' holds a blank role name")
        }
        roles.firstOrNull { it != it.trim() }?.let {
            throw PolicyConfigException("policy '$id': '$path' role name '$it' has surrounding whitespace")
        }
        return roles
    }

    private fun parseColumnRule(
        id: String,
        c: Config,
    ): ColumnRule =
        ColumnRule(
            column = c.getString("column"),
            action =
                when (val action = c.getString("action").lowercase()) {
                    "deny" -> ColumnAction.Deny
                    "mask" ->
                        ColumnAction.Mask(
                            maskValue =
                                if (c.hasPath(
                                        "mask-value",
                                    )
                                ) {
                                    parseValue(id, c.getConfig("mask-value"))
                                } else {
                                    null
                                },
                        )
                    else -> throw PolicyConfigException(
                        "policy '$id': unknown column-rule action '$action' (expected deny|mask)",
                    )
                },
        )

    private fun parseMatch(
        id: String,
        c: Config,
    ): TableMatcher =
        when (val type = c.getString("type").lowercase()) {
            "all" -> TableMatcher.All
            "exact" -> TableMatcher.Exact(parseQname(id, c.getString("qname")))
            "namespace" -> {
                val schemaToken = c.getString("schema")
                val schemaCode =
                    parseSchemaCode(schemaToken)
                        ?: throw PolicyConfigException(
                            "policy '$id': match.schema '$schemaToken' is not a known SchemaCode",
                        )
                TableMatcher.Namespace(
                    schemaCode = schemaCode,
                    namespace = c.getString("namespace"),
                )
            }
            else -> throw PolicyConfigException(
                "policy '$id': unknown match type '$type' (expected all|exact|namespace)",
            )
        }

    private fun parsePredicate(
        id: String,
        c: Config,
    ): PolicyPredicate =
        when (val type = c.getString("type").lowercase()) {
            "eq" -> PolicyPredicate.Eq(column = c.getString("column"), value = parseValue(id, c.getConfig("value")))
            "in" ->
                PolicyPredicate.In(
                    column = c.getString("column"),
                    values = c.getConfigList("values").map { parseValue(id, it) },
                )
            "and" ->
                PolicyPredicate.And(
                    left = parsePredicate(id, c.getConfig("left")),
                    right = parsePredicate(id, c.getConfig("right")),
                )
            "or" ->
                PolicyPredicate.Or(
                    left = parsePredicate(id, c.getConfig("left")),
                    right = parsePredicate(id, c.getConfig("right")),
                )
            "not" -> PolicyPredicate.Not(child = parsePredicate(id, c.getConfig("child")))
            else -> throw PolicyConfigException(
                "policy '$id': unknown predicate type '$type' (expected eq|in|and|or|not)",
            )
        }

    private fun parseValue(
        id: String,
        c: Config,
    ): PolicyValue =
        when (val kind = c.getString("kind").lowercase().replace("_", "-")) {
            "user-attr", "userattr", "user-attribute" -> PolicyValue.UserAttribute(attribute = c.getString("attribute"))
            "literal" ->
                PolicyValue.Literal(
                    value = if (c.hasPath("value")) c.getAnyRef("value") else null,
                    type = c.optString("literal-type") ?: c.optString("type") ?: "text",
                )
            else -> throw PolicyConfigException("policy '$id': unknown value kind '$kind' (expected literal|user-attr)")
        }

    private fun parseQname(
        id: String,
        dotted: String,
    ): QualifiedName {
        val parts = dotted.split(".")
        if (parts.size < 2) {
            throw PolicyConfigException(
                "policy '$id': match.qname '$dotted' must be 'schema.namespace.name' (or at least 'namespace.name')",
            )
        }
        val (schema, namespace, name) =
            if (parts.size >= 3) {
                Triple(parts[0], parts[1], parts.drop(2).joinToString("."))
            } else {
                Triple("", parts[0], parts[1])
            }
        val schemaCode =
            if (schema.isNotEmpty()) {
                parseSchemaCode(schema)
                    ?: org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED
            } else {
                org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED
            }
        return QualifiedName
            .newBuilder()
            .setSchemaCode(schemaCode)
            .setNamespace(namespace)
            .setName(name)
            .build()
    }

    private fun Config.optString(path: String): String? = if (hasPath(path)) getString(path) else null

    private const val POLICIES_PATH = "validate.policies"
}

class PolicyConfigException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
