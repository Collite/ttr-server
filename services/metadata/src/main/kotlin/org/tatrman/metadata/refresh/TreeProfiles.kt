// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import org.tatrman.ttr.metadata.source.ModelStorage
import org.tatrman.ttr.metadata.source.StorageFile
import org.tatrman.ttr.metadata.source.YamlFileSource
import org.tatrman.ttr.profile.DeclarationReader
import org.tatrman.ttr.profile.FileHeader
import org.tatrman.ttr.profile.FileSelector
import org.tatrman.ttr.profile.ProfileException

/**
 * MW S4.2 review-380 — which profile reads each content file of a `yaml` tree, decided as ttr-metadata's loader decides
 * it (`YamlTreeReader.plan`, internal there; ttr-core contracts §4 C-4), so the input cap (✅D-MW-96), the promotion
 * guard (contracts §1, ✅MW-52) and the reported profile never disagree with what the load runs:
 *  - content is every `.yaml`/`.yml` under the root except dot-segments, the root's `agents/`, the control files
 *    (`ttr.yaml`, `renames.yaml`) and what `model.ignore` matches; an unreadable `model.ignore` loads no content;
 *  - a file's own `ttr: {profile, grammar}` header wins; a scalar `ttr` (the canonical version header) is canonical;
 *    without a header the [tree] fallback applies: the configured profile, else `ttr.yaml`'s declaration (block or
 *    flow, read by the library's [DeclarationReader]);
 *  - the bundled identity (`canonical`, `canonical/1`) runs no engine. A file whose header or fallback does not read is
 *    dropped by the load, so it runs none either.
 *
 * One difference, on the safe side: a catalog profile that declares no operations is still counted as a dialect here
 * (the loader reads it as canonical once resolved; resolving needs the profile files, which this does not read).
 */
internal class TreeProfiles private constructor(
    /** The tree's fallback profile reference; null when it has none, or its `ttr.yaml` does not read. */
    val tree: String?,
    /** The content files a dialect profile reads, in path order. */
    val dialect: List<DialectFile>,
) {
    class DialectFile(
        val file: StorageFile,
        val text: String,
        val profile: String,
    )

    /** The dialect profile a promotion would write under: the tree's (a new file has no header), else a file's. */
    fun promotionDialect(): Pair<String, String?>? =
        tree?.takeUnless { YamlFileSource.isBundledProfile(it) }?.let { it to null }
            ?: dialect.firstOrNull()?.let { it.profile to it.file.underRoot() }

    companion object {
        /** What [read] needs listed: the content files and `model.ignore`. */
        val EXTENSIONS = listOf("yaml", "yml", "ignore")

        private const val IGNORE = "model.ignore"
        private val CONTROL_FILES = setOf(TREE_DECLARATION, "renames.yaml")

        /** `ttr.yaml`'s profile reference; null when the file does not read as a declaration. */
        fun declaration(
            storage: ModelStorage,
            file: StorageFile,
        ): String? = runCatching { DeclarationReader.readTree(storage.read(file), file.path).profile }.getOrNull()

        /** [override] is the source's configured profile (a tree with it has no `ttr.yaml`: [YamlKindSource]). */
        fun read(
            listed: List<StorageFile>,
            storage: ModelStorage,
            override: String?,
        ): TreeProfiles {
            val byPath = listed.associateBy { it.underRoot() }
            val tree = override ?: byPath[TREE_DECLARATION]?.let { declaration(storage, it) }
            val ignore =
                byPath[IGNORE]?.let { file ->
                    runCatching { FileSelector(storage.read(file).lines()) }.getOrNull()
                        ?: return TreeProfiles(tree, emptyList())
                }
            val dialect =
                listed
                    .filter { file ->
                        val path = file.underRoot()
                        path.substringAfterLast('.') in setOf("yaml", "yml") &&
                            content(path.split('/')) &&
                            path !in CONTROL_FILES &&
                            ignore?.matches(path) != true
                    }.sortedBy { it.path }
                    .mapNotNull { file ->
                        val text = storage.read(file)
                        profileOf(text, file.path, tree)?.let { DialectFile(file, text, it) }
                    }
            return TreeProfiles(tree, dialect)
        }

        /** ttr-metadata's `TreeContent.includes` (internal there): no dot-segment, not under the root's `agents/`. */
        private fun content(segments: List<String>) =
            segments.isNotEmpty() && segments.none { it.startsWith(".") } && segments.first() != "agents"

        private fun profileOf(
            text: String,
            label: String,
            tree: String?,
        ): String? {
            val header =
                try {
                    DeclarationReader.header(text, label)
                } catch (e: ProfileException) {
                    return null
                }
            val profile =
                when (header) {
                    is FileHeader.Declared -> header.declaration.profile
                    is FileHeader.Canonical -> null
                    FileHeader.Absent -> tree
                }
            return profile?.takeUnless { YamlFileSource.isBundledProfile(it) }
        }
    }
}
