// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo

import org.tatrman.geo.discover.ColumnRef
import org.tatrman.geo.discover.GeoColumns
import org.tatrman.geo.discover.GeoDiscovery
import org.tatrman.geo.discover.PoiEntity
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode

/**
 * Domain fake for the [GeoDiscovery] port (RG-P3.S0 metadata seam). Backs geo's discovery/recipe/POI
 * tests from an in-memory object list, answering [geoColumns]/[poiEntity] with the recipe layer's
 * domain types directly — **no metadata protos** (the real proto mapping lives in
 * `MetaV1GeoDiscovery`, proven by its component spec against in-process Metadata).
 */
class FakeMetadataClient(
    private val objects: List<Obj>,
) : GeoDiscovery {
    /** One metadata object. `role`/`semanticKind`/`nameAttribute` are the semantics strings; null/"" = none. */
    data class Obj(
        val pkg: String,
        val entityName: String,
        val name: String,
        val kind: String, // "entity" | "attribute"
        val role: String? = null, // attribute role, e.g. "geo_lat"
        val semanticKind: String? = null, // entity kind, e.g. "poi"
        val nameAttribute: String = "", // entity: name_attribute (POI display column)
    ) {
        val qname: QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace(entityName)
                .setName(name)
                .setPackage(pkg)
                .build()
    }

    private fun Obj.toColumnRef(): ColumnRef = ColumnRef(qname = qname, entityName = entityName, columnName = name)

    private fun firstRoleColumn(
        role: String,
        pkg: String,
    ): ColumnRef? =
        objects
            .firstOrNull { it.pkg == pkg && it.kind == "attribute" && it.role == role }
            ?.toColumnRef()

    override suspend fun geoColumns(pkg: String): GeoColumns? {
        val lat = firstRoleColumn("geo_lat", pkg) ?: return null
        val lon = firstRoleColumn("geo_lon", pkg) ?: return null
        return GeoColumns(lat, lon)
    }

    override suspend fun poiEntity(pkg: String): PoiEntity? {
        val entity =
            objects.firstOrNull {
                it.pkg == pkg && it.kind == "entity" && it.semanticKind == "poi"
            } ?: return null
        val nameColumn = entity.nameAttribute.ifEmpty { return null }
        val geo = geoColumns(pkg) ?: return null
        return PoiEntity(
            entity = entity.qname,
            entityName = entity.entityName,
            nameColumn = nameColumn,
            latColumn = geo.lat.columnName,
            lonColumn = geo.lon.columnName,
        )
    }

    override suspend fun probeReady(): Boolean = true

    companion object {
        /**
         * A POI fixture: package "cnc" with a `poi`-kind entity Store carrying `geo_lat` (`lat`) and
         * `geo_lon` (`lon`) coordinate columns + a `name_attribute` (`store_name`) for POI-in-model
         * anchor resolution.
         */
        fun poi(pkg: String = "cnc"): FakeMetadataClient =
            FakeMetadataClient(
                listOf(
                    Obj(pkg, "Store", "Store", "entity", semanticKind = "poi", nameAttribute = "store_name"),
                    Obj(pkg, "Store", "lat", "attribute", role = "geo_lat"),
                    Obj(pkg, "Store", "lon", "attribute", role = "geo_lon"),
                ),
            )

        /** A package with geo columns but NO POI entity — the ModelPoiResolver must fall through. */
        fun noPoi(pkg: String = "cnc"): FakeMetadataClient =
            FakeMetadataClient(
                listOf(
                    Obj(pkg, "Store", "lat", "attribute", role = "geo_lat"),
                    Obj(pkg, "Store", "lon", "attribute", role = "geo_lon"),
                ),
            )
    }
}
