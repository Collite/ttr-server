// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.mcp

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.tatrman.veles.client.MetadataGrpcClient
import org.tatrman.plan.v1.parseSchemaCode

private fun buildOutputSchema(
    vararg required: String,
    block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
) = ToolSchema(
    properties = buildJsonObject(block),
    required = required.toList(),
)

private fun columnSchema() =
    buildJsonObject {
        putJsonObject("id") { put("type", "string") }
        putJsonObject("tableId") { put("type", "string") }
        putJsonObject("name") { put("type", "string") }
        putJsonObject("dataType") { put("type", "string") }
        putJsonObject("isNullable") { put("type", "boolean") }
        putJsonObject("isPrimaryKey") { put("type", "boolean") }
        putJsonObject("isForeignKey") { put("type", "boolean") }
        putJsonObject("fkTableName") { put("type", "string") }
        putJsonObject("fkColumnName") { put("type", "string") }
        putJsonObject("physical") { put("type", "boolean") }
    }

private fun tableSchema() =
    buildJsonObject {
        putJsonObject("id") { put("type", "string") }
        putJsonObject("name") { put("type", "string") }
        putJsonObject("schema") { put("type", "string") }
        putJsonObject("columns") {
            put("type", "array")
            put("items", columnSchema())
        }
        putJsonObject("physical") { put("type", "boolean") }
    }

private fun fieldSchema() =
    buildJsonObject {
        putJsonObject("id") { put("type", "string") }
        putJsonObject("entityId") { put("type", "string") }
        putJsonObject("name") { put("type", "string") }
        putJsonObject("columnName") { put("type", "string") }
        putJsonObject("type") { put("type", "string") }
        putJsonObject("primaryKey") { put("type", "boolean") }
        putJsonObject("description") { put("type", "string") }
        putJsonObject("allowedValues") { put("type", "array") }
        putJsonObject("searchable") { put("type", "boolean") }
    }

private fun entitySchema() =
    buildJsonObject {
        putJsonObject("id") { put("type", "string") }
        putJsonObject("name") { put("type", "string") }
        putJsonObject("tableName") { put("type", "string") }
        putJsonObject("tableCondition") { put("type", "string") }
        putJsonObject("entityType") { put("type", "string") }
        putJsonObject("nameColumn") { put("type", "string") }
        putJsonObject("codeColumn") { put("type", "string") }
        putJsonObject("labelPlural") { put("type", "string") }
        putJsonObject("description") { put("type", "string") }
        putJsonObject("aliases") { put("type", "array") }
        putJsonObject("fields") {
            put("type", "array")
            put("items", fieldSchema())
        }
        putJsonObject("sourceFile") { put("type", "string") }
        putJsonObject("compositePrimaryKey") { put("type", "array") }
        putJsonObject("security") { put("type", "object") }
    }

private fun relationshipSchema() =
    buildJsonObject {
        putJsonObject("id") { put("type", "string") }
        putJsonObject("name") { put("type", "string") }
        putJsonObject("fromEntity") { put("type", "string") }
        putJsonObject("toEntity") { put("type", "string") }
        putJsonObject("type") { put("type", "string") }
        putJsonObject("joinClause") { put("type", "string") }
        putJsonObject("description") { put("type", "string") }
        putJsonObject("sourceFile") { put("type", "string") }
    }

private fun roleSchema() =
    buildJsonObject {
        putJsonObject("label") { put("type", "object") }
        putJsonObject("description") { put("type", "string") }
        putJsonObject("search") { put("type", "object") }
    }

private fun drillMapSchema() =
    buildJsonObject {
        putJsonObject("name") { put("type", "string") }
        putJsonObject("fromPattern") { put("type", "string") }
        putJsonObject("toPattern") { put("type", "string") }
        putJsonObject("argMapping") { put("type", "object") }
        putJsonObject("explicit") { put("type", "boolean") }
        putJsonObject("overrideAuto") { put("type", "boolean") }
        putJsonObject("display") { put("type", "object") }
        putJsonObject("sourceFile") { put("type", "string") }
    }

private fun packageVersionSchema() =
    buildJsonObject {
        putJsonObject("packageName") { put("type", "string") }
        putJsonObject("contentHash") { put("type", "string") }
        putJsonObject("loadedAt") { put("type", "string") }
    }

private fun viewSchema() =
    buildJsonObject {
        putJsonObject("id") { put("type", "string") }
        putJsonObject("name") { put("type", "string") }
        putJsonObject("schema") { put("type", "string") }
        putJsonObject("columns") {
            put("type", "array")
            put("items", columnSchema())
        }
        putJsonObject("physical") { put("type", "boolean") }
    }

private fun parameterSchema() =
    buildJsonObject {
        putJsonObject("name") { put("type", "string") }
        putJsonObject("type") { put("type", "string") }
        putJsonObject("default") { put("type", "string") }
    }

private fun sqlQuerySchema() =
    buildJsonObject {
        putJsonObject("id") { put("type", "string") }
        putJsonObject("name") { put("type", "string") }
        putJsonObject("description") { put("type", "string") }
        putJsonObject("pattern") { put("type", "string") }
        putJsonObject("patternAlt") { put("type", "string") }
        putJsonObject("entities") { put("type", "array") }
        putJsonObject("tested") { put("type", "boolean") }
        putJsonObject("testValues") { put("type", "object") }
        putJsonObject("paramLabels") { put("type", "object") }
        putJsonObject("parameters") {
            put("type", "array")
            put("items", parameterSchema())
        }
        putJsonObject("sqlTemplate") { put("type", "string") }
        putJsonObject("example") { put("type", "object") }
        putJsonObject("sourceFile") { put("type", "string") }
        putJsonObject("security") { put("type", "object") }
    }

private fun storedProcedureSchema() =
    buildJsonObject {
        putJsonObject("id") { put("type", "string") }
        putJsonObject("name") { put("type", "string") }
        putJsonObject("description") { put("type", "string") }
        putJsonObject("storedProcedureName") { put("type", "string") }
        putJsonObject("parameters") {
            put("type", "array")
            put("items", parameterSchema())
        }
        putJsonObject("result") {
            put("type", "array")
            put("items", parameterSchema())
        }
        putJsonObject("sourceFile") { put("type", "string") }
        putJsonObject("security") { put("type", "object") }
    }

class Tools(
    private val metadataGrpcClient: MetadataGrpcClient? = null,
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(Tools::class.java)

    private fun errStructured(
        message: String,
        errorCode: String = "EXECUTION_ERROR",
    ) = buildJsonObject {
        put("errorCode", errorCode)
        put("error", message)
        put("message", message)
        put("extras", buildJsonObject { })
    }

    val getTablesTool =
        Tool(
            name = "get_tables",
            description = "List all available database tables with their schemas",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject { },
                    required = emptyList(),
                ),
            outputSchema =
                buildOutputSchema {
                    putJsonObject("tables") {
                        put("type", "array")
                        put("items", tableSchema())
                    }
                },
        )

    val getTableDetailsTool =
        Tool(
            name = "get_table_details",
            description = "Get detailed information about a specific table including columns, types, and keys",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "id",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "Table identifier (e.g. db.dbo.customer_address)")
                                },
                            )
                        },
                    required = listOf("id"),
                ),
            outputSchema = ToolSchema(properties = tableSchema()),
        )

    val getEntitiesTool =
        Tool(
            name = "get_entities",
            description = "List all logical model entities",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "package",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "Filter by package name (e.g. hartland)")
                                },
                            )
                        },
                    required = emptyList(),
                ),
            outputSchema =
                buildOutputSchema {
                    putJsonObject("entities") {
                        put("type", "array")
                        put("items", entitySchema())
                    }
                },
        )

    val getEntityDetailsTool =
        Tool(
            name = "get_entity_details",
            description = "Get detailed information about a specific entity including fields and relationships",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "id",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "Entity identifier")
                                },
                            )
                        },
                    required = listOf("id"),
                ),
            outputSchema = ToolSchema(properties = entitySchema()),
        )

    val getRelationshipsTool =
        Tool(
            name = "get_relationships",
            description = "List all relationships between entities",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject { },
                    required = emptyList(),
                ),
            outputSchema =
                buildOutputSchema {
                    putJsonObject("relationships") {
                        put("type", "array")
                        put("items", relationshipSchema())
                    }
                },
        )

    val getPatternQueriesTool =
        Tool(
            name = "get_pattern_queries",
            description = "List all available pattern query templates",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "package",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "Filter by package name (e.g. hartland)")
                                },
                            )
                        },
                    required = emptyList(),
                ),
            outputSchema =
                buildOutputSchema {
                    putJsonObject("patternQueries") {
                        put("type", "array")
                        put("items", sqlQuerySchema())
                    }
                },
        )

    val getPatternQueryDetailsTool =
        Tool(
            name = "get_pattern_query_details",
            description = "Get details of a specific pattern query including SQL template and parameters",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "id",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "Pattern query identifier")
                                },
                            )
                        },
                    required = listOf("id"),
                ),
            outputSchema = ToolSchema(properties = sqlQuerySchema()),
        )

    val getSqlQueriesTool =
        Tool(
            name = "get_sql_queries",
            description = "List all predefined SQL queries",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "package",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "Filter by package name (e.g. hartland)")
                                },
                            )
                        },
                    required = emptyList(),
                ),
            outputSchema =
                buildOutputSchema {
                    putJsonObject("sqlQueries") {
                        put("type", "array")
                        put("items", sqlQuerySchema())
                    }
                },
        )

    val getSqlQueryDetailsTool =
        Tool(
            name = "get_sql_query_details",
            description = "Get details of a specific SQL query including SQL template and parameters",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "id",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "SQL query identifier")
                                },
                            )
                        },
                    required = listOf("id"),
                ),
            outputSchema = ToolSchema(properties = sqlQuerySchema()),
        )

    val getStoredProceduresTool =
        Tool(
            name = "get_stored_procedures",
            description = "List all available stored procedures",
            inputSchema =
                ToolSchema(
                    properties = buildJsonObject { },
                    required = emptyList(),
                ),
            outputSchema =
                buildOutputSchema {
                    putJsonObject("storedProcedures") {
                        put("type", "array")
                        put("items", storedProcedureSchema())
                    }
                },
        )

    val getStoredProcedureDetailsTool =
        Tool(
            name = "get_stored_procedure_details",
            description = "Get details of a specific stored procedure including parameters",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "id",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "Stored procedure identifier")
                                },
                            )
                        },
                    required = listOf("id"),
                ),
            outputSchema = ToolSchema(properties = storedProcedureSchema()),
        )

    val getModelTool: Tool =
        Tool(
            name = "get_model",
            description =
                "Fetch the full ModelBundle (entities, relations, tables, views, pattern queries, " +
                    "named queries, roles, drill maps, package versions) for one or more packages in a single round-trip.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "packages",
                                buildJsonObject {
                                    put("type", "array")
                                    put("items", buildJsonObject { put("type", "string") })
                                    put("description", "List of package names to fetch (e.g. [\"hartland\"])")
                                },
                            )
                            put(
                                "locale",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "Locale for localised strings (e.g. \"cs\")")
                                },
                            )
                            put(
                                "include_search_hints",
                                buildJsonObject {
                                    put("type", "boolean")
                                    put("description", "Include search hints (aliases, keywords)")
                                },
                            )
                            put(
                                "include_roles",
                                buildJsonObject {
                                    put("type", "boolean")
                                    put("description", "Include conceptual roles")
                                },
                            )
                            put(
                                "include_drill_map",
                                buildJsonObject {
                                    put("type", "boolean")
                                    put("description", "Include drill maps")
                                },
                            )
                        },
                    required = listOf("packages"),
                ),
            outputSchema =
                buildOutputSchema {
                    putJsonObject("entities") {
                        put("type", "array")
                        put("items", entitySchema())
                    }
                    putJsonObject("relations") {
                        put("type", "array")
                        put("items", relationshipSchema())
                    }
                    putJsonObject("tables") {
                        put("type", "array")
                        put("items", tableSchema())
                    }
                    putJsonObject("views") {
                        put("type", "array")
                        put("items", viewSchema())
                    }
                    putJsonObject("patternQueries") {
                        put("type", "array")
                        put("items", sqlQuerySchema())
                    }
                    putJsonObject("namedQueries") {
                        put("type", "array")
                        put("items", sqlQuerySchema())
                    }
                    putJsonObject("roles") {
                        put("type", "array")
                        put("items", roleSchema())
                    }
                    putJsonObject("drillMaps") {
                        put("type", "array")
                        put("items", drillMapSchema())
                    }
                    putJsonObject("packageVersions") {
                        put("type", "array")
                        put("items", packageVersionSchema())
                    }
                },
        )

    // ---------------------------------------------------------------------
    // Golem P4 S4.2 — resolve a subject `area` to its package set. A Golem Shem
    // declaring `areas: [hartland]` resolves it here to the concrete packages
    // it must pull (via get_model). Zero-logic wrapper over veles's
    // ResolveArea RPC.
    // ---------------------------------------------------------------------

    val resolveAreaTool: Tool =
        Tool(
            name = "resolve_area",
            description =
                "Resolve a subject area (e.g. 'hartland') to the package set it spans, plus its " +
                    "description and tags. A Golem Shem uses this to expand its `areas: [...]` list " +
                    "into the concrete Veles packages to fetch via get_model. " +
                    "`found=false` when the area is unknown.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "area",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "Bare area name, e.g. 'hartland'")
                                },
                            )
                        },
                    required = listOf("area"),
                ),
            outputSchema =
                buildOutputSchema {
                    putJsonObject("packages") {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    }
                    putJsonObject("description") { put("type", "string") }
                    putJsonObject("tags") {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    }
                    putJsonObject("found") { put("type", "boolean") }
                },
        )

    suspend fun resolveAreaCallback(request: CallToolRequest): CallToolResult {
        val grpc = metadataGrpcClient ?: return notWiredResult("resolve_area")
        val args = request.params.arguments as? kotlinx.serialization.json.JsonObject
        val area =
            args?.get("area")?.let { (it as? JsonPrimitive)?.content }
                ?: return CallToolResult(
                    content = listOf(TextContent(text = "Missing area parameter")),
                    isError = true,
                    structuredContent = errStructured("Missing area parameter"),
                )
        return try {
            val resp = grpc.resolveArea(area)
            val structured =
                buildJsonObject {
                    put("packages", buildJsonArray { for (p in resp.packagesList) add(JsonPrimitive(p)) })
                    put("description", JsonPrimitive(resp.description))
                    put("tags", buildJsonArray { for (t in resp.tagsList) add(JsonPrimitive(t)) })
                    put("found", JsonPrimitive(resp.found))
                }
            val text = McpJson.encodeToString(structured)
            logger.info(
                "resolve_area completed | success | area={} | found={} | packages={} | isError=false",
                area,
                resp.found,
                resp.packagesList.size,
            )
            // Surface veles-side warnings (e.g. area_not_found) as MCP text so callers can log them.
            val warningText =
                if (resp.messagesList.isNotEmpty()) {
                    "\n\n[veles messages] " +
                        resp.messagesList.joinToString("; ") { "[${it.code}] ${it.humanMessage}" }
                } else {
                    ""
                }
            CallToolResult(
                content = listOf(TextContent(text = text + warningText)),
                structuredContent = structured,
            )
        } catch (e: Exception) {
            logger.error("Error executing resolve_area tool for area: $area", e)
            errorResult("resolve_area", "Failed to resolve area: ${e.message}")
        }
    }

    suspend fun getTablesCallback(request: CallToolRequest): CallToolResult {
        val grpc = metadataGrpcClient ?: return notWiredResult("get_tables")
        return try {
            val resp = grpc.listObjects(kind = "table", packageFilter = packageArg(request))
            val tablesJson =
                buildJsonArray {
                    for (obj in resp.itemsList) {
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(obj.qualifiedName.toDottedQname()))
                                put("name", JsonPrimitive(obj.localName))
                                put("schema", JsonPrimitive(""))
                                put("description", JsonPrimitive(obj.description))
                                put("sourceFile", JsonPrimitive(obj.sourceFile))
                                put("columns", buildJsonArray { })
                                put("physical", JsonPrimitive(true))
                            },
                        )
                    }
                }
            val text = McpJson.encodeToString(tablesJson)
            logger.info("get_tables completed | success | tableCount={} | isError=false", tablesJson.size)
            CallToolResult(
                content = listOf(TextContent(text = text)),
                structuredContent = buildJsonObject { put("tables", tablesJson) },
            )
        } catch (e: Exception) {
            logger.error("Error executing get_tables tool", e)
            errorResult("get_tables", "Failed to fetch tables: ${e.message}")
        }
    }

    suspend fun getTableDetailsCallback(request: CallToolRequest): CallToolResult {
        val id =
            request.params.arguments?.let {
                if (it is kotlinx.serialization.json.JsonObject) {
                    it["id"]?.let { v -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content }
                } else {
                    null
                }
            } ?: run {
                val result =
                    CallToolResult(
                        content = listOf(TextContent(text = "Missing id parameter")),
                        isError = true,
                        structuredContent = errStructured("Missing id parameter"),
                    )
                logger.info("get_table_details completed | missing id | isError={}", result.isError)
                logger.debug("get_table_details missing id result: {}", result)
                return result
            }

        val grpc = metadataGrpcClient ?: return notWiredResult("get_table_details")
        return try {
            val resp = grpc.getObject(parseQname(id, defaultSchema = "db", defaultNamespace = "table"))
            if (!resp.hasObjectDescriptor()) {
                return notFoundResult("get_table_details", id, resp.messagesList)
            }
            val obj = resp.objectDescriptor
            val detail = resp.table
            val columns =
                buildJsonArray {
                    for (col in detail.columnsList) {
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(""))
                                put("tableId", JsonPrimitive(obj.qualifiedName.toDottedQname()))
                                put("name", JsonPrimitive(col.name))
                                put("dataType", JsonPrimitive(col.dataType))
                                put("isNullable", JsonPrimitive(col.nullable))
                                put("isPrimaryKey", JsonPrimitive(detail.primaryKeyList.contains(col.name)))
                                put("isForeignKey", JsonPrimitive(false))
                                put("fkTableName", JsonPrimitive(""))
                                put("fkColumnName", JsonPrimitive(""))
                                put("physical", JsonPrimitive(true))
                            },
                        )
                    }
                }
            val structured =
                buildJsonObject {
                    put("id", JsonPrimitive(obj.qualifiedName.toDottedQname()))
                    put("name", JsonPrimitive(obj.localName))
                    put("schema", JsonPrimitive(""))
                    put("description", JsonPrimitive(obj.description))
                    put("sourceFile", JsonPrimitive(obj.sourceFile))
                    put("columns", columns)
                    put("physical", JsonPrimitive(true))
                }
            val text = McpJson.encodeToString(structured)
            logger.info("get_table_details completed | success | id={} | isError=false", id)
            CallToolResult(content = listOf(TextContent(text = text)), structuredContent = structured)
        } catch (e: Exception) {
            logger.error("Error executing get_table_details tool for id: $id", e)
            errorResult("get_table_details", "Failed to fetch table details: ${e.message}")
        }
    }

    suspend fun getEntitiesCallback(request: CallToolRequest): CallToolResult {
        val grpc = metadataGrpcClient ?: return notWiredResult("get_entities")
        return try {
            val resp = grpc.listObjects(kind = "entity", packageFilter = packageArg(request))
            val entitiesJson =
                buildJsonArray {
                    for (obj in resp.itemsList) {
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(obj.qualifiedName.toDottedQname()))
                                put("name", JsonPrimitive(obj.localName))
                                put("tableName", JsonPrimitive(""))
                                put("entityType", JsonPrimitive(""))
                                put("sourceFile", JsonPrimitive(obj.sourceFile))
                                put("description", JsonPrimitive(obj.description))
                                put("aliases", buildJsonArray { })
                                put("fields", buildJsonArray { })
                            },
                        )
                    }
                }
            val text = McpJson.encodeToString(entitiesJson)
            logger.info("get_entities completed | success | entityCount={} | isError=false", entitiesJson.size)
            CallToolResult(
                content = listOf(TextContent(text = text)),
                structuredContent = buildJsonObject { put("entities", entitiesJson) },
            )
        } catch (e: Exception) {
            logger.error("Error executing get_entities tool", e)
            errorResult("get_entities", "Failed to fetch entities: ${e.message}")
        }
    }

    suspend fun getEntityDetailsCallback(request: CallToolRequest): CallToolResult {
        val id =
            request.params.arguments?.let {
                if (it is kotlinx.serialization.json.JsonObject) {
                    it["id"]?.let { v -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content }
                } else {
                    null
                }
            } ?: run {
                val result =
                    CallToolResult(
                        content = listOf(TextContent(text = "Missing id parameter")),
                        isError = true,
                        structuredContent = errStructured("Missing id parameter"),
                    )
                logger.info("get_entity_details completed | missing id | isError={}", result.isError)
                logger.debug("get_entity_details missing id result: {}", result)
                return result
            }

        val grpc = metadataGrpcClient ?: return notWiredResult("get_entity_details")
        return try {
            val resp = grpc.getObject(parseQname(id, defaultSchema = "er", defaultNamespace = "entity"))
            if (!resp.hasObjectDescriptor()) {
                return notFoundResult("get_entity_details", id, resp.messagesList)
            }
            val obj = resp.objectDescriptor
            val detail = resp.entity
            val structured =
                buildJsonObject {
                    put("id", JsonPrimitive(obj.qualifiedName.toDottedQname()))
                    put("name", JsonPrimitive(obj.localName))
                    put("tableName", JsonPrimitive(""))
                    put("entityType", JsonPrimitive(""))
                    put("nameColumn", JsonPrimitive(detail.nameAttribute))
                    put("codeColumn", JsonPrimitive(detail.codeAttribute))
                    put("labelPlural", JsonPrimitive(detail.labelPlural))
                    put("description", JsonPrimitive(obj.description))
                    put("aliases", buildJsonArray { for (a in detail.aliasesList) add(JsonPrimitive(a)) })
                    // Per-attribute fields are inlined by get_model; the detail RPC returns the
                    // entity's scalar metadata only. Callers needing fields use get_model.
                    put("fields", buildJsonArray { })
                    put("sourceFile", JsonPrimitive(obj.sourceFile))
                    put("compositePrimaryKey", buildJsonArray { })
                    put("security", buildJsonObject { })
                }
            val text = McpJson.encodeToString(structured)
            logger.info("get_entity_details completed | success | id={} | isError=false", id)
            CallToolResult(content = listOf(TextContent(text = text)), structuredContent = structured)
        } catch (e: Exception) {
            logger.error("Error executing get_entity_details tool for id: $id", e)
            errorResult("get_entity_details", "Failed to fetch entity details: ${e.message}")
        }
    }

    suspend fun getRelationshipsCallback(request: CallToolRequest): CallToolResult {
        val grpc = metadataGrpcClient ?: return notWiredResult("get_relationships")
        return try {
            val resp = grpc.listObjects(kind = "relation", packageFilter = packageArg(request))
            val relationshipsJson =
                buildJsonArray {
                    for (obj in resp.itemsList) {
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(obj.qualifiedName.toDottedQname()))
                                put("name", JsonPrimitive(obj.localName))
                                put("fromEntity", JsonPrimitive(""))
                                put("toEntity", JsonPrimitive(""))
                                put("type", JsonPrimitive(""))
                                put("joinClause", JsonPrimitive(""))
                                put("description", JsonPrimitive(obj.description))
                                put("sourceFile", JsonPrimitive(obj.sourceFile))
                            },
                        )
                    }
                }
            val text = McpJson.encodeToString(relationshipsJson)
            logger.info(
                "get_relationships completed | success | relationshipCount={} | isError=false",
                relationshipsJson.size,
            )
            CallToolResult(
                content = listOf(TextContent(text = text)),
                structuredContent = buildJsonObject { put("relationships", relationshipsJson) },
            )
        } catch (e: Exception) {
            logger.error("Error executing get_relationships tool", e)
            errorResult("get_relationships", "Failed to fetch relationships: ${e.message}")
        }
    }

    suspend fun getPatternQueriesCallback(request: CallToolRequest): CallToolResult {
        val grpc = metadataGrpcClient ?: return notWiredResult("get_pattern_queries")
        return try {
            val resp = grpc.listQueries(packageFilter = packageArg(request))
            val patternQueriesJson = encodeQueryDescriptorsJson(resp.itemsList)
            val text = McpJson.encodeToString(patternQueriesJson)
            logger.info(
                "get_pattern_queries completed | success | patternQueryCount={} | isError=false",
                patternQueriesJson.size,
            )
            CallToolResult(
                content = listOf(TextContent(text = text)),
                structuredContent = buildJsonObject { put("patternQueries", patternQueriesJson) },
            )
        } catch (e: Exception) {
            logger.error("Error executing get_pattern_queries tool", e)
            errorResult("get_pattern_queries", "Failed to fetch pattern queries: ${e.message}")
        }
    }

    suspend fun getPatternQueryDetailsCallback(request: CallToolRequest): CallToolResult {
        val id =
            request.params.arguments?.let {
                if (it is kotlinx.serialization.json.JsonObject) {
                    it["id"]?.let { v -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content }
                } else {
                    null
                }
            } ?: run {
                val result =
                    CallToolResult(
                        content = listOf(TextContent(text = "Missing id parameter")),
                        isError = true,
                        structuredContent = errStructured("Missing id parameter"),
                    )
                logger.info("get_pattern_query_details completed | missing id | isError={}", result.isError)
                logger.debug("get_pattern_query_details missing id result: {}", result)
                return result
            }

        val grpc = metadataGrpcClient ?: return notWiredResult("get_pattern_query_details")
        return try {
            val resp = grpc.getQuery(parseQname(id, defaultSchema = "", defaultNamespace = "query"))
            if (!resp.hasObjectDescriptor()) {
                return notFoundResult("get_pattern_query_details", id, resp.messagesList)
            }
            val structured = encodeQueryDetailJson(resp)
            val text = McpJson.encodeToString(structured)
            logger.info("get_pattern_query_details completed | success | id={} | isError=false", id)
            CallToolResult(content = listOf(TextContent(text = text)), structuredContent = structured)
        } catch (e: Exception) {
            logger.error("Error executing get_pattern_query_details tool for id: $id", e)
            errorResult("get_pattern_query_details", "Failed to fetch pattern query details: ${e.message}")
        }
    }

    suspend fun getSqlQueriesCallback(request: CallToolRequest): CallToolResult {
        val grpc = metadataGrpcClient ?: return notWiredResult("get_sql_queries")
        return try {
            val resp = grpc.listQueries(packageFilter = packageArg(request))
            val sqlQueriesJson = encodeQueryDescriptorsJson(resp.itemsList)
            val text = McpJson.encodeToString(sqlQueriesJson)
            logger.info(
                "get_sql_queries completed | success | sqlQueryCount={} | isError=false",
                sqlQueriesJson.size,
            )
            CallToolResult(
                content = listOf(TextContent(text = text)),
                structuredContent = buildJsonObject { put("sqlQueries", sqlQueriesJson) },
            )
        } catch (e: Exception) {
            logger.error("Error executing get_sql_queries tool", e)
            errorResult("get_sql_queries", "Failed to fetch SQL queries: ${e.message}")
        }
    }

    suspend fun getSqlQueryDetailsCallback(request: CallToolRequest): CallToolResult {
        val id =
            request.params.arguments?.let {
                if (it is kotlinx.serialization.json.JsonObject) {
                    it["id"]?.let { v -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content }
                } else {
                    null
                }
            } ?: run {
                val result =
                    CallToolResult(
                        content = listOf(TextContent(text = "Missing id parameter")),
                        isError = true,
                        structuredContent = errStructured("Missing id parameter"),
                    )
                logger.info("get_sql_query_details completed | missing id | isError={}", result.isError)
                logger.debug("get_sql_query_details missing id result: {}", result)
                return result
            }

        val grpc = metadataGrpcClient ?: return notWiredResult("get_sql_query_details")
        return try {
            val resp = grpc.getQuery(parseQname(id, defaultSchema = "", defaultNamespace = "query"))
            if (!resp.hasObjectDescriptor()) {
                return notFoundResult("get_sql_query_details", id, resp.messagesList)
            }
            val structured = encodeQueryDetailJson(resp)
            val text = McpJson.encodeToString(structured)
            logger.info("get_sql_query_details completed | success | id={} | isError=false", id)
            CallToolResult(content = listOf(TextContent(text = text)), structuredContent = structured)
        } catch (e: Exception) {
            logger.error("Error executing get_sql_query_details tool for id: $id", e)
            errorResult("get_sql_query_details", "Failed to fetch SQL query details: ${e.message}")
        }
    }

    suspend fun getStoredProceduresCallback(request: CallToolRequest): CallToolResult {
        val grpc = metadataGrpcClient ?: return notWiredResult("get_stored_procedures")
        return try {
            val resp = grpc.listObjects(kind = "procedure", packageFilter = packageArg(request))
            val proceduresJson =
                buildJsonArray {
                    for (obj in resp.itemsList) {
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(obj.qualifiedName.toDottedQname()))
                                put("name", JsonPrimitive(obj.localName))
                                put("description", JsonPrimitive(obj.description))
                                put("storedProcedureName", JsonPrimitive(obj.localName))
                                put("parameters", buildJsonArray { })
                                put("result", buildJsonArray { })
                                put("sourceFile", JsonPrimitive(obj.sourceFile))
                                put("security", buildJsonObject { })
                            },
                        )
                    }
                }
            val text = McpJson.encodeToString(proceduresJson)
            logger.info(
                "get_stored_procedures completed | success | procedureCount={} | isError=false",
                proceduresJson.size,
            )
            CallToolResult(
                content = listOf(TextContent(text = text)),
                structuredContent = buildJsonObject { put("storedProcedures", proceduresJson) },
            )
        } catch (e: Exception) {
            logger.error("Error executing get_stored_procedures tool", e)
            errorResult("get_stored_procedures", "Failed to fetch stored procedures: ${e.message}")
        }
    }

    suspend fun getStoredProcedureDetailsCallback(request: CallToolRequest): CallToolResult {
        val id =
            request.params.arguments?.let {
                if (it is kotlinx.serialization.json.JsonObject) {
                    it["id"]?.let { v -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content }
                } else {
                    null
                }
            } ?: run {
                val result =
                    CallToolResult(
                        content = listOf(TextContent(text = "Missing id parameter")),
                        isError = true,
                        structuredContent = errStructured("Missing id parameter"),
                    )
                logger.info("get_stored_procedure_details completed | missing id | isError={}", result.isError)
                logger.debug("get_stored_procedure_details missing id result: {}", result)
                return result
            }

        val grpc = metadataGrpcClient ?: return notWiredResult("get_stored_procedure_details")
        return try {
            val resp = grpc.getObject(parseQname(id, defaultSchema = "db", defaultNamespace = "procedure"))
            if (!resp.hasObjectDescriptor()) {
                return notFoundResult("get_stored_procedure_details", id, resp.messagesList)
            }
            val obj = resp.objectDescriptor
            val detail = resp.procedure
            val structured =
                buildJsonObject {
                    put("id", JsonPrimitive(obj.qualifiedName.toDottedQname()))
                    put("name", JsonPrimitive(obj.localName))
                    put("description", JsonPrimitive(obj.description))
                    put("storedProcedureName", JsonPrimitive(obj.localName))
                    put(
                        "parameters",
                        buildJsonArray {
                            for (p in detail.parametersList) {
                                add(
                                    buildJsonObject {
                                        put("name", JsonPrimitive(p.name))
                                        put("type", JsonPrimitive(p.dataType))
                                        put("default", JsonPrimitive(""))
                                    },
                                )
                            }
                        },
                    )
                    put("result", buildJsonArray { })
                    put("sourceFile", JsonPrimitive(obj.sourceFile))
                    put("security", buildJsonObject { })
                }
            val text = McpJson.encodeToString(structured)
            logger.info("get_stored_procedure_details completed | success | id={} | isError=false", id)
            CallToolResult(content = listOf(TextContent(text = text)), structuredContent = structured)
        } catch (e: Exception) {
            logger.error("Error executing get_stored_procedure_details tool for id: $id", e)
            errorResult("get_stored_procedure_details", "Failed to fetch stored procedure details: ${e.message}")
        }
    }

    suspend fun getModelCallback(request: CallToolRequest): CallToolResult {
        val args = request.params.arguments as? kotlinx.serialization.json.JsonObject
        val packagesArg = args?.get("packages")
        val packagesList: List<String> =
            when (val p = packagesArg) {
                is kotlinx.serialization.json.JsonArray ->
                    p.mapNotNull {
                        (it as? kotlinx.serialization.json.JsonPrimitive)
                            ?.content
                    }
                else -> emptyList()
            }
        if (packagesList.isEmpty()) {
            val result =
                CallToolResult(
                    content = listOf(TextContent(text = "packages must be a non-empty array")),
                    isError = true,
                    structuredContent =
                        errStructured(
                            "packages must be a non-empty array",
                            errorCode = "EMPTY_PACKAGES",
                        ),
                )
            logger.info("get_model completed | empty_packages | isError={}", result.isError)
            logger.debug("get_model empty_packages result: {}", result)
            return result
        }
        val locale = args?.get("locale")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: ""
        val includeSearchHints =
            args?.get("include_search_hints")?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
            }
                ?: true
        val includeRoles =
            args
                ?.get(
                    "include_roles",
                )?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() }
                ?: true
        val includeDrillMap =
            args?.get("include_drill_map")?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
            }
                ?: true

        val grpc = metadataGrpcClient ?: return notWiredResult("get_model")
        return try {
            val resp = grpc.getModel(packagesList, locale, includeSearchHints, includeRoles, includeDrillMap)
            val model = resp.model
            // golem's PackageContext keys + prunes on package-led qnames
            // (`<package>.er.entity.x`). The metadata service leaves
            // QualifiedName.package empty (package is a sourceFile/path concept), so
            // derive it here from each object's sourceFile. Map bare→package is reused
            // to package-prefix relation endpoints so they still match entity keys.
            val entityPkgByQname = HashMap<String, String>()
            val entitiesArray =
                buildJsonArray {
                    for (entity in model.entitiesList) {
                        val obj = entity.objectDescriptor
                        val det = entity.detail
                        val pkg = packageFromSource(obj.sourceFile, packagesList)
                        entityPkgByQname[obj.qualifiedName.toDottedQname()] = pkg
                        add(
                            buildJsonObject {
                                val dotted = obj.qualifiedName.toPackagedDottedQname(obj.sourceFile, packagesList)
                                put("id", JsonPrimitive(dotted))
                                put("qname", JsonPrimitive(dotted))
                                put("name", JsonPrimitive(obj.localName))
                                put("nameAttribute", JsonPrimitive(det.nameAttribute))
                                put("codeAttribute", JsonPrimitive(det.codeAttribute))
                                put("nameColumn", JsonPrimitive(det.nameAttribute))
                                put("codeColumn", JsonPrimitive(det.codeAttribute))
                                put("labelPlural", JsonPrimitive(det.labelPlural))
                                put("description", JsonPrimitive(obj.description))
                                put(
                                    "aliases",
                                    buildJsonArray { for (a in det.aliasesList) add(JsonPrimitive(a)) },
                                )
                                // Per-attribute details, now inlined via ModelBundleAttribute
                                // (entity.attributesList). Each carries the attribute's
                                // ObjectDescriptor + AttributeDetail — enough for callers
                                // to render the entity schema without a second GetObject.
                                put(
                                    "fields",
                                    buildJsonArray {
                                        for (attr in entity.attributesList) {
                                            val ao = attr.objectDescriptor
                                            val ad = attr.detail
                                            add(
                                                buildJsonObject {
                                                    // Field names mirror golem's
                                                    // PackageContext._field_from_raw expectations
                                                    // (camelCase isKey/primaryKey; the camelCase
                                                    // convention is used by the surrounding
                                                    // entity JSON too).
                                                    put("name", JsonPrimitive(ao.localName))
                                                    put("qname", JsonPrimitive(ao.qualifiedName.toDottedQname()))
                                                    put("type", JsonPrimitive(ad.type))
                                                    put("isKey", JsonPrimitive(ad.isKey))
                                                    put("primaryKey", JsonPrimitive(ad.isKey))
                                                    put("nullable", JsonPrimitive(ad.nullable))
                                                    // columnName: mapping er→db lives in
                                                    // er2db_attribute, not inlined here. Leave
                                                    // empty for now; golem falls back to name.
                                                    put("columnName", JsonPrimitive(""))
                                                    put("description", JsonPrimitive(ao.description))
                                                    if (ad.hasSearch()) {
                                                        put("searchable", JsonPrimitive(ad.search.searchable))
                                                        put("fuzzy", JsonPrimitive(ad.search.fuzzy))
                                                    } else {
                                                        put("searchable", JsonPrimitive(false))
                                                        put("fuzzy", JsonPrimitive(false))
                                                    }
                                                    put("allowedValues", buildJsonArray { })
                                                },
                                            )
                                        }
                                    },
                                )
                                put("sourceFile", JsonPrimitive(obj.sourceFile))
                                put("compositePrimaryKey", buildJsonArray { })
                                put("security", buildJsonObject { })
                            },
                        )
                    }
                }

            // Resolve a relation endpoint to the package-led qname golem selected its
            // entities under, so prune_to_selection keeps the relation (it requires both
            // endpoints in the selection). Falls back to the bare qname if the endpoint
            // entity isn't in the bundle.
            fun packagedEndpoint(qname: QualifiedName): String {
                val bare = qname.toDottedQname()
                val pkg = entityPkgByQname[bare].orEmpty()
                return if (pkg.isEmpty()) bare else "$pkg.$bare"
            }
            val relationsArray =
                buildJsonArray {
                    for (rel in model.relationsList) {
                        val from = packagedEndpoint(rel.fromEntity)
                        val to = packagedEndpoint(rel.toEntity)
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive("$from->$to"))
                                put("name", JsonPrimitive(""))
                                put("fromEntity", JsonPrimitive(from))
                                put("toEntity", JsonPrimitive(to))
                                put("type", JsonPrimitive(""))
                                put("joinClause", JsonPrimitive(""))
                                put("description", JsonPrimitive(""))
                                put("sourceFile", JsonPrimitive(""))
                            },
                        )
                    }
                }
            val tablesArray =
                buildJsonArray {
                    for (table in model.tablesList) {
                        val colsArray =
                            buildJsonArray {
                                for (col in table.detail.columnsList) {
                                    add(
                                        buildJsonObject {
                                            put("id", JsonPrimitive(""))
                                            put("tableId", JsonPrimitive(""))
                                            put("name", JsonPrimitive(col.name))
                                            put("dataType", JsonPrimitive(col.dataType))
                                            put("isNullable", JsonPrimitive(col.nullable))
                                            put("isPrimaryKey", JsonPrimitive(false))
                                            put("isForeignKey", JsonPrimitive(false))
                                            put("fkTableName", JsonPrimitive(""))
                                            put("fkColumnName", JsonPrimitive(""))
                                            put("physical", JsonPrimitive(true))
                                        },
                                    )
                                }
                            }
                        add(
                            buildJsonObject {
                                val td = table.objectDescriptor
                                put(
                                    "id",
                                    JsonPrimitive(td.qualifiedName.toPackagedDottedQname(td.sourceFile, packagesList)),
                                )
                                put("name", JsonPrimitive(td.localName))
                                put("schema", JsonPrimitive(""))
                                put("columns", colsArray)
                                put("physical", JsonPrimitive(true))
                            },
                        )
                    }
                }
            val viewsArray =
                buildJsonArray {
                    for (view in model.viewsList) {
                        val colsArray =
                            buildJsonArray {
                                for (col in view.detail.columnsList) {
                                    add(
                                        buildJsonObject {
                                            put("id", JsonPrimitive(""))
                                            put("tableId", JsonPrimitive(""))
                                            put("name", JsonPrimitive(col.name))
                                            put("dataType", JsonPrimitive(col.dataType))
                                            put("isNullable", JsonPrimitive(col.nullable))
                                            put("isPrimaryKey", JsonPrimitive(false))
                                            put("isForeignKey", JsonPrimitive(false))
                                            put("fkTableName", JsonPrimitive(""))
                                            put("fkColumnName", JsonPrimitive(""))
                                            put("physical", JsonPrimitive(true))
                                        },
                                    )
                                }
                            }
                        add(
                            buildJsonObject {
                                val vd = view.objectDescriptor
                                put(
                                    "id",
                                    JsonPrimitive(vd.qualifiedName.toPackagedDottedQname(vd.sourceFile, packagesList)),
                                )
                                put("name", JsonPrimitive(vd.localName))
                                put("schema", JsonPrimitive(""))
                                put("columns", colsArray)
                                put("physical", JsonPrimitive(true))
                            },
                        )
                    }
                }
            val patternQueriesArray = encodeBundleQueriesJson(model.patternQueriesList, packagesList)
            val namedQueriesArray = encodeBundleQueriesJson(model.namedQueriesList, packagesList)
            val rolesArray =
                buildJsonArray {
                    for (role in model.rolesList) {
                        add(
                            buildJsonObject {
                                put(
                                    "label",
                                    buildJsonObject {
                                        for ((k, v) in role.label.byLanguageMap) {
                                            put(
                                                k,
                                                JsonPrimitive(v),
                                            )
                                        }
                                    },
                                )
                                put("description", JsonPrimitive(role.description))
                                put("search", buildJsonObject { })
                            },
                        )
                    }
                }
            val drillMapsArray =
                buildJsonArray {
                    for (dm in model.drillMapsList) {
                        add(
                            buildJsonObject {
                                put("name", JsonPrimitive(dm.name))
                                put("fromPattern", JsonPrimitive(dm.fromPattern.toDottedQname()))
                                put("toPattern", JsonPrimitive(dm.toPattern.toDottedQname()))
                                put(
                                    "argMapping",
                                    buildJsonObject { for ((k, v) in dm.argMappingMap) put(k, JsonPrimitive(v)) },
                                )
                                put("explicit", JsonPrimitive(dm.explicit))
                                put("overrideAuto", JsonPrimitive(dm.overrideAuto))
                                put(
                                    "display",
                                    buildJsonObject {
                                        for ((k, v) in dm.display.byLanguageMap) {
                                            put(
                                                k,
                                                JsonPrimitive(v),
                                            )
                                        }
                                    },
                                )
                                put("sourceFile", JsonPrimitive(dm.sourceFile))
                            },
                        )
                    }
                }
            val packageVersionsArray =
                buildJsonArray {
                    for (pv in model.packageVersionsList) {
                        add(
                            buildJsonObject {
                                put("packageName", JsonPrimitive(pv.packageName))
                                put("contentHash", JsonPrimitive(pv.contentHash))
                                put("loadedAt", JsonPrimitive(pv.loadedAt))
                            },
                        )
                    }
                }
            val structured =
                buildJsonObject {
                    put("entities", entitiesArray)
                    put("relations", relationsArray)
                    put("tables", tablesArray)
                    put("views", viewsArray)
                    put("patternQueries", patternQueriesArray)
                    put("namedQueries", namedQueriesArray)
                    put("roles", rolesArray)
                    put("drillMaps", drillMapsArray)
                    put("packageVersions", packageVersionsArray)
                }
            val text = McpJson.encodeToString(structured)
            logger.info(
                "get_model completed | success | packages={} | entities={} | tables={} | isError=false",
                packagesList.size,
                entitiesArray.size,
                tablesArray.size,
            )
            CallToolResult(content = listOf(TextContent(text = text)), structuredContent = structured)
        } catch (e: Exception) {
            logger.error("Error executing get_model tool", e)
            errorResult("get_model", "Failed to fetch model: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Phase 07 A4 / DF-ME01 — `cnc.role` queries against the gRPC metadata service.
    // These tools route through `metadataGrpcClient`; when it isn't wired, the tool
    // returns a structured error.
    // -------------------------------------------------------------------------

    val listRolesTool: Tool =
        Tool(
            name = "list_roles",
            description =
                "List all conceptual roles (`cnc.role.*`) declared in the metadata model. " +
                    "Returns role qnames, localised labels, and descriptions.",
            inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
            outputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("roles") {
                                put("type", "array")
                                putJsonObject("items") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("qname") { put("type", "string") }
                                        putJsonObject("description") { put("type", "string") }
                                        putJsonObject("label") { put("type", "object") }
                                    }
                                }
                            }
                        },
                ),
        )

    val getRolesForEntityTool: Tool =
        Tool(
            name = "get_roles_for_entity",
            description =
                "Return the conceptual roles attached to a given ER entity (e.g. `er.entity.store_sales` → " +
                    "`[cnc.role.fact]`). The `qname` arg accepts either a " +
                    "dotted local name (`store_sales`) or the full qname (`er.entity.store_sales`).",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("qname") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Entity qname (`er.entity.store_sales`) or local name (`store_sales`).",
                                )
                            }
                        },
                    required = listOf("qname"),
                ),
            outputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("entity") { put("type", "string") }
                            putJsonObject("roles") {
                                put("type", "array")
                                putJsonObject("items") { put("type", "string") }
                            }
                        },
                ),
        )

    suspend fun listRolesCallback(
        @Suppress("UNUSED_PARAMETER") request: CallToolRequest,
    ): CallToolResult {
        val grpc = metadataGrpcClient ?: return notWiredResult("list_roles")
        return try {
            val resp = grpc.listRoles()
            val rolesArray =
                buildJsonArray {
                    for (entry in resp.itemsList) {
                        val q = entry.objectDescriptor.qualifiedName
                        val full = "${q.schemaCode}.${q.namespace}.${q.name}"
                        add(
                            buildJsonObject {
                                put("qname", JsonPrimitive(full))
                                put("description", JsonPrimitive(entry.role.description))
                                put(
                                    "label",
                                    buildJsonObject {
                                        for ((lang, text) in entry.role.label.byLanguageMap) {
                                            put(lang, JsonPrimitive(text))
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
            val structured = buildJsonObject { put("roles", rolesArray) }
            val text = McpJson.encodeToString(structured)
            logger.info("list_roles completed | success | count={}", resp.itemsList.size)
            CallToolResult(content = listOf(TextContent(text = text)), structuredContent = structured)
        } catch (e: Exception) {
            logger.error("Error executing list_roles tool", e)
            errorResult("list_roles", "Failed to list roles: ${e.message}")
        }
    }

    suspend fun getRolesForEntityCallback(request: CallToolRequest): CallToolResult {
        val grpc = metadataGrpcClient ?: return notWiredResult("get_roles_for_entity")
        val raw =
            (request.params.arguments as? kotlinx.serialization.json.JsonObject)
                ?.get("qname")
                ?.let { (it as? JsonPrimitive)?.content }
        if (raw.isNullOrBlank()) {
            return CallToolResult(
                content = listOf(TextContent(text = "Missing qname parameter")),
                isError = true,
                structuredContent = errStructured("Missing qname parameter", errorCode = "MISSING_ARG"),
            )
        }
        return try {
            val qname = parseEntityQname(raw)
            val resp = grpc.getRolesForEntity(qname)
            val rolesArray =
                buildJsonArray {
                    for (r in resp.rolesList) {
                        add(JsonPrimitive("${r.schemaCode}.${r.namespace}.${r.name}"))
                    }
                }
            val structured =
                buildJsonObject {
                    put("entity", JsonPrimitive("${qname.schemaCode}.${qname.namespace}.${qname.name}"))
                    put("roles", rolesArray)
                }
            val text = McpJson.encodeToString(structured)
            logger.info(
                "get_roles_for_entity completed | success | entity={} | count={}",
                raw,
                resp.rolesList.size,
            )
            CallToolResult(content = listOf(TextContent(text = text)), structuredContent = structured)
        } catch (e: Exception) {
            logger.error("Error executing get_roles_for_entity for $raw", e)
            errorResult("get_roles_for_entity", "Failed to fetch roles: ${e.message}")
        }
    }

    /** Accept full `schema.namespace.name` (`er.entity.sales`) or local `name` (defaults to `er.entity.*`). */
    private fun parseEntityQname(raw: String): org.tatrman.plan.v1.QualifiedName {
        val parts = raw.split(".")
        val (schema, namespace, name) =
            when (parts.size) {
                1 -> Triple("er", "entity", parts[0])
                2 -> Triple("er", parts[0], parts[1])
                else -> Triple(parts[0], parts[1], parts.drop(2).joinToString("."))
            }
        val schemaCode = parseSchemaCode(schema) ?: SchemaCode.SCHEMA_CODE_UNSPECIFIED
        return org.tatrman.plan.v1.QualifiedName
            .newBuilder()
            .setSchemaCode(schemaCode)
            .setNamespace(namespace)
            .setName(name)
            .build()
    }

    /** Optional `package` filter argument shared by the list tools. Empty = no filter (all packages). */
    private fun packageArg(request: CallToolRequest): String =
        (request.params.arguments as? kotlinx.serialization.json.JsonObject)
            ?.get("package")
            ?.let { (it as? JsonPrimitive)?.content }
            ?: ""

    /**
     * Parse a dotted id (as emitted by the list tools via [toDottedQname]) back into a
     * [QualifiedName] for GetObject / GetQuery. Accepts an optional leading package segment,
     * a schema token (`db`/`er`/`cnc`/`ws`/`obj`), a namespace and the object name. A bare
     * local name falls back to [defaultSchema] / [defaultNamespace].
     *
     * Disambiguation: schema tokens are a fixed vocabulary, so a leading segment that is NOT a
     * schema token but is followed by one is treated as the package.
     */
    private fun parseQname(
        raw: String,
        defaultSchema: String,
        defaultNamespace: String,
    ): QualifiedName {
        var parts = raw.split(".")
        var pkg = ""
        if (parts.size >= 2 && parseSchemaCode(parts[0]) == null && parseSchemaCode(parts[1]) != null) {
            pkg = parts[0]
            parts = parts.drop(1)
        }
        val (schema, namespace, name) =
            when {
                parts.size == 1 -> Triple(defaultSchema, defaultNamespace, parts[0])
                parseSchemaCode(parts[0]) != null && parts.size >= 3 ->
                    Triple(parts[0], parts[1], parts.drop(2).joinToString("."))
                else -> Triple(defaultSchema, parts[0], parts.drop(1).joinToString("."))
            }
        val builder =
            QualifiedName
                .newBuilder()
                .setSchemaCode(parseSchemaCode(schema) ?: SchemaCode.SCHEMA_CODE_UNSPECIFIED)
                .setNamespace(namespace)
                .setName(name)
        if (pkg.isNotEmpty()) builder.setPackage(pkg)
        return builder.build()
    }

    /** Application-level "object missing in model" — metadata returns OK with a populated `messages`. */
    private fun notFoundResult(
        toolName: String,
        id: String,
        messages: List<org.tatrman.common.v1.ResponseMessage>,
    ): CallToolResult {
        val detail = messages.firstOrNull()?.humanMessage ?: "not found"
        val msg = "$toolName: no object at '$id' ($detail)"
        logger.info("$toolName completed | not_found | id={}", id)
        return CallToolResult(
            content = listOf(TextContent(text = msg)),
            isError = true,
            structuredContent = errStructured(msg, errorCode = "NOT_FOUND"),
        )
    }

    private fun notWiredResult(toolName: String): CallToolResult {
        val msg =
            "$toolName requires the gRPC metadata client; configure metadata.host/port to enable cnc.role queries."
        return CallToolResult(
            content = listOf(TextContent(text = msg)),
            isError = true,
            structuredContent = errStructured(msg, errorCode = "GRPC_NOT_CONFIGURED"),
        )
    }

    private fun errorResult(
        toolName: String,
        message: String,
    ): CallToolResult {
        logger.info("$toolName completed | exception | error={}", message.take(100))
        return CallToolResult(
            content = listOf(TextContent(text = message)),
            isError = true,
            structuredContent = errStructured(message),
        )
    }
}

// Render a QualifiedName in dotted form: optional pkg, then
// schema.namespace.name. That is what new-Golem's
// PackageContext.from_model_bundle() uses to key entities and patterns;
// proto's QualifiedName.toString() emits multi-line text format, which
// would force the Python side to re-parse it.
private fun org.tatrman.plan.v1.QualifiedName.toDottedQname(): String {
    val schemaToken =
        when (schemaCode) {
            org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED -> namespace
            else -> "${schemaCode.name.lowercase()}.$namespace"
        }
    val pkgSegment = if (`package`.isNotEmpty()) "${`package`}." else ""
    // schemaToken already contains the namespace once for UNSPECIFIED; otherwise it's
    // "<schema>.<namespace>". The name is always last.
    return if (schemaCode == org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED) {
        "$pkgSegment$schemaToken.$name"
    } else {
        "$pkgSegment$schemaToken.$name"
    }
}

// Derive the package name from a metadata sourceFile path. Packages are a path
// concept (`…/<package>/file.ttr`) — the qname does not carry them. Prefer
// a requested-package name that appears as a path segment (mirrors the metadata
// service's ListObjects `/<pkg>/` filter); fall back to the file's parent directory.
private fun packageFromSource(
    sourceFile: String,
    knownPackages: Collection<String>,
): String {
    if (sourceFile.isEmpty()) return ""
    val segments = sourceFile.replace('\\', '/').split('/').filter { it.isNotEmpty() }
    knownPackages.firstOrNull { it.isNotEmpty() && it in segments }?.let { return it }
    return if (segments.size >= 2) segments[segments.size - 2] else ""
}

// Package-led dotted qname golem's PackageContext keys + prunes on:
// `<package>.<schema>.<namespace>.<name>`. Falls back to the bare dotted qname when no
// package can be derived (or one is already present on the proto qname).
private fun org.tatrman.plan.v1.QualifiedName.toPackagedDottedQname(
    sourceFile: String,
    knownPackages: Collection<String>,
): String {
    val bare = toDottedQname()
    if (`package`.isNotEmpty()) return bare
    val pkg = packageFromSource(sourceFile, knownPackages)
    return if (pkg.isEmpty()) bare else "$pkg.$bare"
}

/**
 * new-golem Stage 04 — encode `ModelBundleQuery` entries as the JSON shape
 * Golem's PackageContext.from_model_bundle() expects. Keeps the encoder used by
 * both `patternQueries` and `namedQueries` in one place so they don't drift.
 */
private fun encodeBundleQueriesJson(
    queries: List<org.tatrman.meta.v1.ModelBundleQuery>,
    knownPackages: Collection<String>,
): kotlinx.serialization.json.JsonArray =
    buildJsonArray {
        for (query in queries) {
            val obj = query.objectDescriptor
            val desc = query.queryDescriptor
            add(
                buildJsonObject {
                    val dotted = obj.qualifiedName.toPackagedDottedQname(obj.sourceFile, knownPackages)
                    put("id", JsonPrimitive(dotted))
                    put("qname", JsonPrimitive(dotted))
                    put("name", JsonPrimitive(obj.localName))
                    put("description", JsonPrimitive(obj.description))
                    put("sourceFile", JsonPrimitive(obj.sourceFile))
                    put("sourceLanguage", JsonPrimitive(query.sourceLanguage.name))
                    put("sourceText", JsonPrimitive(query.sourceText))
                    put("sqlTemplate", JsonPrimitive(query.sourceText))
                    put(
                        "parameters",
                        buildJsonArray {
                            for (p in query.parametersList) {
                                add(
                                    buildJsonObject {
                                        put("name", JsonPrimitive(p.name))
                                        put("type", JsonPrimitive(p.type))
                                        put("label", JsonPrimitive(p.label))
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "search",
                        buildJsonObject {
                            put(
                                "patterns",
                                buildJsonArray { for (p in desc.search.patternsList) add(JsonPrimitive(p)) },
                            )
                            put(
                                "examples",
                                buildJsonArray { for (e in desc.search.examplesList) add(JsonPrimitive(e)) },
                            )
                            put(
                                "aliases",
                                buildJsonArray { for (a in desc.search.aliasesList) add(JsonPrimitive(a)) },
                            )
                            put("searchable", JsonPrimitive(desc.search.searchable))
                            put("fuzzy", JsonPrimitive(desc.search.fuzzy))
                        },
                    )
                    // Stage 04 leaves the legacy placeholders in place so existing
                    // get_pattern_queries consumers (legacy Golem nodes) keep their
                    // shape; the encoder above adds the new fields alongside.
                    put("pattern", JsonPrimitive(""))
                    put("patternAlt", JsonPrimitive(""))
                    put("entities", buildJsonArray { })
                    put("tested", JsonPrimitive(false))
                    put("testValues", buildJsonObject { })
                    put("paramLabels", buildJsonObject { })
                    put("example", buildJsonObject { })
                    put("security", buildJsonObject { })
                },
            )
        }
    }

/**
 * Encode a list of `QueryDescriptor` (from `ListQueries`) as the thin JSON shape the
 * `get_pattern_queries` / `get_sql_queries` tools return. Mirrors the keys golem's
 * metadata routes read (`id`, `name`, `description`) plus the legacy placeholders kept
 * for backward compatibility.
 */
private fun encodeQueryDescriptorsJson(
    queries: List<org.tatrman.meta.v1.QueryDescriptor>,
): kotlinx.serialization.json.JsonArray =
    buildJsonArray {
        for (query in queries) {
            val obj = query.objectDescriptor
            val dotted = obj.qualifiedName.toDottedQname()
            add(
                buildJsonObject {
                    put("id", JsonPrimitive(dotted))
                    put("qname", JsonPrimitive(dotted))
                    put("name", JsonPrimitive(obj.localName))
                    put("description", JsonPrimitive(obj.description))
                    put("pattern", JsonPrimitive(""))
                    put("patternAlt", JsonPrimitive(""))
                    put("entities", buildJsonArray { })
                    put("tested", JsonPrimitive(false))
                    put("testValues", buildJsonObject { })
                    put("paramLabels", buildJsonObject { })
                    put("parameters", buildJsonArray { })
                    put("sqlTemplate", JsonPrimitive(""))
                    put("example", buildJsonObject { })
                    put("sourceFile", JsonPrimitive(obj.sourceFile))
                    put("security", buildJsonObject { })
                },
            )
        }
    }

/** Encode a `GetQueryResponse` (from `GetQuery`) as the detailed query JSON shape. */
private fun encodeQueryDetailJson(resp: org.tatrman.meta.v1.GetQueryResponse): kotlinx.serialization.json.JsonObject {
    val obj = resp.objectDescriptor
    val dotted = obj.qualifiedName.toDottedQname()
    return buildJsonObject {
        put("id", JsonPrimitive(dotted))
        put("qname", JsonPrimitive(dotted))
        put("name", JsonPrimitive(obj.localName))
        put("description", JsonPrimitive(obj.description))
        put("sourceLanguage", JsonPrimitive(resp.sourceLanguage.name))
        put("sourceText", JsonPrimitive(resp.sourceText))
        put("sqlTemplate", JsonPrimitive(resp.sourceText))
        put(
            "parameters",
            buildJsonArray {
                for (p in resp.parametersList) {
                    add(
                        buildJsonObject {
                            put("name", JsonPrimitive(p.name))
                            put("type", JsonPrimitive(p.type))
                            put("label", JsonPrimitive(p.label))
                            put("default", JsonPrimitive(""))
                        },
                    )
                }
            },
        )
        put(
            "search",
            buildJsonObject {
                put("patterns", buildJsonArray { for (p in resp.search.patternsList) add(JsonPrimitive(p)) })
                put("examples", buildJsonArray { for (e in resp.search.examplesList) add(JsonPrimitive(e)) })
                put("aliases", buildJsonArray { for (a in resp.search.aliasesList) add(JsonPrimitive(a)) })
                put("searchable", JsonPrimitive(resp.search.searchable))
                put("fuzzy", JsonPrimitive(resp.search.fuzzy))
            },
        )
        put("pattern", JsonPrimitive(""))
        put("patternAlt", JsonPrimitive(""))
        put("entities", buildJsonArray { })
        put("tested", JsonPrimitive(false))
        put("testValues", buildJsonObject { })
        put("paramLabels", buildJsonObject { })
        put("example", buildJsonObject { })
        put("sourceFile", JsonPrimitive(obj.sourceFile))
        put("security", buildJsonObject { })
    }
}
