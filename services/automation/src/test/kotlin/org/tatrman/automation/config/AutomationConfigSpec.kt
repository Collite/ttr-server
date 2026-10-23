// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.config

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.Duration
import java.time.ZoneId

/** AU contracts §8: the typed config, fail-at-load naming every missing required key, secrets kept out of logs. */
class AutomationConfigSpec :
    StringSpec({
        val required =
            """
            automation.db.host = "pg"
            automation.door.queryUrl = "query:7240"
            automation.door.velesUrl = "veles:7212"
            automation.auth.jwksUrl = "http://keycloak:8080/realms/r/protocol/openid-connect/certs"
            automation.auth.issuer = "https://id.example/realms/r"
            """.trimIndent()

        // The packaged application.conf supplies every default; the test supplies only the required keys.
        fun load(
            hocon: String,
            env: Map<String, String> = emptyMap(),
        ) = AutomationConfig.load(
            ConfigFactory
                .parseString(
                    hocon,
                ).withFallback(ConfigFactory.parseResources("application.conf"))
                .resolve(),
            env,
        )

        "the defaults of application.conf type cleanly once the required keys are set" {
            val c = load(required)
            c.door shouldBe DoorConfig("query:7240", "veles:7212", sidecarUrl = null)
            c.auth.jwksUrl shouldBe "http://keycloak:8080/realms/r/protocol/openid-connect/certs"
            c.auth.issuer shouldBe "https://id.example/realms/r"
            c.auth.audience shouldBe null
            c.auth.authorMap shouldBe emptyMap()
            c.db.getString("database") shouldBe "automation"
            c.budgets shouldBe BudgetsConfig(Duration.ofSeconds(60), Duration.ofMinutes(30), 200, 200)
            c.retention shouldBe RetentionConfig(stagingHours = 24, graceHours = 1, protocolDays = 400)
            c.scheduler shouldBe SchedulerConfig(ZoneId.of("UTC"), enabled = true)
            c.previews shouldBe PreviewsConfig(emptyList(), 24)
            c.bundleGit.branch shouldBe "master"
            c.staging.kind shouldBe "local"
            c.paused shouldBe false
            c.runner shouldBe RunnerConfig(4)
            c.clockSkew shouldBe Duration.ZERO
            c.connections shouldBe emptyMap()
        }

        "a missing required key fails at load, and the message names every missing key at once" {
            val e = shouldThrow<IllegalArgumentException> { load("""automation.door.queryUrl = "query:7240"""") }
            e.message shouldContain "automation.db.host"
            e.message shouldContain "automation.door.velesUrl"
            e.message shouldContain "automation.auth.jwksUrl"
            e.message shouldContain "automation.auth.issuer"
            e.message shouldNotContain "automation.door.queryUrl"
        }

        "a blank required key counts as missing" {
            val e = shouldThrow<IllegalArgumentException> { load(required + "\nautomation.auth.jwksUrl = \"\"") }
            e.message shouldContain "automation.auth.jwksUrl"
        }

        "the token endpoint defaults to the JWKS URL's sibling; an explicit one wins" {
            load(required).auth.tokenUrl shouldBe "http://keycloak:8080/realms/r/protocol/openid-connect/token"
            load(required + "\nautomation.auth.tokenUrl = \"https://id.example/token\"").auth.tokenUrl shouldBe
                "https://id.example/token"
        }

        "without a tokenUrl, a JWKS URL that is not Keycloak-shaped fails at load, named" {
            val e =
                shouldThrow<IllegalArgumentException> {
                    load(required + "\nautomation.auth.jwksUrl = \"https://id.example/keys.json\"")
                }
            e.message shouldContain "auth.tokenUrl"
        }

        "the author map: author=principal pairs, comma-separated; a malformed pair fails at load" {
            load(
                required +
                    "\nautomation.auth.authorMap = \"ops@hartland.example=svc-hartland-automation, Jana Nová = jana\"",
            ).auth.authorMap shouldBe
                mapOf("ops@hartland.example" to "svc-hartland-automation", "Jana Nová" to "jana")
            val e =
                shouldThrow<IllegalArgumentException> {
                    load(required + "\nautomation.auth.authorMap = \"a=b, lonely\"")
                }
            e.message shouldContain "'lonely'"
        }

        "connections: HOCON first, the TTR_CONN_<NAME> env over it; other env ignored" {
            val c =
                load(
                    required + "\nautomation.connections { N8N = \"from-hocon\", MAIL = \"smtp\" }",
                    env = mapOf("TTR_CONN_N8N" to "from-env", "TTR_CONN_" to "nameless", "HOME" to "/root"),
                )
            c.connections shouldBe mapOf("N8N" to "from-env", "MAIL" to "smtp")
        }

        "toString never prints a credential or the bundle token" {
            val c =
                load(
                    required + "\nautomation.bundle.git.token = \"ghp_secret\"",
                    env = mapOf("TTR_CONN_N8N" to "s3cr3t-value"),
                )
            val printed = c.toString()
            printed shouldNotContain "ghp_secret"
            printed shouldNotContain "s3cr3t-value"
            printed shouldContain "N8N"
        }

        "runner concurrency is configurable and invalid bounds fail at startup" {
            load(required + "\nautomation.runner.workers = 2").runner shouldBe RunnerConfig(2)
            for (workers in listOf(-1, 0, 257)) {
                shouldThrow<IllegalArgumentException> {
                    load(required + "\nautomation.runner.workers = $workers")
                }.message shouldContain "runner.workers"
            }
        }

        "remote staging loads explicit namespace settings and hides credential-bearing URLs" {
            val c =
                load(
                    required + """
                automation.staging {
                    kind = redis
                    url = "redis://au:private-password@redis:6379/2"
                    prefix = "hartland/au"
                }
            """,
                )
            c.staging.prefix shouldBe "hartland/au"
            c.staging.url shouldContain "private-password"
            c.toString() shouldNotContain "private-password"
            c.staging.toString() shouldNotContain "private-password"
            val s3 =
                load(
                    required + """
                automation.staging {
                    kind = seaweed
                    url = "http://seaweed:8333"
                    bucket = "au-staging"
                    anonymous = true
                }
            """,
                )
            s3.staging.bucket shouldBe "au-staging"
            s3.staging.anonymous shouldBe true
        }

        "invalid staging kinds and unsafe namespaces fail before storage IO" {
            for (kind in listOf("s3", "Redis", "")) {
                shouldThrow<IllegalArgumentException> { StagingConfig(kind, "/tmp", null) }
            }
            for (prefix in listOf("", "/au", "au/", "../au", "au/../x", "au/*", "au/[x]")) {
                shouldThrow<IllegalArgumentException> { StagingConfig("local", "/tmp", null, prefix = prefix) }
            }
            shouldThrow<IllegalArgumentException> { StagingConfig("local", null, null) }
        }

        "remote staging rejects malformed and ambiguous URLs without exposing a secret" {
            for (url in listOf(
                "redis://u:secret@/0",
                "http://u:secret@redis/0",
                "redis://u:secret@redis/x",
                "redis://redis/0?q=secret",
            )) {
                shouldThrow<IllegalArgumentException> { StagingConfig("redis", null, url) }.message shouldNotContain
                    "secret"
            }
            for (url in listOf(
                "http://u:secret@seaweed",
                "http://seaweed/bucket",
                "file:///tmp",
                "http://seaweed#secret",
            )) {
                shouldThrow<IllegalArgumentException> { StagingConfig("seaweed", null, url, bucket = "au-staging") }
                    .message shouldNotContain "secret"
            }
            shouldThrow<IllegalArgumentException> { StagingConfig("seaweed", null, "http://seaweed") }
        }

        "clock.skew is read when set (test only)" {
            load(required + "\nautomation.clock.skew = 90s").clockSkew shouldBe Duration.ofSeconds(90)
        }

        "door aliases are explicit world-storage pairs and malformed or repeated routes fail at load" {
            load(required + "\nautomation.door.routes = \"demo/erp_db=us-erp, demo/automation_db=us-ledger\"")
                .door.routes shouldBe mapOf("demo/erp_db" to "us-erp", "demo/automation_db" to "us-ledger")
            for (raw in listOf("erp_db=us", "demo/erp_db=", "demo/erp_db=http://db", "a/b=x,a/b=y", "a/b=x=y")) {
                shouldThrow<IllegalArgumentException> {
                    load(required + "\nautomation.door.routes = \"$raw\"")
                }.message shouldContain "door.routes"
            }
        }

        "approvals are required by default; off needs an explicit development gate and typos refuse" {
            load(required).approvalsOff shouldBe false
            shouldThrow<IllegalArgumentException> { load(required + "\nautomation.approvals = off") }
                .message shouldContain "development"
            load(required + "\nautomation.approvals = off\nautomation.development = true").approvalsOff shouldBe true
            shouldThrow<IllegalArgumentException> { load(required + "\nautomation.approvals = of") }
        }
    })
