// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.ModelStorage
import org.tatrman.ttr.metadata.source.ProfileCatalog
import org.tatrman.ttr.metadata.source.SourceSnapshot
import org.tatrman.ttr.metadata.source.StorageFile
import org.tatrman.ttr.metadata.source.YamlFileSource
import java.nio.file.Files
import java.nio.file.Path

/** The tree declaration at a `yaml` source's root (MW-P4 contracts §1). */
internal const val TREE_DECLARATION = "ttr.yaml"

/** The bundled identity profile a `yaml` tree without a declaration is read through. */
internal const val CANONICAL_PROFILE = "canonical/1"

/**
 * MW S4.2 — the `kind: yaml` source (MW-P4 contracts §1, ✅D-MW-60): the library's [YamlFileSource] over the same
 * storage a `ttr` source reads. Each load reads it through a fresh [TreeView] because a git tree is only on disk once
 * the load has fetched it:
 *  - the profile catalog (every file under `<root>/profiles/`, what resolves `profile: <name>/<major>`) is read from the root the
 *    listing names, on first use, so a git source resolves its own profiles;
 *  - [profile] (`profile` / `METADATA_PROFILE`, relative to the source root) applies only to a tree without
 *    `ttr.yaml`. A tree that declares one while the source names another fails the load with [PROFILE_CONFLICT]:
 *    there is no silent precedence. A filesystem source is checked at startup too ([checkProfileConflict]).
 *
 * [profileName] is what `/status` and `GetStatus` report as the source's profile after the last load: the
 * configured one, else the tree's declaration, else [CANONICAL_PROFILE].
 */
internal class YamlKindSource(
    private val sourceId: String,
    private val priority: Int,
    private val storage: ModelStorage,
    val profile: String?,
    private val layouts: Boolean = true,
    private val maxInputCodePoints: Long = Long.MAX_VALUE,
) : ModelSource {
    @Volatile
    var profileName: String = profile ?: ""
        private set

    override fun load(): SourceSnapshot {
        val view = TreeView(storage, sourceId, profile, maxInputCodePoints)
        return YamlFileSource(sourceId, priority, view, profile, view.catalog, layouts).load().also {
            profileName = view.identity(view.declared ?: profile) ?: CANONICAL_PROFILE
        }
    }

    /** One load's reading of [storage]: the listing names the root, the catalog and the declaration follow it. */
    private class TreeView(
        private val storage: ModelStorage,
        private val sourceId: String,
        private val profile: String?,
        private val maxInputCodePoints: Long,
    ) : ModelStorage by storage {
        private var budgetChecked = false

        @Volatile
        private var root: Path? = null

        /** The tree declaration's profile reference, when the tree has a readable one. */
        var declared: String? = null
            private set

        val catalog: Map<String, Path> =
            object : AbstractMap<String, Path>() {
                private val read by lazy { root?.let { ProfileCatalog.read(it).entries }.orEmpty() }
                override val entries: Set<Map.Entry<String, Path>> get() = read.entries
            }

        /**
         * [reference] as `<name>/<major>`: a path (relative to the root) to a catalog profile is that profile's
         * identity; an identity, or a path the catalog does not hold, is reported as written.
         */
        fun identity(reference: String?): String? {
            if (reference == null) return null
            val base = root ?: return reference
            val target = runCatching { base.resolve(reference).normalize() }.getOrNull() ?: return reference
            return catalog.entries.firstOrNull { base.resolve(it.value).normalize() == target }?.key ?: reference
        }

        override fun listFiles(
            extensions: List<String>,
            prefixes: List<String>,
        ): List<StorageFile> =
            storage
                .listFiles(extensions, prefixes)
                .also { listed ->
                    root = root ?: listed.firstNotNullOfOrNull { it.rootPath }
                    val declaration = listed.firstOrNull { it.underRoot() == TREE_DECLARATION } ?: return@also
                    if (profile != null) throw IllegalStateException(conflict(sourceId, profile))
                    declared = TreeProfiles.declaration(storage, declaration)
                }.also { listed -> checkInputBudget(listed) }

        /**
         * ✅D-MW-96 (2): a file read through a dialect profile runs the engine over it, at ≈ 500 B of heap per input
         * code point (S4.2a's measurements), so the service refuses a tree whose dialect inputs total more than
         * [maxInputCodePoints] before the library reads them. The dialect inputs are the loader's ([TreeProfiles],
         * review-380): a file's own header counts as the tree's declaration does; canonical files, ignored files and
         * files outside the content walk run no engine and are not counted.
         */
        private fun checkInputBudget(listed: List<StorageFile>) {
            if (budgetChecked || maxInputCodePoints == Long.MAX_VALUE) return
            if (listed.none { it.underRoot().endsWith(".yaml") || it.underRoot().endsWith(".yml") }) return
            budgetChecked = true
            var total = 0L
            for (file in TreeProfiles.read(listed, storage, profile).dialect) {
                total += file.text.codePointCount(0, file.text.length)
                if (total > maxInputCodePoints) throw IllegalStateException(overBudget(sourceId, maxInputCodePoints))
            }
        }
    }

    companion object {
        const val PROFILE_CONFLICT = "profile_conflict"
        const val INPUT_BUDGET = "yaml_input_budget"

        /** Heap the profile engine holds per dialect input code point (S4.2a: ≈ 450–500 B, measured). */
        const val ENGINE_BYTES_PER_CODE_POINT = 500L

        /**
         * `metadata.yaml.maxInputCodePoints`: [configured] when positive, else half of [maxHeapBytes] at
         * [ENGINE_BYTES_PER_CODE_POINT] — 536 870 on a 512 MiB heap, room for a dfp-sized tree (~300 k) beside the
         * TTR sources the same JVM serves.
         */
        fun inputBudget(
            configured: Long,
            maxHeapBytes: Long = Runtime.getRuntime().maxMemory(),
        ): Long = if (configured > 0) configured else maxHeapBytes / 2 / ENGINE_BYTES_PER_CODE_POINT

        fun overBudget(
            sourceId: String,
            limit: Long,
        ): String =
            "$INPUT_BUDGET: source '$sourceId' reads more than $limit code points of YAML through a profile; the " +
                "profile engine holds ~$ENGINE_BYTES_PER_CODE_POINT B of heap per code point. Raise " +
                "metadata.yaml.maxInputCodePoints (METADATA_YAML_MAX_INPUT_CODE_POINTS) with the heap, or split the tree"

        fun conflict(
            sourceId: String,
            profile: String,
        ): String =
            "$PROFILE_CONFLICT: source '$sourceId' configures profile '$profile' but its tree declares " +
                "$TREE_DECLARATION; remove one (a profile applies only to a tree without $TREE_DECLARATION)"

        /** The startup check for a source whose tree is already on disk (a filesystem source). */
        fun checkProfileConflict(
            sourceId: String,
            root: Path,
            profile: String?,
        ) {
            if (profile != null && Files.isRegularFile(root.resolve(TREE_DECLARATION))) {
                throw IllegalArgumentException(conflict(sourceId, profile))
            }
        }
    }
}

internal fun StorageFile.underRoot(): String {
    val root = rootPath ?: return path.replace('\\', '/')
    return runCatching { root.relativize(Path.of(path)).toString().replace('\\', '/') }.getOrDefault(path)
}
