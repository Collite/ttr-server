// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import com.google.protobuf.TextFormat
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.fuzzy.api.GrpcService
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.PostgresConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.fetchSqlRows
import org.tatrman.fuzzy.v1.FuzzyStatusRequest
import org.tatrman.meta.v1.ListMemberVocabulariesRequest
import org.tatrman.meta.v1.ListMemberVocabulariesResponse
import org.tatrman.meta.v1.MemberVocabulary
import org.tatrman.meta.v1.PageInfo
import org.tatrman.meta.v1.VelesServiceGrpc
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import java.nio.file.Files
import java.nio.file.Path

/**
 * MV-T2 T7 — the hartland shape, end to end on the lex-matcher side: Veles' listing as T1 recorded it
 * for hartland (the four member vocabularies, their methods, their read plans byte for byte), run
 * through the real loader and the real JDBC row mapping against an H2 warehouse in PostgreSQL mode,
 * read back through `GetStatus`. `MV_STATUS_DUMP=<file>` writes the response — the reference the
 * control room records.
 */
class HartlandShapedStatusTest :
    StringSpec({

        // T1's hartland listing (tasks-mv-t1.md T7), verbatim.
        val hartland =
            listOf(
                Triple(
                    "er.entity.customer_address.state",
                    "EXACT",
                    "SELECT \"ca_address_sk\" AS \"sk\", \"ca_state\" AS \"state\" FROM \"customer_address\" " +
                        "GROUP BY \"ca_address_sk\", \"ca_state\" ORDER BY \"ca_address_sk\"",
                ),
                Triple(
                    "er.entity.store.state",
                    "EXACT",
                    "SELECT \"s_store_sk\" AS \"sk\", \"s_state\" AS \"state\" FROM \"store\" " +
                        "GROUP BY \"s_store_sk\", \"s_state\" ORDER BY \"s_store_sk\"",
                ),
                Triple(
                    "er.entity.store.store_name",
                    "TYPOS(1)",
                    "SELECT \"s_store_sk\" AS \"sk\", \"s_store_name\" AS \"store_name\" FROM \"store\" " +
                        "GROUP BY \"s_store_sk\", \"s_store_name\" ORDER BY \"s_store_sk\"",
                ),
                Triple(
                    "er.entity.warehouse.state",
                    "EXACT",
                    "SELECT \"w_warehouse_sk\" AS \"sk\", \"w_state\" AS \"state\" FROM \"warehouse\" " +
                        "GROUP BY \"w_warehouse_sk\", \"w_state\" ORDER BY \"w_warehouse_sk\"",
                ),
            )

        val veles =
            object : VelesServiceGrpc.VelesServiceImplBase() {
                override fun listMemberVocabularies(
                    request: ListMemberVocabulariesRequest,
                    responseObserver: StreamObserver<ListMemberVocabulariesResponse>,
                ) {
                    val items =
                        hartland.map { (category, method, sql) ->
                            val (_, ns, name) = category.split('.', limit = 3)
                            MemberVocabulary
                                .newBuilder()
                                .setCategory(category)
                                .setAttribute(
                                    QualifiedName
                                        .newBuilder()
                                        .setSchemaCode(SchemaCode.ER)
                                        .setNamespace(ns)
                                        .setName(name),
                                ).setKeyAttribute(category.substringBeforeLast('.') + ".sk")
                                .setReadSql(sql)
                                .setDialect(request.dialect)
                                .setMatchMethod(method)
                                .setVersion("sha256:$category")
                                .build()
                        }
                    responseObserver.onNext(
                        ListMemberVocabulariesResponse
                            .newBuilder()
                            .addAllItems(items)
                            .setPageInfo(PageInfo.newBuilder().setTotalCount(items.size))
                            .build(),
                    )
                    responseObserver.onCompleted()
                }
            }

        "hartland's four member vocabularies load from the warehouse and read back through GetStatus" {
            Database.connect(
                "jdbc:h2:mem:mv-hartland;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
            transaction {
                exec(
                    "CREATE TABLE \"store\" (\"s_store_sk\" INT, \"s_state\" VARCHAR(2), \"s_store_name\" VARCHAR(50))",
                )
                // A store with no state: no member to match — skipped, not a lost category.
                exec(
                    "INSERT INTO \"store\" VALUES (1, 'TN', 'ought'), (2, 'TN', 'able'), (3, 'TX', 'pri'), (4, NULL, 'ese')",
                )
                exec("CREATE TABLE \"customer_address\" (\"ca_address_sk\" INT, \"ca_state\" VARCHAR(2))")
                exec("INSERT INTO \"customer_address\" VALUES (10, 'TN'), (11, 'GA'), (12, 'TN')")
                exec("CREATE TABLE \"warehouse\" (\"w_warehouse_sk\" INT, \"w_state\" VARCHAR(2))")
                exec("INSERT INTO \"warehouse\" VALUES (20, 'TN')")
            }

            val server =
                InProcessServerBuilder
                    .forName("mv-hartland")
                    .directExecutor()
                    .addService(veles)
                    .build()
                    .start()
            val channel = InProcessChannelBuilder.forName("mv-hartland").directExecutor().build()
            val repo =
                StringRepository(
                    AppConfig(
                        serverPort = 7141,
                        grpcPort = 7241,
                        grpcReflectionEnabled = false,
                        refreshIntervalSeconds = 0,
                        tokenBasedConfig = TokenBasedConfig(),
                        nlp = NlpConfig(),
                        loaderSource = LoaderSourceConfig(source = "metadata"),
                        metadata = MetadataConfig(),
                    ),
                    MetadataLoaderSource(
                        client = MetadataServiceClient(channel, timeoutMs = 2_000),
                        dialect = PostgresConfig("h", 1, "hartland_us", "u", "p"),
                        sourceNamespace = "",
                        fetchRows = ::fetchSqlRows,
                    ),
                )
            try {
                val status =
                    runBlocking {
                        repo.forceRefresh()
                        GrpcService(FuzzyMatcher(repo), repo).getStatus(FuzzyStatusRequest.getDefaultInstance())
                    }
                System.getenv("MV_STATUS_DUMP")?.let {
                    Files.writeString(Path.of(it), TextFormat.printer().printToString(status))
                }

                // A-MV-15 (review-104 F2): a member is a VALUE — two stores in TN are one `TN`.
                status.categoriesList.map { "${it.category} (${it.matchMethod}): ${it.size}" } shouldContainExactly
                    listOf(
                        "er.entity.customer_address.state (EXACT): 2",
                        "er.entity.store.state (EXACT): 2",
                        "er.entity.store.store_name (TYPOS(1)): 4",
                        "er.entity.warehouse.state (EXACT): 1",
                    )
                status.warningsCount shouldBe 0
                status.layerVersions.memberIndexVersionsMap.keys shouldBe hartland.map { it.first }.toSet()
                // ...and its id is the value the attribute stores: what a filter compares it against.
                repo.getCandidates("er.entity.store.state").map { it.id to it.value } shouldContainExactly
                    listOf("TN" to "TN", "TX" to "TX")
            } finally {
                repo.close()
                channel.shutdownNow()
                server.shutdownNow()
            }
        }

        "review-104 F11 — a read plan that does not open with SELECT still runs as a query" {
            Database.connect(
                "jdbc:h2:mem:mv-with;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
            transaction {
                exec("CREATE TABLE \"store\" (\"s_store_sk\" INT, \"s_state\" VARCHAR(2))")
                exec("INSERT INTO \"store\" VALUES (1, 'TN'), (2, 'TX')")
            }
            // The shape a translator renders for a query-backed entity: a CTE, or a parenthesised
            // SELECT. Left to guess, Exposed sends both through executeUpdate.
            val withPlan =
                "WITH \"src\" AS (SELECT \"s_store_sk\", \"s_state\" FROM \"store\") " +
                    "SELECT \"s_store_sk\", \"s_state\" FROM \"src\" ORDER BY 1"
            val parenthesised = "(SELECT \"s_store_sk\", \"s_state\" FROM \"store\" ORDER BY 1)"

            fetchSqlRows(withPlan) shouldContainExactly listOf(ReadRow("1", "TN"), ReadRow("2", "TX"))
            fetchSqlRows(parenthesised) shouldContainExactly listOf(ReadRow("1", "TN"), ReadRow("2", "TX"))
        }
    })
