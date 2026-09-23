// SPDX-License-Identifier: Apache-2.0
import com.google.protobuf.gradle.*
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.ktlint)
}

val protobufVersion =
    libs.versions.protobuf
        .asProvider()
        .get()

dependencies {
    api(libs.protobuf.java)
    api(libs.protobuf.kotlin)
    api(libs.grpc.stub)
    api(libs.grpc.protobuf)
    api(libs.grpc.kotlin.stub)
    // Generated grpc-kotlin stubs reference kotlinx.coroutines.flow.Flow. This
    // was previously leaking onto the compile classpath transitively via the
    // ai-platform-proto `api` dep; that dep was removed in fork Stage 2.6, so
    // depend on coroutines directly.
    api(libs.kotlinx.coroutines.core)
    // ttr-translator arc B1: the canonical plan.v1/transdsl.v1/dfdsl.v1 wire formats
    // are owned by tatrman and consumed as an artifact. The protobuf-gradle-plugin
    // extracts the bundled `.proto` files onto the protoc include path (without
    // recompiling them here), so `import "org/tatrman/plan/v1/plan.proto"` in the
    // downstream service protos keeps resolving. FQCNs are identical (org.tatrman.*).
    api(libs.tatrman.ttr.plan.proto)
    // All other protos are in-repo. The ai-platform Maven dep (com.tatrman:shared-proto,
    // Themis's residual nlp.v1) was removed in fork Stage 2.6 — Themis now imports
    // org.tatrman.nlp.v1 + common.v1, both generated here.

    testImplementation(libs.bundles.kotest)
    // JsonFormat (proto3 JSON ↔ message) for the envelope/v1 golden round-trip
    // spec (Iris Stage 1.1) — the JVM serialization path iris-bff relies on.
    testImplementation(libs.protobuf.java.util)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        // Phase 2.1 — gRPC Java + Kotlin codegen so service modules (veles, dispatcher,
        // worker, etc.) get `*ServiceGrpcKt` base classes from the in-repo protos. Aligned
        // to ai-platform's protoc-gen-grpc-java 1.78.0 / protoc-gen-grpc-kotlin 1.5.0.
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.78.0"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.5.0:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            // RV-P2.1.T7 — the wire-compatibility guard's input. A descriptor set is the
            // compiled truth about every message, field number, type and enum value in the
            // repo; `ProtoCompatSpec` diffs it against the checked-in baseline and fails on
            // anything that is not purely additive (J-v2). Imports are included so a baseline
            // resolves its dependencies without a second artifact.
            task.generateDescriptorSet = true
            task.descriptorSetOptions.includeImports = true
            task.descriptorSetOptions.path =
                layout.buildDirectory
                    .file("descriptors/${task.sourceSet.name}.desc")
                    .get()
                    .asFile.path
            task.builtins {
                id("kotlin") { }
                // Phase 1.2 T4 — Python codegen for the shared-proto tree so the
                // Python lane (Nlp, Polars, Metis) can `import` generated
                // `org.tatrman.*` types. See `AGENTS.md` §4.1 ("Imports come
                // from the generated shared-proto package only").
                id("python") { }
            }
            task.plugins {
                id("grpc")
                id("grpckt")
            }
        }
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // The compat guard reads the descriptor set protoc just produced (RV-P2.1.T7).
    dependsOn("generateProto")
    systemProperty(
        "tatrman.proto.descriptorSet",
        layout.buildDirectory
            .file("descriptors/main.desc")
            .get()
            .asFile.path,
    )
    systemProperty("tatrman.proto.baseline", file("compat/wire-baseline.desc").path)
    // A FROZEN copy of the resolver contract as it was before the RV-P2.1 lattice, for the
    // old-client proof. Deliberately a second file: `wire-baseline.desc` moves whenever a wire
    // change is deliberately accepted, and a proof about a specific historical shape must not
    // quietly become a proof about the current one.
    systemProperty("tatrman.proto.preLattice", file("compat/resolver-v1-pre-lattice.desc").path)
    // LP-P0 — a FROZEN copy of the protos as they were before `fuzzy.match:v2` (the wire baseline at
    // 2d90ab2), for the fuzzy old-client proof. Same reason as the pre-lattice file.
    systemProperty("tatrman.proto.preLp", file("compat/fuzzy-v1-pre-lp.desc").path)
}

// RV-P2.1.T7 — refresh the wire baseline. Deliberately NOT wired into any build: the
// baseline is the record of what has already shipped, and regenerating it is how you
// *accept* a wire change, so it must be a conscious commit (`just proto-baseline`), the
// same discipline as the helm golden templates.
val refreshProtoBaseline by tasks.registering(Copy::class) {
    group = "verification"
    description = "Overwrite the checked-in wire baseline with the CURRENT protos. Review the diff."
    dependsOn("generateProto")
    from(layout.buildDirectory.file("descriptors/main.desc"))
    into(file("compat"))
    rename { "wire-baseline.desc" }
}

ktlint {
    // Generated proto Kotlin sources are not human-authored — exclude.
    filter {
        exclude { it.file.path.contains("/build/generated/") }
    }
}

// --- Python packaging (Phase 1.2 T4) ---------------------------------------------------
//
// Mirrors the ai-platform pattern (see `ai-platform/shared/proto/build.gradle.kts`
// `PreparePythonPackage`). The output is a uv-importable package rooted at
// `build/python-package/src/`; every Python service consumes it via
// `just proto-py` (which invokes `:shared:proto:preparePythonPackage`).
//
// The wheel ships only `src/org` — the in-repo `org.tatrman.*` generated types.
// Since fork Stage 2.6 every proto is in-repo (no `com.tatrman.*` Maven jar),
// so there is no `src/cz` tree and nothing external to package.
val pythonPackageDir = project.layout.buildDirectory.dir("python-package")

abstract class PreparePythonPackage : DefaultTask() {
    @get:InputDirectory
    @get:Optional
    abstract val generatedSource: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun buildPackage() {
        val targetDir = outputDir.get().asFile
        targetDir.deleteRecursively()
        targetDir.mkdirs()

        val srcDir = targetDir.resolve("src")
        srcDir.mkdirs()

        val generatedDir = generatedSource.orNull?.asFile
        if (generatedDir?.exists() == true) {
            generatedDir.copyRecursively(srcDir, overwrite = true)
        }

        // Recursive __init__.py generation — EXCEPT the shared `org` / `org.tatrman`
        // namespace levels. Those stay PEP 420 namespace packages so the
        // org.tatrman.{plan,transdsl,dfdsl}.v1 modules from the `ttr-plan-proto`
        // wheel (ttr-translator arc B1) merge with the org.tatrman.* modules
        // generated here; a regular __init__.py at either level would make one
        // distribution shadow the other. Deeper levels are regular packages.
        val namespaceDirs = setOf(srcDir.resolve("org"), srcDir.resolve("org/tatrman"))
        srcDir.walkTopDown().filter { it.isDirectory }.forEach { dir ->
            val initFile = dir.resolve("__init__.py")
            if (dir in namespaceDirs) {
                if (initFile.exists()) initFile.delete()
            } else if (!initFile.exists()) {
                initFile.createNewFile()
            }
        }

        targetDir.resolve("pyproject.toml").writeText(
            """
            [project]
            name = "shared-proto"
            version = "0.1.0"
            dependencies = ["protobuf>=4.0.0", "ttr-plan-proto==0.8.4"]
            [build-system]
            requires = ["hatchling"]
            build-backend = "hatchling.build"
            [tool.hatch.build.targets.wheel]
            packages = ["src/org"]
            """.trimIndent(),
        )
    }
}

val preparePythonPackage by tasks.registering(PreparePythonPackage::class) {
    group = "python"
    description = "Assembles the uv-importable shared-proto Python package from the protobuf-generated tree."
    dependsOn("generateProto")
    generatedSource.set(project.layout.buildDirectory.dir("generated/sources/proto/main/python"))
    outputDir.set(pythonPackageDir)
}

tasks.assemble {
    dependsOn(preparePythonPackage)
}

// ttr-translator arc B1 T3 — duplicate-class guard. The plan.v1 / transdsl.v1 /
// dfdsl.v1 wire formats are owned by org.tatrman:ttr-plan-proto and must NOT be
// generated/bundled here (a stray re-add of the deleted .proto files would put two
// copies of the classes on every consumer's classpath). Fail loudly if the
// shared:proto jar ever contains them again.
val noTransferredProtoClasses by tasks.registering {
    group = "verification"
    description =
        "Fails if the shared:proto jar bundles plan/transdsl/dfdsl classes (owned by " +
        "org.tatrman:ttr-plan-proto) or llm/v1 classes (owned by :shared:proto-llm)."
    dependsOn(tasks.named("jar"))
    doLast {
        val jarFile =
            tasks
                .named<Jar>("jar")
                .get()
                .archiveFile
                .get()
                .asFile
        // llm/v1 is owned by :shared:proto-llm so its gRPC stub stays OFF the classpath
        // of the 21 zero-LLM services that depend on :shared:proto (RS-23). A stray
        // re-add of the deleted .proto here would silently re-leak the stub — fail loudly.
        val forbidden =
            listOf(
                "org/tatrman/plan/v1/",
                "org/tatrman/transdsl/v1/",
                "org/tatrman/dfdsl/v1/",
                "org/tatrman/llm/v1/",
            )
        val offenders =
            ZipFile(jarFile).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .map { entry -> entry.name }
                    .filter { name -> name.endsWith(".class") && forbidden.any { prefix -> name.startsWith(prefix) } }
                    .toList()
            }
        require(offenders.isEmpty()) {
            "shared:proto jar bundles transferred wire-format classes (owned by " +
                "org.tatrman:ttr-plan-proto). Re-add of a deleted .proto? Found: ${offenders.take(5)}"
        }
    }
}

tasks.named("check") {
    dependsOn(noTransferredProtoClasses)
}
