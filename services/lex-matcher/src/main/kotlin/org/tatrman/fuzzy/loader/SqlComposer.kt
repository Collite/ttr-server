// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import org.tatrman.fuzzy.config.DatabaseConfig
import org.tatrman.fuzzy.config.MssqlConfig
import org.tatrman.fuzzy.config.PostgresConfig
import org.tatrman.plan.v1.QualifiedName

class SqlComposerException(
    message: String,
) : RuntimeException(message)

/**
 * Composes `SELECT pk, col FROM table` for an alias table (RS-12-γ, [composeAliasCandidates]) —
 * the only SQL the loader still composes. Member vocabularies arrive with a read plan the
 * translator rendered (MV-T2). Identifiers are validated against [VALID_IDENTIFIER_REGEX] and
 * dialect-quoted (Postgres `"x"`, MSSQL `[x]`) — the PK/value names come from metadata, never user
 * input, but the regex + quoting are the defence-in-depth that keeps a malformed model name from
 * becoming injection.
 */
fun buildSelect(
    tableQname: QualifiedName,
    pkLocalName: String,
    valueLocalName: String,
    dialect: DatabaseConfig,
): String {
    if (!VALID_IDENTIFIER_REGEX.matches(pkLocalName)) {
        throw SqlComposerException("Invalid PK local name: $pkLocalName")
    }
    if (!VALID_IDENTIFIER_REGEX.matches(valueLocalName)) {
        throw SqlComposerException("Invalid value local name: $valueLocalName")
    }

    val table = formatTableName(tableQname, dialect)
    val pk = formatIdentifier(pkLocalName, dialect)
    val col = formatIdentifier(valueLocalName, dialect)

    return "SELECT $pk, $col FROM $table"
}

private fun formatTableName(
    qname: QualifiedName,
    dialect: DatabaseConfig,
): String =
    when (dialect) {
        is PostgresConfig -> "\"${qname.namespace}\".\"${qname.name}\""
        is MssqlConfig -> "[${qname.namespace}].[${qname.name}]"
    }

private fun formatIdentifier(
    name: String,
    dialect: DatabaseConfig,
): String =
    when (dialect) {
        is PostgresConfig -> "\"$name\""
        is MssqlConfig -> "[$name]"
    }

private val VALID_IDENTIFIER_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
