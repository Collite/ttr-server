// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.diagnostics.RgDiagnostics
import org.tatrman.meta.v1.MemberVocabulary
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.orchestrator.TranslateResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.AttributeMappingTarget
import org.tatrman.ttr.metadata.model.DbColumn
import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.Er2DbAttributeMapping
import org.tatrman.ttr.metadata.model.MatchMethods
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.memberVocabularyCarriers
import org.tatrman.translate.v1.Language
import java.security.MessageDigest

/**
 * MV-T1 (member-vocabulary contracts §3) — one member vocabulary before its read plan is rendered.
 *
 * A member vocabulary is *the values one attribute takes over its entity's population*. Its
 * identity is the attribute ([category]); the population is the entity's, decided by the
 * translator from the entity mapping (table, view, `SqlQuery`) — never by this file, and never by
 * the loader.
 */
data class MemberVocabularyDraft(
    /** The carrier's qname dotted — byte-equal to its compiled-lexicon target ref. */
    val category: String,
    val carrier: QualifiedName,
    /** The owner: the attribute's entity, or the column's table (db-only estate). */
    val owner: QualifiedName,
    /** The source schema the projection is written in — ER for an attribute, DB for a column. */
    val sourceSchema: SchemaCode,
    val ownerLocal: String,
    val valueLocal: String,
    /** The owner's single key, or null when it has none or a composite one (RG-FUZ-001). */
    val key: QualifiedName?,
    val keyLocal: String?,
    /** `matchMethod ?: EXACT` — an indexed carrier with no authored method is matched exactly. */
    val matchMethod: String,
    /** Set when the projection cannot be rendered at all (RG-FUZ-003) — checked before the translator runs. */
    val unrenderable: String? = null,
)

object MemberVocabularies {
    /**
     * T4 — one draft per member-vocabulary carrier (`Model.memberVocabularyCarriers()`, the SAME
     * definition the lexicon compiler's `memberVocabulary` facet reads — so the archive and this RPC
     * cannot disagree about which vocabularies exist), in category order. Pure.
     */
    fun enumerate(model: Model): List<MemberVocabularyDraft> {
        val objects = model.objectByQname()
        val attributeTargets =
            model.mappings
                .filterIsInstance<Er2DbAttributeMapping>()
                .associate { it.attribute to it.target }
        return model.memberVocabularyCarriers().mapNotNull { carrier ->
            when (carrier) {
                is Attribute -> {
                    val entity = objects[carrier.entity] as? Entity
                    val keys = entity?.attributes.orEmpty().filter { it.isKey }
                    val key = keys.singleOrNull()
                    MemberVocabularyDraft(
                        category = carrier.qname.dotted(),
                        carrier = carrier.qname,
                        owner = carrier.entity,
                        sourceSchema = SchemaCode.ER,
                        ownerLocal = carrier.entity.name,
                        valueLocal = carrier.qname.local(),
                        key = key?.qname,
                        keyLocal = key?.qname?.local(),
                        matchMethod = carrier.search.matchMethod ?: MatchMethods.DEFAULT,
                        unrenderable =
                            (attributeTargets[carrier.qname] as? AttributeMappingTarget.Expression)?.let {
                                "the attribute maps to an expression (`${it.raw}`), which the translator's ER " +
                                    "catalog does not represent — it could not be queried by name either"
                            },
                    )
                }
                is DbColumn -> {
                    val table = objects[carrier.table] as? DbTable
                    val pk = table?.primaryKey.orEmpty().singleOrNull()
                    MemberVocabularyDraft(
                        category = carrier.qname.dotted(),
                        carrier = carrier.qname,
                        owner = carrier.table,
                        sourceSchema = SchemaCode.DB,
                        ownerLocal = carrier.table.name,
                        valueLocal = carrier.qname.local(),
                        key = pk?.let { carrier.table.copy(name = "${carrier.table.name}.$it") },
                        keyLocal = pk,
                        matchMethod = carrier.search.matchMethod ?: MatchMethods.DEFAULT,
                    )
                }
                else -> null
            }
        }
    }

    /**
     * The projection, in the carrier's own schema: `SELECT DISTINCT <key>, <value> FROM <owner>
     * ORDER BY <key>`. Identifiers are double-quoted (the translator parses `Lex.MYSQL_ANSI`), so an
     * attribute called `state` or `name` is never read as a keyword.
     */
    fun projection(draft: MemberVocabularyDraft): String {
        val key = quote(requireNotNull(draft.keyLocal))
        return "SELECT DISTINCT $key, ${quote(draft.valueLocal)} FROM ${quote(draft.ownerLocal)} ORDER BY $key"
    }

    private fun quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""

    private fun QualifiedName.local(): String = name.substringAfterLast('.')
}

/**
 * T5 — renders each draft's projection through the translator over [handle], the query path's own
 * `ModelHandle` (`SnapshotModelHandle` over this model's snapshot). That is the whole point: the
 * rows a member vocabulary indexes are the rows a query over the same entity reads, because the
 * same code decides both.
 */
class MemberVocabularyRenderer(
    private val handle: ModelHandle,
    private val modelVersion: String,
) {
    fun render(
        draft: MemberVocabularyDraft,
        dialect: SqlDialect,
    ): MemberVocabulary {
        val builder =
            MemberVocabulary
                .newBuilder()
                .setCategory(draft.category)
                .setAttribute(draft.carrier.toProto())
                .setEntity(draft.owner.toProto())
                .setKeyAttribute(draft.key?.dotted() ?: "")
                .setDialect(dialect.name)
                .setMatchMethod(draft.matchMethod)
        val readSql =
            when {
                draft.key == null -> {
                    builder.addDiagnostics(diagnostic("RG-FUZ-001", "category" to draft.category))
                    ""
                }
                draft.unrenderable != null -> {
                    builder.addDiagnostics(
                        diagnostic("RG-FUZ-003", "category" to draft.category, "reason" to draft.unrenderable),
                    )
                    ""
                }
                else ->
                    when (val result = translate(MemberVocabularies.projection(draft), draft.sourceSchema, dialect)) {
                        is TranslateResult.Success -> result.output
                        is TranslateResult.Failure -> {
                            builder.addDiagnostics(
                                diagnostic(
                                    "RG-FUZ-003",
                                    "category" to draft.category,
                                    "reason" to "${result.code}: ${result.message}",
                                ),
                            )
                            ""
                        }
                    }
            }
        return builder
            .setReadSql(readSql)
            .setVersion(version(modelVersion, readSql, builder.keyAttribute, draft.matchMethod))
            .build()
    }

    private fun translate(
        sql: String,
        sourceSchema: SchemaCode,
        dialect: SqlDialect,
    ): TranslateResult =
        try {
            Translator(handle).translate(
                source = sql,
                sourceLanguage = Language.SQL,
                targetLanguage = Language.SQL,
                targetSchema = SchemaCode.DB,
                targetDialect = dialect,
                sourceSchema = sourceSchema,
            )
        } catch (e: RuntimeException) {
            // The library reports its own failures as values; anything thrown is a defect in it, and
            // one unrenderable vocabulary must not take the whole listing down with it.
            TranslateResult.Failure("render_threw", e.message ?: e.javaClass.simpleName)
        }

    private fun diagnostic(
        id: String,
        vararg args: Pair<String, String>,
    ): ResponseMessage =
        ResponseMessage
            .newBuilder()
            .setSeverity(Severity.WARNING)
            .setCode(id)
            .setHumanMessage(RgDiagnostics.render(id, *args))
            .build()

    companion object {
        /** SHA-256 over (model version, read_sql, key_attribute, match_method) — contracts §3. */
        fun version(
            modelVersion: String,
            readSql: String,
            keyAttribute: String,
            matchMethod: String,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            for (part in listOf(modelVersion, readSql, keyAttribute, matchMethod)) {
                digest.update(part.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** The dialects a read plan can be rendered for — the enum minus its protobuf sentinels. */
        val DIALECTS: List<SqlDialect> =
            SqlDialect.entries.filter { it != SqlDialect.UNRECOGNIZED && it != SqlDialect.SQL_DIALECT_UNSPECIFIED }

        /** A request's dialect name → the translator's enum; "" ⇒ the translator's default (MSSQL). Null when unknown. */
        fun dialectOf(name: String): SqlDialect? =
            if (name.isBlank()) {
                SqlDialect.MSSQL
            } else {
                DIALECTS.firstOrNull {
                    it.name.equals(
                        name.trim(),
                        ignoreCase = true,
                    )
                }
            }
    }
}
