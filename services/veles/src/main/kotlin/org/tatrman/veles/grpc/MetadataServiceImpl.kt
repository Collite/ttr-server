// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import org.tatrman.meta.v1.AttributeDetail
import org.tatrman.meta.v1.AttributeSemantics
import org.tatrman.meta.v1.AttributeJoinPair
import org.tatrman.meta.v1.Binding as BindingProto
import org.tatrman.meta.v1.BindingType
import org.tatrman.meta.v1.Cardinality
import org.tatrman.meta.v1.DbColumnDetail
import org.tatrman.meta.v1.DbColumnSummary
import org.tatrman.meta.v1.DbForeignKeyDetail
import org.tatrman.meta.v1.DbTableDetail
import org.tatrman.meta.v1.DbViewDetail
import org.tatrman.meta.v1.DrillMapDetail
import org.tatrman.meta.v1.ModelBundleAttribute
import org.tatrman.meta.v1.ModelBundleEntity
import org.tatrman.meta.v1.ModelBundleQuery
import org.tatrman.meta.v1.EntityDetail
import org.tatrman.meta.v1.EntitySemantics
import org.tatrman.meta.v1.Er2CncRoleMappingDetail
import org.tatrman.meta.v1.EdgeResult
import org.tatrman.meta.v1.RefreshRequest
import org.tatrman.meta.v1.RefreshResponse
import org.tatrman.meta.v1.ResolveAreaRequest
import org.tatrman.meta.v1.ResolveAreaResponse
import org.tatrman.meta.v1.SourceRefreshResult
import org.tatrman.meta.v1.Er2DbAttributeMappingDetail
import org.tatrman.meta.v1.Er2DbEntityMappingDetail
import org.tatrman.meta.v1.Er2DbRelationMappingDetail
import org.tatrman.meta.v1.TraverseEdgesRequest
import org.tatrman.meta.v1.TraverseEdgesResponse
import org.tatrman.meta.v1.GetModelRequest
import org.tatrman.meta.v1.GetModelResponse
import org.tatrman.meta.v1.GetObjectRequest
import org.tatrman.meta.v1.GetObjectResponse
import org.tatrman.meta.v1.GetRolesForEntityRequest
import org.tatrman.meta.v1.GetRolesForEntityResponse
import org.tatrman.meta.v1.GetQueryRequest
import org.tatrman.meta.v1.GetQueryResponse
import org.tatrman.meta.v1.GetSnapshotRequest
import org.tatrman.meta.v1.GetSnapshotResponse
import org.tatrman.meta.v1.GetStatusRequest
import org.tatrman.meta.v1.GetStatusResponse
import org.tatrman.meta.v1.Language as ProtoLanguage
import org.tatrman.meta.v1.ListObjectsRequest
import org.tatrman.meta.v1.ListObjectsResponse
import org.tatrman.meta.v1.ListQueriesRequest
import org.tatrman.meta.v1.ListQueriesResponse
import org.tatrman.meta.v1.ListRolesRequest
import org.tatrman.meta.v1.ListRolesResponse
import org.tatrman.meta.v1.ListMemberVocabulariesRequest
import org.tatrman.meta.v1.ListMemberVocabulariesResponse
import org.tatrman.meta.v1.MemberVocabulary
import org.tatrman.translate.snapshot.SnapshotModelHandle
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.meta.v1.ModelBundle
import org.tatrman.meta.v1.ModelBundleTable
import org.tatrman.meta.v1.ModelBundleView
import org.tatrman.meta.v1.PackageVersion
import org.tatrman.meta.v1.RelationDetail
import org.tatrman.meta.v1.RoleEntry
import org.tatrman.meta.v1.LocalizedString as ProtoLocalizedString
import org.tatrman.meta.v1.LocalizedStringList as ProtoLocalizedStringList
import org.tatrman.meta.v1.VelesServiceGrpcKt
import org.tatrman.meta.v1.SearchHints as ProtoSearchHints
import org.tatrman.meta.v1.SearchRequest
import org.tatrman.meta.v1.SearchResponse
import org.tatrman.meta.v1.SearchResult
import org.tatrman.meta.v1.StringList as ProtoStringList
import org.tatrman.meta.v1.ModelDescriptor as ProtoModelDescriptor
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.ObjectEntry
import org.tatrman.meta.v1.OverallStatus
import org.tatrman.meta.v1.PageInfo
import org.tatrman.meta.v1.ParseStatus as ProtoParseStatus
import org.tatrman.meta.v1.ParseStatusFilter
import org.tatrman.meta.v1.QueryDescriptor
import org.tatrman.meta.v1.QueryDetail
import org.tatrman.meta.v1.QueryParameterDef as ProtoQueryParameterDef
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.meta.v1.RoleDetail
import org.tatrman.plan.v1.SchemaCode as ProtoSchemaCode
import org.tatrman.common.v1.Severity
import org.tatrman.meta.v1.ValidateModelRequest
import org.tatrman.meta.v1.ValidateModelResponse
import org.tatrman.ttr.metadata.query.MetadataQuery
import org.tatrman.ttr.metadata.refresh.MetadataRefresher
import org.tatrman.ttr.metadata.model.AttributeMappingTarget
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.Binding as DomainBinding
import org.tatrman.ttr.metadata.model.CncSchema
import org.tatrman.ttr.metadata.model.DbColumn
import org.tatrman.ttr.metadata.model.DbForeignKey
import org.tatrman.ttr.metadata.model.DbSchema
import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttr.metadata.model.DbView
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.Er2CncRoleMapping
import org.tatrman.ttr.metadata.model.Er2DbAttributeMapping
import org.tatrman.ttr.metadata.model.Er2DbEntityMapping
import org.tatrman.ttr.metadata.model.Er2DbRelationMapping
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.LocalizedText
import org.tatrman.ttr.metadata.model.LocalizedTextList
import org.tatrman.ttr.metadata.model.MappingTarget
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelObject
import org.tatrman.ttr.metadata.model.ParseStatus as DomainParseStatus
import org.tatrman.ttr.metadata.model.Query
import org.tatrman.ttr.metadata.model.Relation
import org.tatrman.ttr.metadata.model.Role
import org.tatrman.ttr.metadata.model.SearchHints
import org.tatrman.ttr.metadata.model.MatchMethods
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import org.tatrman.veles.parse.QueryParseState
import org.tatrman.ttr.metadata.search.SearchQuery
import io.opentelemetry.api.trace.Tracer
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.semantics.semanticsblock.ResolvedAttributeSemantics
import org.tatrman.ttr.semantics.semanticsblock.ResolvedEntitySemantics

/**
 * Metadata service gRPC surface.
 *
 * Implements: GetModel, ListObjects, GetObject / GetSnapshot (per-kind detail for db
 * tables/views/columns/FKs, er2db mappings, queries, entities/attributes, roles, er2cnc
 * mappings — DbProcedure / Relation stay descriptor-only), GetStatus, Search, ListQueries,
 * GetQuery, ListRoles, GetRolesForEntity, ValidateModel, TraverseEdges, Refresh — all `List*`
 * RPCs use AIP-158 sort-key page tokens via [PageTokenCodec]. Still pending: DbProcedureDetail /
 * RelationDetail (v1 gap closure Phase 07).
 *
 * When a [QueryParseState] is supplied, `parse_status` / `parse_error_*` for queries are read
 * live from the background [org.tatrman.veles.parse.QueryParseWorker]; otherwise they reflect the
 * model's stored (initial PENDING) state.
 */
class MetadataServiceImpl(
    private val registry: MetadataRegistry,
    private val searchRegistry: org.tatrman.ttr.metadata.search.SearchAlgorithmRegistry =
        org.tatrman.ttr.metadata.search
            .SearchAlgorithmRegistry(emptyMap()),
    private val searchIndexHolder: org.tatrman.ttr.metadata.search.SearchIndexHolder? = null,
    private val tracer: Tracer? = null,
    private val parseState: QueryParseState? = null,
    private val refresher: MetadataRefresher? = null,
) : VelesServiceGrpcKt.VelesServiceCoroutineImplBase() {
    private val logger = org.slf4j.LoggerFactory.getLogger(MetadataServiceImpl::class.java)

    /**
     * Live parse status for a query of the snapshot [snap] — the background worker's view of THAT
     * model if available, else the model's stored value.
     */
    private fun liveParseStatus(
        q: Query,
        snap: org.tatrman.ttr.metadata.registry.RegistrySnapshot,
    ): DomainParseStatus = parseState?.get(snap.model.version.value, q.qname) ?: q.parseStatus

    /**
     * GH #53 — render a qname with its canonical lowercase schema token (`er.entity.x`), not the
     * uppercase enum name (`ER.entity.x`). Falls back to the namespace when the schema code is
     * UNSPECIFIED (query/map qnames) so the rendered string still round-trips and reads sanely.
     */
    private fun org.tatrman.plan.v1.QualifiedName.dotted(): String {
        val token =
            org.tatrman.plan.v1
                .schemaCodeToToken(schemaCode)
        return "${token.ifEmpty { namespace }}.$namespace.$name"
    }

    /**
     * Columns that are indexed *by virtue of backing an indexed ER attribute* — what
     * `ListObjects(indexed_only)` lists beside the carriers that are indexed themselves (MV renamed
     * this from `attributeBackedFuzzyColumns`; the bit it read always meant "indexed").
     *
     * An indexed carrier may be a DB column directly (handled by [searchHintsOrNull]) OR an ER
     * attribute; for an attribute, the listed column is its `er2db` mapping target. Attributes
     * mapped to an Expression (no physical column) or with no mapping at all are skipped with a
     * warning. This is a LISTING — the member vocabularies themselves come from
     * [listMemberVocabularies], which never walks columns.
     *
     * Memoised by model version so the traversal + warnings run once per snapshot
     * swap, not once per `listObjects` call.
     */
    @Volatile
    private var indexedColumnsCache: Pair<String, Set<org.tatrman.ttr.metadata.model.QualifiedName>>? = null

    private fun indexedColumns(model: Model): Set<org.tatrman.ttr.metadata.model.QualifiedName> {
        val version = model.version.value
        indexedColumnsCache?.let { (v, set) -> if (v == version) return set }

        val indexedAttrs =
            model
                .objectByQname()
                .values
                .asSequence()
                .filterIsInstance<Attribute>()
                .filter { it.search.indexed }
                .map { it.qname }
                .toSet()
        val mappingByAttr =
            model.mappings
                .asSequence()
                .filterIsInstance<Er2DbAttributeMapping>()
                .associateBy { it.attribute }

        val columns = mutableSetOf<org.tatrman.ttr.metadata.model.QualifiedName>()
        for (attr in indexedAttrs) {
            when (val target = mappingByAttr[attr]?.target) {
                is AttributeMappingTarget.Column -> columns.add(target.qname)
                is AttributeMappingTarget.Expression ->
                    logger.warn(
                        "Indexed attribute {} maps to an expression (no physical column); not listed by indexed_only",
                        attr.dotted(),
                    )
                null ->
                    logger.warn(
                        "Indexed attribute {} has no er2db column mapping; not listed by indexed_only",
                        attr.dotted(),
                    )
            }
        }
        val result = columns.toSet()
        indexedColumnsCache = version to result
        return result
    }

    /** Returns the SearchHints on this object, or null if the kind doesn't carry one. */
    private fun ModelObject.searchHintsOrNull(): SearchHints? =
        when (this) {
            is Entity -> search
            is Attribute -> search
            is Role -> search
            is DbColumn -> search
            is Query -> search
            else -> null
        }

    override suspend fun getModel(request: GetModelRequest): GetModelResponse {
        val snap =
            registry.read()
                ?: return GetModelResponse
                    .newBuilder()
                    .addMessages(notReadyMessage())
                    .build()

        if (request.packagesList.isEmpty()) {
            return GetModelResponse
                .newBuilder()
                .addMessages(
                    ResponseMessage
                        .newBuilder()
                        .setSeverity(Severity.ERROR)
                        .setCode("EMPTY_PACKAGES")
                        .setHumanMessage("packages must be non-empty")
                        .build(),
                ).build()
        }

        // M2: hoist per-snapshot lookups out of the per-package loop.
        val erSchema = snap.model.schemas["er"] as? ErSchema
        val dbSchema = snap.model.schemas["db"] as? DbSchema
        val cncSchema = snap.model.schemas["cnc"] as? CncSchema
        val allObjects = snap.model.objectByQname().values

        val options = BundleOptions(includeSearchHints = request.includeSearchHints, locale = request.locale)
        val bundleBuilder = ModelBundle.newBuilder()

        for (packageName in request.packagesList) {
            val packageRoot = "/$packageName/"

            val packageEntities =
                erSchema?.entities?.values?.filter { it.sourceFile.contains(packageRoot) } ?: emptyList()
            bundleBuilder.addAllEntities(packageEntities.map { it.toModelBundleEntity(options) })

            val packageRelations =
                erSchema?.relations?.values?.filter { it.sourceFile.contains(packageRoot) } ?: emptyList()
            bundleBuilder.addAllRelations(packageRelations.map { it.toRelationDetail() })

            // Tables: ALL tables declared in the package's db.ttr, regardless of entity binding.
            // Per PF-1: the free-SQL planner needs the full physical schema.
            val packageTables =
                dbSchema?.tables?.values?.filter { it.sourceFile.contains(packageRoot) } ?: emptyList()
            bundleBuilder.addAllTables(packageTables.map { it.toModelBundleTable(options) })

            val packageViews =
                dbSchema?.views?.values?.filter { it.sourceFile.contains(packageRoot) } ?: emptyList()
            bundleBuilder.addAllViews(packageViews.map { it.toModelBundleView(options) })

            // A3: pattern iff search.patterns is non-empty. Discrimination happens on the
            // domain object, so it is unaffected by include_search_hints stripping later.
            val packageQueries =
                snap.model.queries.values
                    .filter { it.sourceFile.contains(packageRoot) }
            val (patternQueries, namedQueries) =
                packageQueries.partition { it.search.patterns.isNotEmpty() }
            bundleBuilder.addAllPatternQueries(
                patternQueries.map { it.toModelBundleQuery(liveParseStatus(it, snap), options) },
            )
            bundleBuilder.addAllNamedQueries(
                namedQueries.map { it.toModelBundleQuery(liveParseStatus(it, snap), options) },
            )

            if (request.includeRoles) {
                val packageRoles =
                    cncSchema?.roles?.values?.filter { it.sourceFile.contains(packageRoot) } ?: emptyList()
                bundleBuilder.addAllRoles(
                    // NLS-P10: a role's description rides RoleDetail, not an ObjectDescriptor,
                    // so it needs the chain applied explicitly here.
                    packageRoles.map { it.toRoleDetail(options.locale).applyBundleOptions(options) },
                )
            }

            // v2.2 — drill maps (Stage 03). Filter to maps contributed by this package's
            // source files; honour the request flag (defaults to "include" once non-empty
            // populates the field).
            if (request.includeDrillMap) {
                val packageDrillMaps =
                    snap.model.drillMaps.values
                        .filter { it.sourceFile.contains(packageRoot) }
                bundleBuilder.addAllDrillMaps(packageDrillMaps.map { it.toDrillMapDetail() })
            }

            // PackageVersion: per OQ-01.B, sha256 of concatenated source-file BYTES in
            // deterministic file-name order. The path prefix is included in the digest so
            // identical files at different paths still hash differently.
            val packageFiles =
                allObjects
                    .asSequence()
                    .filter { it.sourceFile.contains(packageRoot) }
                    .map { it.sourceFile }
                    .distinct()
                    .sorted()
                    .toList()
            val digest = MessageDigest.getInstance("SHA-256")
            for (filePath in packageFiles) {
                digest.update((filePath + "\n").toByteArray())
                // sourceFile from FileBasedSource is an absolute fs path. If a future source
                // returns a non-fs path, Files.readAllBytes will throw and the RPC will fail
                // for that package — that's the correct failure mode (no silent wrong hash).
                digest.update(Files.readAllBytes(Path.of(filePath)))
                digest.update("\n".toByteArray())
            }
            val contentHash = digest.digest().joinToString("") { "%02x".format(it) }

            val loadedAt = Instant.now().toString()
            bundleBuilder.addPackageVersions(
                PackageVersion
                    .newBuilder()
                    .setPackageName(packageName)
                    .setContentHash(contentHash)
                    .setLoadedAt(loadedAt)
                    .build(),
            )
        }

        return GetModelResponse
            .newBuilder()
            .setModel(bundleBuilder.build())
            .build()
    }

    override suspend fun listObjects(request: ListObjectsRequest): ListObjectsResponse {
        val snap =
            registry.read()
                ?: return ListObjectsResponse.newBuilder().addMessages(notReadyMessage()).build()
        // MV: a column is listed if it is indexed itself OR it backs an indexed ER attribute.
        // `fuzzy_only` is the deprecated spelling of `indexed_only` — honoured, warned once.
        if (request.fuzzyOnly && fuzzyOnlyWarned.compareAndSet(false, true)) {
            logger.warn(
                "ListObjects: fuzzy_only is deprecated; use indexed_only (MV — it filters on SearchHints.indexed)",
            )
        }
        val indexedOnly = request.indexedOnly || request.fuzzyOnly
        val backingIndexed = if (indexedOnly) indexedColumns(snap.model) else emptySet()
        val all =
            snap.model
                .objectByQname()
                .values
                .asSequence()
                .filter {
                    request.schema == ProtoSchemaCode.SCHEMA_CODE_UNSPECIFIED ||
                        it.qname.schemaCode == request.schema.toDomain()
                }.filter { request.kind.isEmpty() || it.kind == request.kind }
                .filter { request.tagsList.isEmpty() || request.tagsList.any(it.tags::contains) }
                .filter {
                    request.sourceFilePrefix.isEmpty() ||
                        it.sourceFile.startsWith(request.sourceFilePrefix)
                }.filter { obj ->
                    !indexedOnly ||
                        obj.searchHintsOrNull()?.indexed == true ||
                        (obj is DbColumn && obj.qname in backingIndexed)
                }.filter { obj ->
                    request.`package`.isEmpty() || obj.sourceFile.contains("/${request.`package`}/")
                }.sortedBy { "${it.qname.schemaCode}.${it.qname.namespace}.${it.qname.name}" }
                .toList()

        val pageSize = (request.page.pageSize.takeIf { it > 0 } ?: 100).coerceAtMost(1000)
        val (slice, nextToken) =
            PageTokenCodec.paginate(all, request.page.pageToken, pageSize) {
                "${it.qname.schemaCode}.${it.qname.namespace}.${it.qname.name}"
            }

        return ListObjectsResponse
            .newBuilder()
            .addAllItems(slice.map { it.toObjectDescriptor() })
            .setPageInfo(
                PageInfo
                    .newBuilder()
                    .setNextPageToken(nextToken)
                    .setTotalCount(all.size)
                    .build(),
            ).build()
    }

    override suspend fun getObject(request: GetObjectRequest): GetObjectResponse {
        val snap =
            registry.read()
                ?: return GetObjectResponse.newBuilder().addMessages(notReadyMessage()).build()
        // MS: hoisted — the mention-facet owner lookup below indexes the same map, and
        // `objectByQname()` rebuilds it on every call.
        val byQname = snap.model.objectByQname()
        val obj =
            byQname[request.qualifiedName.toDomain()]
                ?: return GetObjectResponse
                    .newBuilder()
                    .addMessages(
                        ResponseMessage
                            .newBuilder()
                            .setSeverity(Severity.WARNING)
                            .setCode("object_not_found")
                            .setHumanMessage(
                                "No object at ${request.qualifiedName.schemaCode}.${request.qualifiedName.namespace}.${request.qualifiedName.name}",
                            ).build(),
                    ).build()
        val builder =
            GetObjectResponse
                .newBuilder()
                .setObjectDescriptor(obj.toObjectDescriptor())
        when (obj) {
            is DbTable -> builder.table = obj.toDbTableDetail()
            is DbView -> builder.view = obj.toDbViewDetail()
            is DbColumn -> builder.column = obj.toDbColumnDetail(byQname.mentionOf(obj.table))
            is DbForeignKey -> builder.foreignKey = obj.toDbForeignKeyDetail()
            is Entity -> builder.entity = obj.toEntityDetail()
            is Attribute -> builder.attribute = obj.toAttributeDetail(byQname.mentionOf(obj.entity))
            is Er2DbEntityMapping -> builder.er2DbEntityMapping = obj.toEr2DbEntityMappingDetail()
            is Er2DbAttributeMapping -> builder.er2DbAttributeMapping = obj.toEr2DbAttributeMappingDetail()
            is Er2DbRelationMapping -> builder.er2DbRelationMapping = obj.toEr2DbRelationMappingDetail()
            is Role -> builder.role = obj.toRoleDetail()
            is Er2CncRoleMapping -> builder.er2CncRoleMapping = obj.toEr2CncRoleMappingDetail()
            // GetObjectResponse has no `query` field — query detail is served by GetQuery (and via
            // GetSnapshot's ObjectEntry). DbProcedure / Relation remain descriptor-only for now.
            else -> Unit
        }
        return builder.build()
    }

    override suspend fun getSnapshot(request: GetSnapshotRequest): GetSnapshotResponse {
        val snap =
            registry.read()
                ?: return GetSnapshotResponse.newBuilder().addMessages(notReadyMessage()).build()

        // GH #112 — the snapshot carries each query's canonical form from the LIVE parse state,
        // which fills in after the swap; the ETag has to move with it or a consumer that fetched
        // during the parse window never sees the plans. Read BEFORE the objects are walked, so a
        // parse landing mid-walk makes the snapshot newer than its ETag (one extra fetch), never
        // older. Without a live parse state the content is a function of the model alone and the
        // ETag stays the bare version.
        val etag = parseState?.let { "${snap.model.version.value}.q${it.generation()}" } ?: snap.model.version.value
        if (request.ifNoneMatch.isNotEmpty() && request.ifNoneMatch == etag) {
            return GetSnapshotResponse
                .newBuilder()
                .setNotModified(true)
                .setEtag(etag)
                .build()
        }
        return GetSnapshotResponse
            .newBuilder()
            .setNotModified(false)
            .setEtag(etag)
            .setSnapshot(buildModelSnapshot(snap))
            .build()
    }

    /**
     * The whole-model snapshot GetSnapshot serves — and the one [listMemberVocabularies] renders
     * through (`SnapshotModelHandle.from(...)`, the adapter the translate service builds from this
     * very response), so an index reads an entity exactly as a query over it does.
     */
    private fun buildModelSnapshot(
        snap: org.tatrman.ttr.metadata.registry.RegistrySnapshot,
    ): org.tatrman.meta.v1.ModelSnapshot {
        val descriptor = snap.toProtoDescriptor()
        val snapshotBuilder =
            org.tatrman.meta.v1.ModelSnapshot
                .newBuilder()
                .setModel(descriptor)
        val byQname = snap.model.objectByQname()
        byQname.values.forEach { obj ->
            val entryBuilder =
                ObjectEntry
                    .newBuilder()
                    .setObjectDescriptor(obj.toObjectDescriptor())
            // Same per-kind detail population as GetObject (a few kinds — DbProcedure / Relation — stay descriptor-only).
            when (obj) {
                is DbTable -> entryBuilder.table = obj.toDbTableDetail()
                is DbView -> entryBuilder.view = obj.toDbViewDetail()
                is DbColumn -> entryBuilder.column = obj.toDbColumnDetail(byQname.mentionOf(obj.table))
                is DbForeignKey -> entryBuilder.foreignKey = obj.toDbForeignKeyDetail()
                is Entity -> entryBuilder.entity = obj.toEntityDetail()
                is Attribute -> entryBuilder.attribute = obj.toAttributeDetail(byQname.mentionOf(obj.entity))
                is Er2DbEntityMapping -> entryBuilder.er2DbEntityMapping = obj.toEr2DbEntityMappingDetail()
                is Er2DbAttributeMapping -> entryBuilder.er2DbAttributeMapping = obj.toEr2DbAttributeMappingDetail()
                is Er2DbRelationMapping -> entryBuilder.er2DbRelationMapping = obj.toEr2DbRelationMappingDetail()
                is Query -> entryBuilder.query = obj.toQueryDetail(liveParseStatus(obj, snap))
                is Role -> entryBuilder.role = obj.toRoleDetail()
                is Er2CncRoleMapping -> entryBuilder.er2CncRoleMapping = obj.toEr2CncRoleMappingDetail()
                else -> Unit
            }
            snapshotBuilder.addObjects(entryBuilder.build())
        }
        return snapshotBuilder.build()
    }

    // ----- MV-T1 — ListMemberVocabularies (member-vocabulary contracts §3) -----

    /** Rendered vocabularies for one (model version, dialect) — rendering runs Calcite, so once per swap. */
    @Volatile
    private var memberVocabularyCache: Triple<String, SqlDialect, List<MemberVocabulary>>? = null

    override suspend fun listMemberVocabularies(
        request: ListMemberVocabulariesRequest,
    ): ListMemberVocabulariesResponse {
        val snap =
            registry.read()
                ?: return ListMemberVocabulariesResponse.newBuilder().addMessages(notReadyMessage()).build()
        val dialect =
            MemberVocabularyRenderer.dialectOf(request.dialect)
                ?: return ListMemberVocabulariesResponse
                    .newBuilder()
                    .addMessages(
                        ResponseMessage
                            .newBuilder()
                            .setSeverity(Severity.ERROR)
                            .setCode("unknown_dialect")
                            .setHumanMessage(
                                "Unknown dialect '${request.dialect}' — expected a SqlDialect value name " +
                                    "(${MemberVocabularyRenderer.DIALECTS.joinToString { it.name }}) or \"\"",
                            ).build(),
                    ).build()
        val all = memberVocabularies(snap, dialect)
        val pageSize = (request.page.pageSize.takeIf { it > 0 } ?: 100).coerceAtMost(1000)
        val (slice, nextToken) = PageTokenCodec.paginate(all, request.page.pageToken, pageSize) { it.category }
        return ListMemberVocabulariesResponse
            .newBuilder()
            .addAllItems(slice)
            .setPageInfo(
                PageInfo
                    .newBuilder()
                    .setNextPageToken(nextToken)
                    .setTotalCount(all.size)
                    .build(),
            ).build()
    }

    private fun memberVocabularies(
        snap: org.tatrman.ttr.metadata.registry.RegistrySnapshot,
        dialect: SqlDialect,
    ): List<MemberVocabulary> {
        val version = snap.model.version.value
        memberVocabularyCache?.let { (v, d, cached) -> if (v == version && d == dialect) return cached }
        val drafts = MemberVocabularies.enumerate(snap.model)
        val rendered =
            if (drafts.isEmpty()) {
                emptyList()
            } else {
                val renderer = MemberVocabularyRenderer(SnapshotModelHandle.from(buildModelSnapshot(snap)), version)
                drafts.map { renderer.render(it, dialect) }
            }
        for (v in rendered.filter { it.diagnosticsCount > 0 }) {
            logger.warn(
                "Member vocabulary {} has no read plan: {}",
                v.category,
                v.diagnosticsList.joinToString { it.humanMessage },
            )
        }
        logger.info(
            "Member vocabularies for model {} ({}): {} listed, {} with a read plan",
            version,
            dialect.name,
            rendered.size,
            rendered.count { it.readSql.isNotEmpty() },
        )
        memberVocabularyCache = Triple(version, dialect, rendered)
        return rendered
    }

    override suspend fun listQueries(request: ListQueriesRequest): ListQueriesResponse {
        val snap =
            registry.read()
                ?: return ListQueriesResponse.newBuilder().addMessages(notReadyMessage()).build()

        val all =
            snap.model.queries.values
                .asSequence()
                .filter { request.tagsList.isEmpty() || request.tagsList.any(it.tags::contains) }
                .filter {
                    request.languageFilter == ProtoLanguage.LANGUAGE_UNSPECIFIED ||
                        it.sourceLanguage.toProtoLanguage() == request.languageFilter
                }.filter { request.parseStatusFilter.matches(liveParseStatus(it, snap)) }
                .filter { request.`package`.isEmpty() || it.sourceFile.contains("/${request.`package`}/") }
                .sortedBy { "${it.qname.schemaCode}.${it.qname.namespace}.${it.qname.name}" }
                .toList()

        val pageSize = (request.page.pageSize.takeIf { it > 0 } ?: 100).coerceAtMost(1000)
        val (slice, nextToken) =
            PageTokenCodec.paginate(all, request.page.pageToken, pageSize) {
                "${it.qname.schemaCode}.${it.qname.namespace}.${it.qname.name}"
            }

        return ListQueriesResponse
            .newBuilder()
            .addAllItems(slice.map { it.toQueryDescriptor(liveParseStatus(it, snap)) })
            .setPageInfo(
                PageInfo
                    .newBuilder()
                    .setNextPageToken(nextToken)
                    .setTotalCount(all.size)
                    .build(),
            ).build()
    }

    override suspend fun getQuery(request: GetQueryRequest): GetQueryResponse {
        val snap =
            registry.read()
                ?: return GetQueryResponse.newBuilder().addMessages(notReadyMessage()).build()
        val q =
            snap.model.queries[request.qualifiedName.toDomain()]
                ?: return GetQueryResponse
                    .newBuilder()
                    .addMessages(
                        ResponseMessage
                            .newBuilder()
                            .setSeverity(Severity.WARNING)
                            .setCode("object_not_found")
                            .setHumanMessage(
                                "No query at ${request.qualifiedName.schemaCode}.${request.qualifiedName.namespace}.${request.qualifiedName.name}",
                            ).build(),
                    ).build()

        val live = liveParseStatus(q, snap)
        val builder =
            GetQueryResponse
                .newBuilder()
                .setObjectDescriptor(q.toObjectDescriptor())
                .setSourceLanguage(q.sourceLanguage.toProtoLanguage())
                .setSourceText(q.sourceText)
                .setParseStatus(live.toProtoParseStatus())
                .setSearch(q.search.toProto())
                .addAllParameters(
                    q.parameters.map {
                        ProtoQueryParameterDef
                            .newBuilder()
                            .setName(it.name)
                            .setType(it.type)
                            .setLabel(it.label)
                            .build()
                    },
                )
        // `uses` (referenced objects / queries) is not tracked on the model yet — populated when
        // query_ref resolution lands (v1 gap closure Phase 03 / DF-T03). Left empty for now.
        when (val ps = live) {
            is DomainParseStatus.ParseFailure -> {
                builder.parseErrorMessage = ps.message
                builder.parseErrorLocation = ps.location
            }
            is DomainParseStatus.ParseSuccess -> {
                if (request.includeCanonicalForm) {
                    try {
                        builder.canonicalForm =
                            org.tatrman.plan.v1.PlanNode
                                .parseFrom(ps.canonicalFormProtoBytes)
                    } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
                        builder.addMessages(
                            ResponseMessage
                                .newBuilder()
                                .setSeverity(Severity.WARNING)
                                .setCode("canonical_form_unreadable")
                                .setHumanMessage(
                                    "Stored canonical form for the query could not be parsed: ${e.message}",
                                ).build(),
                        )
                    }
                }
            }
            is DomainParseStatus.ParsePending -> Unit
        }
        return builder.build()
    }

    override suspend fun getStatus(request: GetStatusRequest): GetStatusResponse {
        val snap = registry.read()
        val supportedAlgos = searchRegistry.supportedNames.ifEmpty { listOf("substring", "keyword", "regex", "all") }
        val builder = GetStatusResponse.newBuilder()
        if (snap == null) {
            return builder
                .setModelLoaded(false)
                .setOverallStatus(OverallStatus.DEGRADED)
                .addAllSupportedSearchAlgorithms(supportedAlgos)
                .setDefaultSearchAlgorithm(DEFAULT_SEARCH_ALGORITHM)
                .build()
        }
        val totalQueries = snap.model.queries.size
        val counts =
            parseState?.counts(snap.model.version.value)
                ?: QueryParseState.Counts(parsed = 0, pending = totalQueries, failed = 0)
        builder
            .setModelLoaded(true)
            .setModelVersion(snap.model.version.value)
            .setModelLoadedAt(snap.swappedAt.toString())
            .setQueriesTotal(totalQueries)
            .setQueriesParsed(counts.parsed)
            .setQueriesFailed(counts.failed)
            .setQueriesPending(counts.pending)
            .setOverallStatus(OverallStatus.OK)
            .addAllSupportedSearchAlgorithms(supportedAlgos)
            .setDefaultSearchAlgorithm(DEFAULT_SEARCH_ALGORITHM)
        searchIndexHolder?.stats()?.let { stats ->
            builder
                .setSearchIndexObjects(stats.objectCount)
                .setSearchIndexCompileErrors(stats.compileErrors.size)
            stats.compileErrors.take(MAX_COMPILE_ERRORS_SURFACED).forEach { ce ->
                builder.addSearchCompileErrors(
                    org.tatrman.meta.v1.CompileError
                        .newBuilder()
                        .setField(ce.field)
                        .setMessage(ce.message)
                        .also {
                            val owner = snap.model.objectByQname()[ce.objectQname]
                            if (owner != null) it.`object` = owner.toObjectDescriptor()
                        }.build(),
                )
            }
        }
        return builder.build()
    }

    override suspend fun search(request: SearchRequest): SearchResponse {
        val snap = registry.read()
        val builder = SearchResponse.newBuilder()
        if (snap == null) {
            return builder.addMessages(notReadyMessage()).build()
        }
        val span =
            tracer
                ?.spanBuilder("metadata.search")
                ?.startSpan()
        val started = System.nanoTime()
        try {
            val requestedAlgo = request.algorithm.ifEmpty { DEFAULT_SEARCH_ALGORITHM }
            val language = request.language.ifEmpty { "cs" }

            val algo = searchRegistry.get(requestedAlgo)
            if (algo == null) {
                span?.setAttribute("search.algorithm_requested", requestedAlgo)
                span?.setAttribute("search.language", language)
                span?.setAttribute("search.query_length", request.query.length.toLong())
                span?.setAttribute("search.result_count", 0L)
                span?.setAttribute("search.threshold", request.resultThreshold.toDouble())
                span?.setAttribute("search.top_score", 0.0)
                span?.setAttribute("search.top_algorithm", "")
                span?.setAttribute("search.top_matched_field", "")
                span?.setAttribute(
                    "search.duration_ms",
                    (System.nanoTime() - started).toDouble() / 1_000_000.0,
                )
                return builder
                    .setAlgorithmUsed(requestedAlgo)
                    .addMessages(
                        ResponseMessage
                            .newBuilder()
                            .setSeverity(Severity.WARNING)
                            .setCode("algorithm_not_supported")
                            .setHumanMessage(
                                "Algorithm '$requestedAlgo' is not supported by this server. Supported: ${searchRegistry.supportedNames}",
                            ).build(),
                    ).build()
            }

            // MD2: delegate algorithm selection + per-language index + post-processing to
            // the library MetadataQuery (the M1.2 pull-down of this exact logic). The proto
            // SearchRequest is converted to the library's proto-free SearchQuery here.
            val query =
                SearchQuery(
                    query = request.query,
                    algorithm = requestedAlgo,
                    language = language,
                    // Map the proto page size onto the library limit (M1 de-proto: was
                    // `if (hasPage) page.pageSize else 0`). Dropping this made every search
                    // ignore the caller's page_size and fall back to the default-100 window.
                    limit = if (request.hasPage()) request.page.pageSize else 0,
                    resultThreshold = request.resultThreshold,
                    includeExtractedParameters = request.includeExtractedParameters,
                )
            val processed = MetadataQuery(snap, searchRegistry, searchIndexHolder).search(query)

            val byQname = snap.model.objectByQname()
            val protoResults =
                processed.mapNotNull { hit ->
                    val owner = byQname[hit.ownerQname] ?: return@mapNotNull null
                    SearchResult
                        .newBuilder()
                        .setObjectDescriptor(owner.toObjectDescriptor())
                        .setRelevanceScore(hit.score)
                        .setMatchedField(hit.matchedField)
                        .setSnippet(hit.snippet)
                        .setAlgorithm(hit.algorithm)
                        .setMatchedValue(hit.matchedValue)
                        .setPatternIndex(hit.patternIndex)
                        .putAllExtractedParameters(hit.extractedParameters)
                        .build()
                }
            val top = processed.firstOrNull()
            span?.setAttribute("search.algorithm_requested", requestedAlgo)
            span?.setAttribute("search.language", language)
            span?.setAttribute("search.query_length", request.query.length.toLong())
            span?.setAttribute("search.result_count", protoResults.size.toLong())
            span?.setAttribute("search.threshold", request.resultThreshold.toDouble())
            span?.setAttribute("search.top_score", (top?.score ?: 0f).toDouble())
            span?.setAttribute("search.top_algorithm", top?.algorithm ?: "")
            span?.setAttribute("search.top_matched_field", top?.matchedField ?: "")
            span?.setAttribute(
                "search.duration_ms",
                (System.nanoTime() - started).toDouble() / 1_000_000.0,
            )

            return builder
                .addAllItems(protoResults)
                .setAlgorithmUsed(requestedAlgo)
                .build()
        } finally {
            span?.end()
        }
    }

    private companion object {
        const val DEFAULT_SEARCH_ALGORITHM = "all"
        const val MAX_COMPILE_ERRORS_SURFACED = 50

        // Preserves the old TraverseEdgesHandler.MAX_DEPTH_CAP (now internal to the library).
        const val MAX_TRAVERSE_DEPTH = 10
    }

    override suspend fun listRoles(request: ListRolesRequest): ListRolesResponse {
        val snap =
            registry.read()
                ?: return ListRolesResponse.newBuilder().addMessages(notReadyMessage()).build()
        val cnc = snap.model.schemas["cnc"] as? CncSchema
        val all =
            (cnc?.roles?.values?.toList() ?: emptyList())
                .sortedBy { "${it.qname.schemaCode}.${it.qname.namespace}.${it.qname.name}" }
        val pageSize = (request.page.pageSize.takeIf { it > 0 } ?: 100).coerceAtMost(1000)
        val (slice, nextToken) =
            PageTokenCodec.paginate(all, request.page.pageToken, pageSize) {
                "${it.qname.schemaCode}.${it.qname.namespace}.${it.qname.name}"
            }
        return ListRolesResponse
            .newBuilder()
            .addAllItems(
                slice.map { role ->
                    RoleEntry
                        .newBuilder()
                        .setObjectDescriptor(role.toObjectDescriptor())
                        .setRole(role.toRoleDetail())
                        .build()
                },
            ).setPageInfo(
                PageInfo
                    .newBuilder()
                    .setNextPageToken(nextToken)
                    .setTotalCount(all.size)
                    .build(),
            ).build()
    }

    override suspend fun getRolesForEntity(request: GetRolesForEntityRequest): GetRolesForEntityResponse {
        val snap =
            registry.read()
                ?: return GetRolesForEntityResponse.newBuilder().addMessages(notReadyMessage()).build()
        val target = request.entity.toDomain()
        val mappings =
            snap.model.mappings
                .filterIsInstance<Er2CncRoleMapping>()
                .filter { it.entity == target }
        val builder = GetRolesForEntityResponse.newBuilder()
        if (mappings.isEmpty()) {
            // Distinguish "no roles" from "entity unknown" via a soft message.
            val entityKnown = snap.model.objectByQname()[target] != null
            if (!entityKnown) {
                builder.addMessages(
                    ResponseMessage
                        .newBuilder()
                        .setSeverity(Severity.WARNING)
                        .setCode("object_not_found")
                        .setHumanMessage(
                            "No entity at ${target.schemaCode}.${target.namespace}.${target.name}",
                        ).build(),
                )
            }
        }
        // Preserve insertion order; de-duplicate while keeping first occurrence.
        val seen = LinkedHashSet<org.tatrman.plan.v1.QualifiedName>()
        for (m in mappings) seen += m.role.toProto()
        builder.addAllRoles(seen)
        return builder.build()
    }

    override suspend fun validateModel(request: ValidateModelRequest): ValidateModelResponse {
        val snap =
            registry.read()
                ?: return ValidateModelResponse.newBuilder().addMessages(notReadyMessage()).build()
        val warnings = snap.warnings
        val builder = ValidateModelResponse.newBuilder()
        warnings.forEach {
            builder.addIssues(
                ResponseMessage
                    .newBuilder()
                    .setSeverity(Severity.WARNING)
                    .setCode("source_load_warning")
                    .setHumanMessage(it.message)
                    .setSourceFile(it.file)
                    .build(),
            )
        }
        return builder
            .setErrorsCount(0)
            .setWarningsCount(warnings.size)
            .setInfoCount(0)
            .build()
    }

    override suspend fun refresh(request: RefreshRequest): RefreshResponse {
        val builder = RefreshResponse.newBuilder()
        val ref =
            refresher
                ?: return builder
                    .addMessages(
                        ResponseMessage
                            .newBuilder()
                            .setSeverity(Severity.WARNING)
                            .setCode("refresh_not_supported")
                            .setHumanMessage(
                                "Refresh is not wired in this build — restart the service or configure a MetadataRefresher.",
                            ).build(),
                    ).build()
        val results = ref.refresh(sourceId = request.sourceId, force = request.force)
        for (r in results) {
            builder.addResults(
                SourceRefreshResult
                    .newBuilder()
                    .setSourceId(r.sourceId)
                    .setSuccess(r.success)
                    .setErrorMessage(r.errorMessage)
                    .setOldVersion(r.oldVersion)
                    .setNewVersion(r.newVersion)
                    .setSnapshotSwapped(r.snapshotSwapped)
                    .setNewModelVersion(r.newModelVersion),
            )
        }
        return builder.build()
    }

    override suspend fun traverseEdges(request: TraverseEdgesRequest): TraverseEdgesResponse {
        val snap =
            registry.read()
                ?: return TraverseEdgesResponse.newBuilder().addMessages(notReadyMessage()).build()
        val builder = TraverseEdgesResponse.newBuilder()
        val fromDomain = request.fromQualifiedName.toDomain()
        val fromObj = snap.model.objectByQname()[fromDomain]
        if (fromObj == null) {
            builder.addMessages(
                ResponseMessage
                    .newBuilder()
                    .setSeverity(Severity.WARNING)
                    .setCode("object_not_found")
                    .setHumanMessage(
                        "No object at ${request.fromQualifiedName.schemaCode}.${request.fromQualifiedName.namespace}.${request.fromQualifiedName.name}",
                    ).build(),
            )
            return builder.build()
        }
        // MD2: traverse the library ModelGraph (the moved TraverseEdgesHandler core).
        // Graph vertices are internal ids; proto edge-types/direction convert at the boundary.
        val maxDepth = (if (request.maxDepth <= 0) 1 else request.maxDepth).coerceAtMost(MAX_TRAVERSE_DEPTH)
        val edgeTypes = request.edgeTypesList.mapNotNull { it.toDomain() }.toSet()
        val kindFilter = request.kindFilterList.toSet()
        val graph = snap.graph
        val steps = graph.traverse(fromObj.internalId, edgeTypes, request.direction.toDomain(), maxDepth)
        for (step in steps) {
            val source = graph.byInternalId[step.edge.source] ?: continue
            val target = graph.byInternalId[step.edge.target] ?: continue
            if (kindFilter.isNotEmpty() && target.kind !in kindFilter) continue
            builder.addEdges(
                EdgeResult
                    .newBuilder()
                    .setType(step.edge.type.toProto())
                    .setDepth(step.depth)
                    .setSource(source.toObjectDescriptor())
                    .setTarget(target.toObjectDescriptor()),
            )
        }
        return builder.build()
    }

    /**
     * Golem P4 S4.2 — resolve a subject area (`def area accounting { ... }`) to its
     * package set + description + tags. A Golem Shem with `areas: [accounting]` calls
     * this to discover which Veles packages it must pull (via GetModel) for the area.
     *
     * Unknown area → `found = false`, empty packages, plus a Rule-6 WARNING
     * (`area_not_found`) — never a gRPC error. Referenced packages are returned
     * verbatim; GetModel validates them later.
     */
    override suspend fun resolveArea(request: ResolveAreaRequest): ResolveAreaResponse {
        val snap =
            registry.read()
                ?: return ResolveAreaResponse
                    .newBuilder()
                    .setFound(false)
                    .addMessages(notReadyMessage())
                    .build()
        val area =
            snap.model.areaByName(request.area)
                ?: return ResolveAreaResponse
                    .newBuilder()
                    .setFound(false)
                    .addMessages(
                        ResponseMessage
                            .newBuilder()
                            .setSeverity(Severity.WARNING)
                            .setCode("area_not_found")
                            .setHumanMessage("No area named '${request.area}'")
                            .build(),
                    ).build()
        return ResolveAreaResponse
            .newBuilder()
            .addAllPackages(area.packages)
            .setDescription(area.description)
            .addAllTags(area.tags)
            .setFound(true)
            .build()
    }

    // ----- helpers -----

    private fun notReadyMessage(): ResponseMessage =
        ResponseMessage
            .newBuilder()
            .setSeverity(Severity.WARNING)
            .setCode("metadata_not_ready")
            .setHumanMessage("Metadata service has no snapshot yet — initial load in progress")
            .build()
}

// ----- ListQueries / GetQuery helpers -----

/**
 * [locale] defaults to `""` because the locale-less RPCs (`ListQueries`, `GetQuery`)
 * build this too — see [selectDescription]. On the GetModel bundle path the caller
 * MUST pass `opts.locale`: a `ModelBundleQuery` carries this descriptor nested inside
 * its own, and two descriptors for one query answering differently is a bug waiting
 * to be read as data (NLS-P10 review).
 */
private fun Query.toQueryDescriptor(
    parseStatus: DomainParseStatus,
    locale: String = "",
): QueryDescriptor =
    QueryDescriptor
        .newBuilder()
        .setObjectDescriptor(toObjectDescriptor(locale))
        .setSourceLanguage(sourceLanguage.toProtoLanguage())
        .setParseStatus(parseStatus.toProtoParseStatus())
        .setParameterCount(parameters.size)
        .setSearch(search.toProto())
        .build()

private fun String.toProtoLanguage(): ProtoLanguage =
    when (uppercase().replace("-", "_")) {
        "SQL" -> ProtoLanguage.SQL
        "TRANSFORMATION_DSL", "TRANSDSL", "TRANS_DSL" -> ProtoLanguage.TRANSFORMATION_DSL
        "DATAFRAME_DSL", "DFDSL", "DF_DSL" -> ProtoLanguage.DATAFRAME_DSL
        "REL_NODE", "RELNODE", "PLAN_NODE", "PLANNODE" -> ProtoLanguage.REL_NODE
        else -> ProtoLanguage.LANGUAGE_UNSPECIFIED
    }

private fun DomainParseStatus.toProtoParseStatus(): ProtoParseStatus =
    when (this) {
        is DomainParseStatus.ParsePending -> ProtoParseStatus.PARSE_STATUS_PENDING
        is DomainParseStatus.ParseSuccess -> ProtoParseStatus.PARSE_STATUS_PARSED
        is DomainParseStatus.ParseFailure -> ProtoParseStatus.PARSE_STATUS_FAILED
    }

private fun ParseStatusFilter.matches(status: DomainParseStatus): Boolean =
    when (this) {
        ParseStatusFilter.PARSE_STATUS_FILTER_UNSPECIFIED -> true
        ParseStatusFilter.PARSE_STATUS_FILTER_PARSED -> status is DomainParseStatus.ParseSuccess
        ParseStatusFilter.PARSE_STATUS_FILTER_PENDING -> status is DomainParseStatus.ParsePending
        ParseStatusFilter.PARSE_STATUS_FILTER_FAILED -> status is DomainParseStatus.ParseFailure
        ParseStatusFilter.UNRECOGNIZED -> true
    }

/**
 * NLS-P10 (⚑GXP-D7, grammar 0.13) — pick the description text to serve for [locale].
 *
 * TTR 0.13 lets an author write `description:` either as a plain string or as a
 * localised map. The WIRE did not follow: `meta.v1.ObjectDescriptor.description` is
 * still one `string`, so the choice is made here, at the edge, and exactly once per
 * response. The chain (D7, contracts):
 *
 *  1. the requested locale, when the map carries it;
 *  2. the plain-string form (an author who wrote one string meant it for everyone);
 *  3. `en`;
 *  4. the first entry **by language code** — sorted, so the served text does not
 *     depend on the order the author happened to write the block in;
 *  5. `""`.
 *
 * An EMPTY [locale] means the caller expressed no preference and gets today's
 * behaviour: the plain form, or `""` when only a map was authored. The "empty locale
 * = all locales" rule that governs `LocalizedString` fields (`display_label`, search
 * hints — see [BundleOptions]) does NOT apply to a single-string field: there is no
 * way to return "all" of it, and guessing one would make the bytes a caller receives
 * depend on authoring order.
 *
 * **Which RPCs run the chain, and which cannot.** `GetModel` is the only request in
 * `meta.v1` that carries a `locale`, so it is the only one that can select: the whole
 * bundle — entities, their attributes, tables, views, both query lists (outer AND the
 * nested `QueryDescriptor`) and `RoleDetail` — goes through this function with
 * `BundleOptions.locale`. Every other surface (`ListObjects`, `GetObject`,
 * `GetSnapshot`, `Search`, `ListRoles`, `ListQueries`, `GetQuery`, the search-index
 * `CompileError.object`, `TraverseGraph` edges) has no locale to pass and therefore
 * serves the plain form — which is `""` for an object whose author wrote only a map.
 *
 * That is a consequence of NLS-P10's central constraint, not an oversight: the wire
 * did not widen, so a locale-less request has nothing to select with. Nothing in the
 * estate reads descriptions off those RPCs today (golem consumes the bundle). ⚑ If a
 * consumer ever needs a described object from a locale-less RPC, the fix is a `locale`
 * field on that request — a proto change with its own window — NOT a house default
 * here, which would make the served bytes depend on authoring order.
 */
private fun ModelObject.selectDescription(locale: String): String {
    if (locale.isEmpty()) return description
    descriptionLocalized.byLanguage[locale]?.let { return it }
    if (description.isNotEmpty()) return description
    descriptionLocalized.byLanguage["en"]?.let { return it }
    return descriptionLocalized.byLanguage
        .toSortedMap()
        .values
        .firstOrNull()
        ?: ""
}

private fun ModelObject.toObjectDescriptor(locale: String = ""): ObjectDescriptor =
    ObjectDescriptor
        .newBuilder()
        .setInternalId(internalId)
        .setQualifiedName(qname.toProto())
        // `qname.name` is the dotted leaf path under the namespace (e.g.
        // "QTYPDOK.NAZEV_TYPDOK" for a column under db.dbo). Callers like
        // fuzzy-matcher need just the trailing segment as a SQL identifier;
        // mirrors the DbColumn.localName() helper at the bottom of this file.
        .setLocalName(qname.name.substringAfterLast('.'))
        .setDescription(selectDescription(locale))
        .addAllTags(tags)
        .setSchemaCode(qname.schemaCode.toProto())
        .setKind(kind)
        .setSourceFile(sourceFile)
        .setBinding(toProtoBinding(binding))
        // RS-33 discovery accelerator: surface the object's semantics kind (er
        // entity / db table) so list_objects finds period/poi/fx tables cheaply.
        .also { b ->
            val kind = (this as? Entity)?.semanticsKind ?: (this as? DbTable)?.semanticsKind
            if (!kind.isNullOrEmpty()) b.semanticsKind = kind
        }.build()

private fun toProtoBinding(b: DomainBinding): BindingProto =
    when (b) {
        is DomainBinding.BoundReal ->
            BindingProto.newBuilder().setType(BindingType.BOUND_REAL).build()
        is DomainBinding.BoundSynthetic ->
            BindingProto
                .newBuilder()
                .setType(BindingType.BOUND_SYNTHETIC)
                .setReason(b.reason)
                .build()
        is DomainBinding.Unbound ->
            BindingProto
                .newBuilder()
                .setType(BindingType.UNBOUND)
                .setReason(b.reason)
                .build()
    }

// ----- RG-P3.S0 grounding-semantics projection (RS-33) -----
//
// Projects the typed model's grammar-4.2 semantics onto meta.v1. kind/role stay
// strings (open vocabulary in ttr-semantics); an unresolved/invalid block is
// dropped upstream (semantics == null / semanticsKind == null) and surfaces as a
// TTR-SEM load issue — Veles serves the object WITHOUT semantics, never guesses.

/**
 * The entity/table-side semantics message, or null when there is nothing to say.
 *
 * MS: built when EITHER facet has content — a grounding `kind:` or a mention `measures:`
 * list. `name:`/`code:` deliberately do NOT trigger it: they reach consumers through
 * `EntityDetail.name_attribute` / `code_attribute`, which predate MS and are now fed by
 * the model's D2 merge (MS-P1·S2), so an entity declaring only those keeps serving no
 * semantics message rather than a newly-appearing empty one.
 *
 * `measures` carries attribute LOCAL NAMES in declared order — the same rendering
 * `name_attribute` gets, because both come from a resolved same-model `SymbolRef.path`.
 */
private fun objectSemanticsProto(
    kind: String?,
    mention: ResolvedEntitySemantics?,
): EntitySemantics? {
    val measures = mention?.measures.orEmpty().map { it.attribute.path }
    if (kind.isNullOrEmpty() && measures.isEmpty()) return null
    return EntitySemantics
        .newBuilder()
        .also { if (!kind.isNullOrEmpty()) it.kind = kind }
        .addAllMeasures(measures)
        .build()
}

/**
 * The attribute/column-side semantics message, or null when there is nothing to say.
 *
 * `owner` is the declaring object's qname; the resolved `period:`/`currency:` refs carry
 * only a local `path`, so the cross-ref qname is rebuilt in the owner's schema/namespace
 * (same-model refs — the period table lives beside the fact entity).
 *
 * MS: `aggregation` is declared on the OWNER (`semantics { measures: [...] }`) and
 * denormalised onto the member here — veles owns the join so that a consumer holding one
 * AttributeDetail needs no second lookup. It can be the ONLY reason this message exists:
 * a measure attribute routinely carries no grounding `role:` at all.
 */
private fun attributeSemanticsProto(
    grounding: ResolvedAttributeSemantics?,
    owner: org.tatrman.ttr.metadata.model.QualifiedName,
    aggregation: String,
): AttributeSemantics? {
    if (grounding == null && aggregation.isEmpty()) return null
    val b = AttributeSemantics.newBuilder()
    grounding?.let { g ->
        b.role = g.role
        g.codeFormat?.takeIf { it.isNotEmpty() }?.let { b.codeFormat = it }
        g.period
            ?.path
            ?.takeIf { it.isNotEmpty() }
            ?.let { b.period = owner.copy(name = it).toProto() }
        g.currency
            ?.path
            ?.takeIf { it.isNotEmpty() }
            ?.let { b.currencyAttribute = it }
    }
    if (aggregation.isNotEmpty()) b.aggregation = aggregation
    return b.build()
}

/** The local name a mention ref is spelled with — `er.Sales.amount` → `amount`. */
private fun org.tatrman.ttr.metadata.model.QualifiedName.localName(): String = name.substringAfterLast('.')

/**
 * The measure lookup. Returns "" for an attribute its owner does not list — absence is the
 * answer (MS-R4), never a guessed default.
 */
private fun ResolvedEntitySemantics?.aggregationOf(member: org.tatrman.ttr.metadata.model.QualifiedName): String =
    this
        ?.measures
        ?.firstOrNull { it.attribute.path == member.localName() }
        ?.aggregation
        ?: ""

/**
 * The owner lookup that join needs, for the call sites holding only a member.
 *
 * ⛑ Deliberately NOT a defaulted parameter anywhere downstream: a forgotten argument would
 * silently serve an empty aggregation, and an empty aggregation is indistinguishable from
 * the legitimate "not a measure" answer, so nothing else would ever catch it.
 */
private fun Map<org.tatrman.ttr.metadata.model.QualifiedName, ModelObject>.mentionOf(
    owner: org.tatrman.ttr.metadata.model.QualifiedName,
): ResolvedEntitySemantics? =
    when (val o = this[owner]) {
        is Entity -> o.mentionSemantics
        is DbTable -> o.mentionSemantics
        else -> null
    }

// ----- Phase 2.2 detail builders -----

private fun LocalizedText.toProto(): ProtoLocalizedString =
    ProtoLocalizedString
        .newBuilder()
        .putAllByLanguage(byLanguage)
        .build()

private fun LocalizedTextList.toProto(): ProtoLocalizedStringList {
    val builder = ProtoLocalizedStringList.newBuilder()
    byLanguage.forEach { (lang, list) ->
        builder.putByLanguage(lang, ProtoStringList.newBuilder().addAllValues(list).build())
    }
    return builder.build()
}

private fun SearchHints.toProto(): ProtoSearchHints =
    ProtoSearchHints
        .newBuilder()
        .also { if (!keywords.isEmpty) it.keywords = keywords.toProto() }
        .addAllPatterns(patterns)
        .also { if (!descriptions.isEmpty) it.descriptions = descriptions.toProto() }
        .addAllExamples(examples)
        .addAllAliases(aliases)
        // MV (contracts §2.2): `fuzzy` now says "the method is partial"; `indexed` is the bit that
        // used to hide under its name. `match_method` is "" for a carrier with no vocabulary.
        .setFuzzy(MatchMethods.isPartial(matchMethod))
        .setSearchable(searchable)
        .setIndexed(indexed)
        .setMatchMethod(if (indexed) matchMethod ?: MatchMethods.DEFAULT else "")
        .build()

private fun Entity.toEntityDetail(): EntityDetail =
    EntityDetail
        .newBuilder()
        .setLabelPlural(labelPlural)
        .setNameAttribute(nameAttribute)
        .setCodeAttribute(codeAttribute)
        .addAllAliases(aliases)
        .also { if (!displayLabel.isEmpty) it.displayLabel = displayLabel.toProto() }
        .also { if (!search.isEmpty) it.search = search.toProto() }
        .also { b -> objectSemanticsProto(semanticsKind, mentionSemantics)?.let { b.semantics = it } }
        .build()

/**
 * new-golem Stage 04 — wrap an Entity into a `ModelBundleEntity` so the bundle
 * carries the ObjectDescriptor (qname / source_file / kind / binding) plus the
 * per-attribute details. Without the descriptor, callers can't key entities by
 * qname; without attributes inlined, they'd need a separate GetObject round-trip
 * for each field. PF-3 says the bundle is one round-trip.
 */
private fun Entity.toModelBundleEntity(opts: BundleOptions): ModelBundleEntity =
    ModelBundleEntity
        .newBuilder()
        .setObjectDescriptor(toObjectDescriptor(opts.locale))
        .setDetail(toEntityDetail().applyBundleOptions(opts))
        .addAllAttributes(attributes.map { it.toModelBundleAttribute(opts, mentionSemantics) })
        .build()

private fun Attribute.toModelBundleAttribute(
    opts: BundleOptions,
    owner: ResolvedEntitySemantics?,
): ModelBundleAttribute =
    ModelBundleAttribute
        .newBuilder()
        .setObjectDescriptor(toObjectDescriptor(opts.locale))
        .setDetail(toAttributeDetail(owner))
        .build()

/**
 * new-golem Stage 04 — wrap a Query into a `ModelBundleQuery` carrying the
 * full source text + parameter defs alongside the QueryDescriptor. The bare
 * descriptor only exposes `parameter_count`, which isn't enough for PackageContext
 * (contracts §6) to plan a turn.
 */
private fun Query.toModelBundleQuery(
    parseStatus: DomainParseStatus,
    opts: BundleOptions,
): ModelBundleQuery {
    val builder =
        ModelBundleQuery
            .newBuilder()
            .setObjectDescriptor(toObjectDescriptor(opts.locale))
            .setQueryDescriptor(toQueryDescriptor(parseStatus, opts.locale).applyBundleOptions(opts))
            .setSourceLanguage(sourceLanguage.toProtoLanguage())
            .setSourceText(sourceText)
    for (param in parameters) {
        builder.addParameters(
            org.tatrman.meta.v1.QueryParameterDef
                .newBuilder()
                .setName(param.name)
                .setType(param.type)
                .setLabel(param.label)
                .build(),
        )
    }
    return builder.build()
}

private fun Attribute.toAttributeDetail(owner: ResolvedEntitySemantics?): AttributeDetail =
    AttributeDetail
        .newBuilder()
        .setEntity(entity.toProto())
        .setType(type)
        .setIsKey(isKey)
        .setNullable(nullable)
        .also { if (!displayLabel.isEmpty) it.displayLabel = displayLabel.toProto() }
        .also { builder ->
            valueLabels.forEach { (code, text) -> builder.putValueLabels(code, text.toProto()) }
        }.also { if (!search.isEmpty) it.search = search.toProto() }
        .also { b -> attributeSemanticsProto(semantics, entity, owner.aggregationOf(qname))?.let { b.semantics = it } }
        .build()

private fun Role.toRoleDetail(locale: String = ""): RoleDetail =
    RoleDetail
        .newBuilder()
        .setDescription(selectDescription(locale))
        .also { if (!label.isEmpty) it.label = label.toProto() }
        .also { if (!search.isEmpty) it.search = search.toProto() }
        .build()

private fun org.tatrman.ttr.metadata.model.DrillMap.toDrillMapDetail(): DrillMapDetail =
    DrillMapDetail
        .newBuilder()
        .setName(qname.name)
        .setFromPattern(fromPattern.toProto())
        .setToPattern(toPattern.toProto())
        .putAllArgMapping(argMapping)
        .setExplicit(explicit)
        .setOverrideAuto(overrideAuto)
        .setSourceFile(sourceFile)
        .also { if (!display.isEmpty) it.display = display.toProto() }
        .build()

private fun Relation.toRelationDetail(): RelationDetail {
    val cardinalityProto =
        Cardinality
            .newBuilder()
            .setFromMin(cardinality.fromMin)
            .setFromMax(cardinality.fromMax)
            .setToMin(cardinality.toMin)
            .setToMax(cardinality.toMax)
            .build()
    val joinPairProtos =
        joinPairs.map { pair ->
            AttributeJoinPair
                .newBuilder()
                .setFromAttr(pair.fromAttr.toProto())
                .setToAttr(pair.toAttr.toProto())
                .build()
        }
    return RelationDetail
        .newBuilder()
        .setFromEntity(fromEntity.toProto())
        .setToEntity(toEntity.toProto())
        .setCardinality(cardinalityProto)
        .addAllJoinPairs(joinPairProtos)
        .build()
}

// ----- A4: GetModel bundle options (include_search_hints, locale) -----

/**
 * Options that shape the per-row content of a [ModelBundle]. Honoured by
 * [applyBundleOptions] extension functions applied after building each proto.
 *
 * * `includeSearchHints` — when false, drops the entire SearchHints block from
 *   entities/roles/queries and the entity-level `aliases` shortcut. Drops on the
 *   wire; partition signals are read off the domain object before this point.
 * * `locale` — when non-empty, narrows every [ProtoLocalizedString] /
 *   [ProtoLocalizedStringList] map to that one BCP-47 key (others dropped). Empty
 *   string means "return all locales" (the default).
 */
private data class BundleOptions(
    val includeSearchHints: Boolean,
    val locale: String,
)

private fun ProtoLocalizedString.filterLocale(locale: String): ProtoLocalizedString {
    if (locale.isEmpty()) return this
    val v = byLanguageMap[locale] ?: return ProtoLocalizedString.getDefaultInstance()
    return ProtoLocalizedString.newBuilder().putByLanguage(locale, v).build()
}

private fun ProtoLocalizedStringList.filterLocale(locale: String): ProtoLocalizedStringList {
    if (locale.isEmpty()) return this
    val v = byLanguageMap[locale] ?: return ProtoLocalizedStringList.getDefaultInstance()
    return ProtoLocalizedStringList.newBuilder().putByLanguage(locale, v).build()
}

private fun ProtoSearchHints.filterLocale(locale: String): ProtoSearchHints {
    if (locale.isEmpty()) return this
    return toBuilder()
        .setKeywords(keywords.filterLocale(locale))
        .setDescriptions(descriptions.filterLocale(locale))
        .build()
}

private fun EntityDetail.applyBundleOptions(opts: BundleOptions): EntityDetail {
    var changed = false
    val b = toBuilder()
    if (!opts.includeSearchHints) {
        b.clearSearch()
        b.clearAliases()
        changed = true
    } else if (hasSearch()) {
        b.search = search.filterLocale(opts.locale)
        changed = changed || opts.locale.isNotEmpty()
    }
    if (opts.locale.isNotEmpty() && hasDisplayLabel()) {
        b.displayLabel = displayLabel.filterLocale(opts.locale)
        changed = true
    }
    return if (changed) b.build() else this
}

private fun RoleDetail.applyBundleOptions(opts: BundleOptions): RoleDetail {
    var changed = false
    val b = toBuilder()
    if (!opts.includeSearchHints) {
        b.clearSearch()
        changed = true
    } else if (hasSearch()) {
        b.search = search.filterLocale(opts.locale)
        changed = changed || opts.locale.isNotEmpty()
    }
    if (opts.locale.isNotEmpty() && hasLabel()) {
        b.label = label.filterLocale(opts.locale)
        changed = true
    }
    return if (changed) b.build() else this
}

private fun QueryDescriptor.applyBundleOptions(opts: BundleOptions): QueryDescriptor {
    var changed = false
    val b = toBuilder()
    if (!opts.includeSearchHints) {
        b.clearSearch()
        changed = true
    } else if (hasSearch()) {
        b.search = search.filterLocale(opts.locale)
        changed = changed || opts.locale.isNotEmpty()
    }
    return if (changed) b.build() else this
}

private fun Er2CncRoleMapping.toEr2CncRoleMappingDetail(): Er2CncRoleMappingDetail =
    Er2CncRoleMappingDetail
        .newBuilder()
        .setEntity(entity.toProto())
        .setRole(role.toProto())
        .build()

// ----- DF-M07: per-kind detail for db tables/views/columns/FKs, er2db mappings, and queries -----

// Column qnames are stored table-qualified ("customers.id"); the column's local name is the
// segment after the last dot.
private fun DbColumn.localName(): String = qname.name.substringAfterLast('.')

private fun DbColumn.toColumnSummary(owner: ResolvedEntitySemantics?): DbColumnSummary =
    DbColumnSummary
        .newBuilder()
        .setName(localName())
        .setDataType(dataType)
        .setNullable(nullable)
        .setBinding(toProtoBinding(binding))
        .also { b -> attributeSemanticsProto(semantics, table, owner.aggregationOf(qname))?.let { b.semantics = it } }
        .build()

private fun DbTable.toDbTableDetail(): DbTableDetail =
    DbTableDetail
        .newBuilder()
        .addAllColumns(columns.map { it.toColumnSummary(mentionSemantics) })
        .addAllPrimaryKey(primaryKey)
        .also { b -> objectSemanticsProto(semanticsKind, mentionSemantics)?.let { b.semantics = it } }
        // MS (review-083 F1) — the db twin of EntityDetail.name_attribute/code_attribute.
        // Read straight off the resolved block: unlike the er side there is no legacy
        // `nameAttribute:` on a table, so there is nothing to merge and the semantics block is
        // the only source. Without these two lines the declaration was accepted, validated and
        // then dropped here, and contracts §9's MS-R3 count could never take its code branch on
        // a db-table estate.
        .also { b ->
            mentionSemantics
                ?.name
                ?.path
                ?.takeIf { it.isNotEmpty() }
                ?.let { b.nameAttribute = it }
        }.also { b ->
            mentionSemantics
                ?.code
                ?.path
                ?.takeIf { it.isNotEmpty() }
                ?.let { b.codeAttribute = it }
        }.build()

private fun DbView.toDbViewDetail(): DbViewDetail =
    DbViewDetail
        .newBuilder()
        // A view declares no `semantics { }` block of its own, so its columns have no owner
        // to take an aggregation from.
        .addAllColumns(columns.map { it.toColumnSummary(null) })
        .setDefinitionSql(definitionSql)
        .build()

private fun DbTable.toModelBundleTable(opts: BundleOptions): ModelBundleTable =
    ModelBundleTable
        .newBuilder()
        .setObjectDescriptor(toObjectDescriptor(opts.locale))
        .setDetail(toDbTableDetail())
        .build()

private fun DbView.toModelBundleView(opts: BundleOptions): ModelBundleView =
    ModelBundleView
        .newBuilder()
        .setObjectDescriptor(toObjectDescriptor(opts.locale))
        .setDetail(toDbViewDetail())
        .build()

private fun DbColumn.toDbColumnDetail(owner: ResolvedEntitySemantics?): DbColumnDetail =
    DbColumnDetail
        .newBuilder()
        .setTable(table.toProto())
        .setDataType(dataType)
        .setNullable(nullable)
        .setIsPrimaryKey(isPrimaryKey)
        .setIsForeignKey(isForeignKey)
        .also { if (!search.isEmpty) it.search = search.toProto() }
        .also { b -> attributeSemanticsProto(semantics, table, owner.aggregationOf(qname))?.let { b.semantics = it } }
        .build()

private fun DbForeignKey.toDbForeignKeyDetail(): DbForeignKeyDetail =
    DbForeignKeyDetail
        .newBuilder()
        .addAllFromColumns(fromColumns.map { it.toProto() })
        .addAllToColumns(toColumns.map { it.toProto() })
        .build()

private fun Er2DbEntityMapping.toEr2DbEntityMappingDetail(): Er2DbEntityMappingDetail =
    Er2DbEntityMappingDetail
        .newBuilder()
        .setEntity(entity.toProto())
        .also {
            when (val t = target) {
                is MappingTarget.Table -> it.table = t.qname.toProto()
                is MappingTarget.View -> it.view = t.qname.toProto()
                is MappingTarget.SqlQuery -> it.sqlQuery = t.qname.toProto()
            }
        }
        // `where_filter` is not modelled on the domain Er2DbEntityMapping (filtered targets are
        // expanded into synthesised queries instead), so it's left unset.
        .build()

private fun Er2DbAttributeMapping.toEr2DbAttributeMappingDetail(): Er2DbAttributeMappingDetail =
    Er2DbAttributeMappingDetail
        .newBuilder()
        .setAttribute(attribute.toProto())
        .also {
            // Column targets map cleanly; the free-form Expression target is a raw unparsed string
            // on the domain side — not reconstructed into a structured plan.v1.Expression here.
            when (val t = target) {
                is AttributeMappingTarget.Column -> it.column = t.qname.toProto()
                is AttributeMappingTarget.Expression -> Unit
            }
        }.build()

private fun Er2DbRelationMapping.toEr2DbRelationMappingDetail(): Er2DbRelationMappingDetail =
    Er2DbRelationMappingDetail
        .newBuilder()
        .setRelation(relation.toProto())
        .setForeignKey(foreignKey.toProto())
        .build()

private fun Query.toQueryDetail(parseStatus: DomainParseStatus): QueryDetail =
    QueryDetail
        .newBuilder()
        .setSourceLanguage(sourceLanguage.toProtoLanguage())
        .setSourceText(sourceText)
        .addAllParameters(
            parameters.map {
                ProtoQueryParameterDef
                    .newBuilder()
                    .setName(it.name)
                    .setType(it.type)
                    .setLabel(it.label)
                    .build()
            },
        ).setParseStatus(parseStatus.toProtoParseStatus())
        .also { if (parseStatus is DomainParseStatus.ParseFailure) it.parseErrorMessage = parseStatus.message }
        .also { if (!search.isEmpty) it.search = search.toProto() }
        // GH #112 — the canonical form of every parsed query. The snapshot is the model the
        // translator runs on (`SnapshotModelHandle`): MAP_TO_PHYSICAL expands a query-backed entity
        // into this plan, so without it every ER query over such an entity failed with
        // `PlanNode case 'NODE_NOT_SET'`. GetSnapshot has no per-query include flag (GetQuery does),
        // so it always carries it; a stored plan that does not decode is left unset, as GetQuery
        // reports it (`canonical_form_unreadable`). `uses` isn't tracked on the model yet (DF-T03).
        .also {
            if (parseStatus is DomainParseStatus.ParseSuccess) {
                runCatching {
                    org.tatrman.plan.v1.PlanNode
                        .parseFrom(parseStatus.canonicalFormProtoBytes)
                }.onSuccess { plan -> it.canonicalForm = plan }
            }
        }.build()

private fun org.tatrman.ttr.metadata.registry.RegistrySnapshot.toProtoDescriptor(): ProtoModelDescriptor =
    ProtoModelDescriptor
        .newBuilder()
        .setId(model.descriptor.id)
        .setName(model.descriptor.name)
        .setDescription(model.descriptor.description)
        .addAllTags(model.descriptor.tags)
        .setVersion(model.version.value)
        .setVersionSwappedAt(swappedAt.toString())
        .putAllObjectCounts(
            model
                .objectByQname()
                .values
                .groupingBy { "${it.qname.schemaCode}.${it.kind}" }
                .eachCount(),
        ).build()

/** `fuzzy_only`'s deprecation WARN — once per PROCESS (MV contracts §2.2), not once per instance. */
private val fuzzyOnlyWarned =
    java.util.concurrent.atomic
        .AtomicBoolean(false)
