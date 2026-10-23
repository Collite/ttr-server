// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.concept

import org.slf4j.LoggerFactory
import org.tatrman.ttr.lexicon.sha256
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.snapshot.SnapshotReadResult
import org.tatrman.ttr.snapshot.SnapshotReader
import org.tatrman.ttrp.resolve.MdRepo
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo

/**
 * One loaded model tree: the reconciled ttr-metadata [model], its md tier ([md] — the `Model` carries none, so it is
 * loaded from the same files), and the tree's [contentId].
 *
 * [contentId] is the rule the lexicon build stamps into every archive it writes (`modelSnapshotHash`, ttr-lexicon-cli's
 * `ModelContentId`): `sha256:` over every `.ttr` / `.ttrm` under the root in sorted path order, each contributing its
 * relative path and its bytes, NUL-separated. The doors compare the two to know they serve the model the resolver's
 * vocabulary was compiled from (`ConceptModels`); `ConceptModelSourceTest` holds this copy to the producer's.
 */
class ModelTree(
    val model: Model,
    val md: MdRepo.Loaded?,
    val contentId: String,
    val loadErrors: List<String>,
)

/**
 * CN-P5 S5.1 (cn contracts §4; architecture §2: the doors are pure functions of resolved, cached inputs) — where the
 * cn doors read their model from: ONE path, either
 *
 *  - a **model source tree**: a directory of `.ttrm` files (an estate's `model/`), loaded the way Veles and `ttr cn`
 *    load one source — every file under the root, reconciled over the built-in stock; or
 *  - a **snapshot archive** (`*.tar.zst`, ttr-snapshot's format), whose `docs/` IS that tree, file for file. It is
 *    unpacked into a private directory once per archive, so both forms load through the same code and hash the same.
 *
 * The tree is reloaded when the path's fingerprint moves (a file's size or mtime, or the archive behind a ConfigMap
 * symlink swap) — never on a timer, and never on a request that finds the same files. A tree that fails to load keeps
 * the one already loaded and says so once; with none loaded, [current] is null and the doors answer
 * `CN_MODEL_UNAVAILABLE`.
 */
class ConceptModelSource(
    private val path: Path,
    private val workDir: Path = Path.of(System.getProperty("java.io.tmpdir")),
) {
    private val logger = LoggerFactory.getLogger(ConceptModelSource::class.java)

    @Volatile
    private var cached: Pair<String, ModelTree>? = null

    private val reported =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    /** The tree at [path] — reloaded when its fingerprint moved; null when none could ever be loaded. */
    @Synchronized
    fun current(): ModelTree? {
        val print = runCatching { fingerprint() }.getOrElse { e -> return keep("unreadable: ${e.message}") }
        cached?.let { if (it.first == print) return it.second }
        val tree = runCatching { load() }.getOrElse { e -> return keep("failed to load: ${e.message}") }
        cached = print to tree
        logger.info(
            "cn doors: model {} loaded from {} ({} concept(s), md tier {}, {} load error(s))",
            tree.contentId,
            path,
            cnSize(tree.model),
            if (tree.md == null) "absent" else "present",
            tree.loadErrors.size,
        )
        tree.loadErrors.take(MAX_LOGGED_ERRORS).forEach { logger.warn("cn doors: model load error — {}", it) }
        return tree
    }

    private fun keep(why: String): ModelTree? {
        if (reported.add(why)) {
            if (cached == null) {
                logger.warn("cn doors: model at {} {} — the cn doors answer CN_MODEL_UNAVAILABLE", path, why)
            } else {
                logger.warn("cn doors: model at {} {} — keeping the loaded model", path, why)
            }
        }
        return cached?.second
    }

    private fun cnSize(model: Model): Int = (model.schemas["cn"] as? CnSchema)?.concepts?.size ?: 0

    /** What tells a changed tree from the same one, cheaply: the real path, and every model file's size and mtime. */
    private fun fingerprint(): String {
        val real = path.toRealPath()
        if (!real.isDirectory()) {
            return "archive:$real:${Files.size(
                real,
            )}:${Files.getLastModifiedTime(real).toMillis()}"
        }
        return "tree:$real:" +
            modelFiles(real).joinToString(";") {
                "${it.relativeTo(real)}=${Files.size(it)}@${Files.getLastModifiedTime(it).toMillis()}"
            }
    }

    private fun load(): ModelTree {
        val real = path.toRealPath()
        val root = if (real.isDirectory()) real else unpack(real)
        val snapshot = FileBasedSource("cn", PRIORITY, LocalFsStorage(id = "cn", rootPath = root)).load()
        val reconciled =
            ModelReconciler(ModelDescriptor(id = "cn-doors", name = root.fileName?.toString() ?: "model"))
                .reconcile(listOf(BuiltinStockSource().load(), snapshot))
        val errors = (snapshot.errors + reconciled.errors).distinct().map { "${it.file}:${it.line}: ${it.message}" }
        return ModelTree(reconciled.model, MdRepo.loadFrom(root), contentId(root), errors)
    }

    /**
     * The archive's `docs/` written out under [workDir], one directory per archive content id — so a reload of the
     * same archive reuses it and a new archive never reads a half-written tree. A path that would leave the
     * directory (`..`, absolute) refuses the whole archive.
     */
    private fun unpack(archive: Path): Path {
        val bytes = archive.readBytes()
        val contents =
            when (val r = SnapshotReader.read(bytes)) {
                is SnapshotReadResult.Ok -> r.contents
                is SnapshotReadResult.Failure -> error("not a snapshot archive (${r.reason})")
            }
        val dir = workDir.resolve("cn-doors-" + sha256(bytes).removePrefix("sha256:").take(DIR_HASH_CHARS))
        if (Files.isDirectory(dir)) return dir
        val staging = Files.createTempDirectory(workDir, "cn-doors-staging-")
        for ((rel, text) in contents.docs) {
            val target = staging.resolve(rel).normalize()
            require(target.startsWith(staging) && !Path.of(rel).isAbsolute) { "archive entry escapes docs/: $rel" }
            Files.createDirectories(target.parent)
            Files.writeString(target, text)
        }
        return runCatching {
            Files.move(
                staging,
                dir,
            )
        }.map { dir }.getOrElse { if (Files.isDirectory(dir)) dir else throw it }
    }

    companion object {
        /** The two model extensions `FileBasedSource` walks — and the lexicon build's content id hashes. */
        val EXTENSIONS: List<String> = listOf(".ttr", ".ttrm")

        private const val PRIORITY = 100
        private const val MAX_LOGGED_ERRORS = 20
        private const val DIR_HASH_CHARS = 16

        private fun modelFiles(root: Path): List<Path> =
            Files.walk(root).use { stream ->
                stream
                    .filter { !it.isDirectory() && EXTENSIONS.any { ext -> it.toString().endsWith(ext) } }
                    .sorted()
                    .toList()
            }

        /** The lexicon build's `modelSnapshotHash` rule, over the tree at [root] (see [ModelTree.contentId]). */
        fun contentId(root: Path): String {
            val buffer = ByteArrayOutputStream()
            for (file in modelFiles(root)) {
                buffer.write(file.relativeTo(root).toString().toByteArray(Charsets.UTF_8))
                buffer.write(0)
                buffer.write(file.readBytes())
                buffer.write(0)
            }
            return sha256(buffer.toByteArray())
        }
    }
}
