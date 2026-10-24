// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.concept

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * CN-P5 S5.1 T4 — the cn doors never write (cn contracts §2.3: "cn never executes a write"; A-CN-50). ttr-concept's own
 * `PuritySpec` holds the module to it; this holds the door to it, the way that spec does — by reading the compiled door
 * classes' constant pools: none references JDBC (`java.sql`, `javax.sql`). The other half is structural:
 * `verifyNoDbClient` fails the build if the resolver's runtime classpath resolves a database driver or an ADBC client,
 * so there is nothing for a door to write with.
 */
class CnDoorPurityTest :
    StringSpec({
        "no door class references JDBC" {
            // The MAIN classes: the test tree has a package of the same name, which a resource lookup would find first.
            val main =
                Paths.get(
                    ConceptModelSource::class.java.protectionDomain.codeSource.location
                        .toURI(),
                )
            val classes =
                listOf("org/tatrman/resolver/concept", "org/tatrman/resolver/mcp")
                    .flatMap { pkg ->
                        Files.list(main.resolve(pkg)).use { s ->
                            s.filter { it.fileName.toString().endsWith(".class") }.toList()
                        }
                    }.filter { it.fileName.toString().startsWith("Concept") || it.parent.endsWith(Path.of("concept")) }
            classes.size shouldBeGreaterThan 5
            val forbidden = listOf("java/sql/", "javax/sql/")
            classes
                .filter { c ->
                    val bytes = String(Files.readAllBytes(c), Charsets.ISO_8859_1)
                    forbidden.any { it in bytes }
                }.map { it.fileName.toString() }
                .shouldBeEmpty()
        }
    })
