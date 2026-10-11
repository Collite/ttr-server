// SPDX-License-Identifier: Apache-2.0
package org.tatrman.dispatch.world

import com.typesafe.config.ConfigFactory
import org.tatrman.plan.v1.QualifiedName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class WorldConfigSpec :
    StringSpec({
        val cfg =
            ConfigFactory.parseString(
                """
                world {
                  default-connection = "hartland-default"
                  table-connections {
                    "db.dbo.store_sales" = "hartland-store-exact"
                    "db.dbo.store_*"       = "hartland-store"
                    "db.dbo.customer*"     = "hartland-customer"
                  }
                }
                """.trimIndent(),
            )
        val world = WorldConfig.fromConfig(cfg)

        "exact match wins over prefix" {
            world.routingFor(qname("db.dbo.store_sales")) shouldBe "hartland-store-exact"
        }

        "prefix match works" {
            world.routingFor(qname("db.dbo.store_returns")) shouldBe "hartland-store"
        }

        "prefix match without underscore separator" {
            world.routingFor(qname("db.dbo.customer_address")) shouldBe "hartland-customer"
        }

        "fall back to null when no pattern matches" {
            world.routingFor(qname("db.dbo.item")) shouldBe null
        }

        "resolveOrDefault falls back to default-connection" {
            world.resolveOrDefault(qname("db.dbo.item")) shouldBe "hartland-default"
        }

        // WS-T2 T6 — the TPC-DS warehouse tables route to the pg-tpcds connection (served by Postgres).
        val tpcds =
            WorldConfig.fromConfig(
                ConfigFactory.parseString(
                    """
                    world {
                      default-connection = "hartland-default"
                      table-connections {
                        "db.dbo.store_sales"   = "pg-tpcds"
                        "db.dbo.catalog_sales" = "pg-tpcds"
                        "db.dbo.web_sales"     = "pg-tpcds"
                        "db.dbo.date_dim"      = "pg-tpcds"
                        "db.dbo.item"          = "pg-tpcds"
                        "db.dbo.customer"      = "pg-tpcds"
                        "db.dbo.store"         = "pg-tpcds"
                      }
                    }
                    """.trimIndent(),
                ),
            )

        "TPC-DS fact + dimension tables route to pg-tpcds" {
            listOf(
                "db.dbo.store_sales",
                "db.dbo.catalog_sales",
                "db.dbo.web_sales",
                "db.dbo.date_dim",
                "db.dbo.item",
                "db.dbo.customer",
                "db.dbo.store",
            ).forEach { tpcds.routingFor(qname(it)) shouldBe "pg-tpcds" }
        }

        "an unmodelled table still falls back to the default connection" {
            tpcds.resolveOrDefault(qname("db.dbo.web_returns")) shouldBe "hartland-default"
        }
    })

private fun qname(dotPath: String): QualifiedName {
    val parts = dotPath.split(".")
    return QualifiedName
        .newBuilder()
        .setSchemaCode(
            org.tatrman.plan.v1.SchemaCode
                .valueOf(parts[0].uppercase()),
        ).setNamespace(parts.getOrNull(1).orEmpty())
        .setName(parts.getOrNull(2).orEmpty())
        .build()
}
