// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.staging

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tatrman.automation.ledger.RunOutput
import org.tatrman.automation.runner.ArrowTables
import org.tatrman.automation.runner.Table
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Blob storage seam: local development now; the configured Charon/object-store adapter plugs in here. */
interface StagingStore {
    fun putIfAbsent(
        path: String,
        bytes: ByteArray,
    ): Boolean

    fun get(path: String): ByteArray

    fun delete(path: String)
}

class LocalStagingStore(
    root: Path,
) : StagingStore {
    private val root = root.toAbsolutePath().normalize()

    private fun file(path: String): Path =
        root.resolve(path).normalize().also {
            require(it.startsWith(root) && it != root) { "staging path escapes its root" }
        }

    override fun putIfAbsent(
        path: String,
        bytes: ByteArray,
    ): Boolean {
        val target = file(path)
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, ".stage-", ".tmp")
        try {
            Files.write(tmp, bytes)
            try {
                Files.createLink(target, tmp) // Atomic CREATE_NEW: never replace a previously journalled output.
                return true
            } catch (e: java.nio.file.FileAlreadyExistsException) {
                check(
                    Files.readAllBytes(target).contentEquals(bytes),
                ) { "a staged output cannot change within one pinned run" }
                return false
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override fun get(path: String): ByteArray = Files.readAllBytes(file(path))

    override fun delete(path: String) {
        Files.deleteIfExists(file(path))
    }
}

data class StagedOutput(
    val output: RunOutput,
    val ref: String,
    val table: Table,
)

data class StagedData(
    val arrow: ByteArray,
    val table: Table,
)

/** Publish a completed Arrow blob before recording its ledger row; a failed read never reaches this point. */
class Staging(
    private val store: StagingStore,
    private val record: (RunOutput) -> Unit,
) {
    /** Page readers use the published Arrow, verifying its immutable journal metadata before returning rows. */
    suspend fun read(output: StagedOutput): Table = readData(output).table

    /** Verify before HTTP headers are sent; downloads retain the exact published IPC bytes. */
    suspend fun readData(output: StagedOutput): StagedData =
        withContext(Dispatchers.IO) {
            val bytes = store.get(requireNotNull(output.output.path))
            check(bytes.size.toLong() == output.output.bytes && hash(bytes) == output.output.sha256) {
                "staged output differs from its journal"
            }
            val table =
                ArrowTables.decode(listOf(bytes), output.table.columns).also {
                    check(it.rows.size.toLong() == output.output.rows) { "staged row count differs from its journal" }
                }
            StagedData(bytes, table)
        }

    suspend fun put(
        run: UUID,
        island: String,
        port: String,
        table: Table,
    ): StagedOutput =
        withContext(Dispatchers.IO) {
            require(SAFE.matches(island) && SAFE.matches(port)) { "invalid staged output name" }
            val path = "runs/$run/$island.$port.arrow"
            val bytes = ArrowTables.encode(table)
            currentCoroutineContext().ensureActive()
            store.putIfAbsent(path, bytes)
            currentCoroutineContext().ensureActive()
            val output = RunOutput(run, island, port, table.rows.size.toLong(), bytes.size.toLong(), path, hash(bytes))
            // On a journal failure leave the blob for retention. Another claimant may already reference it;
            // deleting here could destroy its valid output. No reference is returned until the ledger succeeds.
            record(output)
            StagedOutput(output, "/automation/runs/$run/outputs/$island.$port", table)
        }

    companion object {
        private val SAFE = Regex("[A-Za-z_][A-Za-z_0-9.-]*")

        private fun hash(bytes: ByteArray): String =
            "sha256:" +
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }
    }
}
