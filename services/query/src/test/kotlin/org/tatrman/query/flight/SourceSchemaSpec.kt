// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.plan.v1.SchemaCode

/**
 * BI-0 memo S-8 — an edge statement states the layer its qualified names are written in. Found live on hartland
 * (BI-3·S3.1 T1): with `source_schema` unset the door's detector resolves bare table names only, so
 * `SELECT … FROM db.dbo.warehouse` was refused `detection_failed` ("warehouse exist in multiple schemas (db, er)") —
 * 34 of hartland's 36 entities share a name with a bound table.
 */
class SourceSchemaSpec :
    StringSpec({
        "er.entity and obj.query name the ER layer, quoted or not" {
            SourceSchema.of("SELECT * FROM er.entity.warehouse") shouldBe SchemaCode.ER
            SourceSchema.of("""SELECT * FROM "er"."entity"."warehouse"""") shouldBe SchemaCode.ER
            // A quoted dotted schema arrives unfolded (#275): `"er.entity"` → `er."entity"`.
            SourceSchema.of("""SELECT * FROM er."entity"."warehouse"""") shouldBe SchemaCode.ER
            SourceSchema.of("""SELECT "er"."entity"."warehouse"."city" FROM "er"."entity"."warehouse"""") shouldBe
                SchemaCode.ER
            // The spike's ledger 11b: a logical query is a table under ER.
            SourceSchema.of("SELECT * FROM obj.query.revenue_per_customer") shouldBe SchemaCode.ER
            SourceSchema.of("SELECT * FROM ER.ENTITY.WAREHOUSE") shouldBe SchemaCode.ER
        }

        "db.dbo names the DB layer, quoted or not" {
            SourceSchema.of("SELECT w_city FROM db.dbo.warehouse") shouldBe SchemaCode.DB
            SourceSchema.of("""SELECT * FROM "db"."dbo"."warehouse"""") shouldBe SchemaCode.DB
            SourceSchema.of("""SELECT * FROM db."dbo".warehouse""") shouldBe SchemaCode.DB
            SourceSchema.of("SELECT * FROM DB.DBO.WAREHOUSE") shouldBe SchemaCode.DB
        }

        "er and db: ER is stated, so the door refuses the mixed tree with LQ-2's own sentence (ledger 10b)" {
            SourceSchema.of("SELECT * FROM er.entity.warehouse JOIN db.dbo.channel_sales") shouldBe SchemaCode.ER
            SourceSchema.of("SELECT * FROM er.entity.warehouse, obj.query.q, db.dbo.inventory") shouldBe SchemaCode.ER
        }

        // ⚑R123-D (review-123 R9) — a saved query reads either layer: the door picks the one its body reads. Stating ER
        // here would refuse a DB-lane saved query joined to a table as mixed.
        "obj and db: unstated, the door decides by what the saved query reads" {
            SourceSchema.of("SELECT * FROM db.dbo.warehouse, obj.query.revenue_per_customer") shouldBe
                SchemaCode.SCHEMA_CODE_UNSPECIFIED
            SourceSchema.of("SELECT * FROM obj.query.q JOIN db.dbo.inventory i ON q.k = i.k") shouldBe
                SchemaCode.SCHEMA_CODE_UNSPECIFIED
            SourceSchema.of("SELECT * FROM obj.query.revenue_per_customer") shouldBe SchemaCode.ER
        }

        // ⚑R123-C (review-123 R8) — the stated layer is the statement's: the door reads its bare names in it (live on
        // hartland: `db.dbo.inventory i JOIN warehouse w` reads the table warehouse).
        "a bare name beside a qualified one is stated with it" {
            SourceSchema.of("SELECT * FROM db.dbo.inventory i JOIN warehouse w ON i.k = w.k") shouldBe SchemaCode.DB
            SourceSchema.of("SELECT * FROM shopper s JOIN er.entity.sale x ON s.k = x.k") shouldBe SchemaCode.ER
        }

        "no qualified name: unstated, the door autodetects as for any caller" {
            SourceSchema.of("SELECT * FROM warehouse") shouldBe SchemaCode.SCHEMA_CODE_UNSPECIFIED
            SourceSchema.of("SELECT 1") shouldBe SchemaCode.SCHEMA_CODE_UNSPECIFIED
        }

        "literals, comments and two-part names are not qualifiers" {
            SourceSchema.of("SELECT * FROM er.entity.warehouse // db.dbo.x") shouldBe SchemaCode.ER
            SourceSchema.of("SELECT 'db.dbo.warehouse' AS s FROM er.entity.warehouse") shouldBe SchemaCode.ER
            SourceSchema.of("-- db.dbo.warehouse\nSELECT * FROM er.entity.warehouse") shouldBe SchemaCode.ER
            SourceSchema.of("/* er.entity.x */ SELECT * FROM db.dbo.warehouse") shouldBe SchemaCode.DB
            // An alias called `db` (or `er`): `db.city` is a column of it, not a layer.
            SourceSchema.of("SELECT db.city FROM er.entity.warehouse AS db") shouldBe SchemaCode.ER
            SourceSchema.of("SELECT er.w_city FROM db.dbo.warehouse er") shouldBe SchemaCode.DB
        }

        "a catalog qualifier the edge blanked out leaves the layer readable" {
            val blanked =
                (
                    CatalogQualifier.resolve("SELECT * FROM hartland_cz.db.dbo.warehouse", "hartland_cz") as
                        CatalogQualifier.Outcome.Run
                ).sql
            SourceSchema.of(blanked) shouldBe SchemaCode.DB
        }

        "after the edge's rewrite: an unfolded quoted schema names its layer, an alias spelled like one does not" {
            fun sent(sql: String) = (CatalogQualifier.resolve(sql, "hartland_cz") as CatalogQualifier.Outcome.Run).sql
            SourceSchema.of(sent("""SELECT t.* FROM hartland_cz."db.dbo".warehouse t""")) shouldBe SchemaCode.DB
            SourceSchema.of(sent("""SELECT "er.w".city FROM db.dbo.warehouse AS "er.w"""")) shouldBe SchemaCode.DB
        }
    })
