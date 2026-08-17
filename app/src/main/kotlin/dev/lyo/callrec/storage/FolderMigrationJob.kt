// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.lyo.callrec.core.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * One-shot mover: copies existing legacy (app-private) recordings into a
 * newly-picked SAF folder, then repoints the DB rows at the new `content://`
 * documents and deletes the originals. Modeled on [dev.lyo.callrec.cleanup.CleanupJob] —
 * same "only finalised rows" guard, same per-file error isolation so one
 * corrupt/unreadable file doesn't abort the batch.
 */
object FolderMigrationJob {

    data class Progress(val done: Int, val total: Int, val failed: Int)

    private val _progress = MutableStateFlow<Progress?>(null)

    /** Null when no migration is in flight. Observed by Settings UI for a determinate progress indicator. */
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    suspend fun run(ctx: Context, db: RecordingsDb, treeUri: Uri): Progress = withContext(Dispatchers.IO) {
        val dao = db.calls()
        val rows = dao.selectAllFinalised().filter { row ->
            !RecordingPaths.isSaf(row.uplinkPath) ||
                row.downlinkPath?.let { !RecordingPaths.isSaf(it) } == true
        }
        val total = rows.size
        var done = 0
        var failed = 0
        _progress.value = Progress(done, total, failed)

        for (row in rows) {
            val newUp = migrateOne(ctx, treeUri, row.uplinkPath)
            if (newUp == null) failed++
            val newDn = row.downlinkPath?.let { dn ->
                migrateOne(ctx, treeUri, dn).also { if (it == null) failed++ }
            } ?: row.downlinkPath

            val finalUp = newUp ?: row.uplinkPath
            if (finalUp != row.uplinkPath || newDn != row.downlinkPath) {
                runCatching { dao.updateOutcome(row.callId, row.mode, finalUp, newDn) }
                    .onFailure { L.w(TAG, "DB update failed for ${row.callId}", it) }
            }
            done++
            _progress.value = Progress(done, total, failed)
        }

        Progress(done, total, failed).also { _progress.value = null }
    }

    /**
     * Copies [path] into [treeUri] if it's a legacy file. Returns the new
     * `content://` URI string on success, the path unchanged if it was
     * already SAF (nothing to do), or null on genuine failure (source
     * missing, tree inaccessible, copy failed) — caller keeps the original
     * path in that case, so a failed migration never loses a recording.
     */
    private fun migrateOne(ctx: Context, treeUri: Uri, path: String): String? {
        if (RecordingPaths.isSaf(path)) return path
        val src = File(path)
        if (!src.exists()) return null
        return runCatching {
            val tree = DocumentFile.fromTreeUri(ctx, treeUri) ?: return null
            val doc = tree.createFile(mimeFor(src.extension), src.name) ?: return null
            val copied = ctx.contentResolver.openOutputStream(doc.uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
                true
            } ?: false
            if (!copied) {
                runCatching { doc.delete() }
                return null
            }
            src.delete()
            doc.uri.toString()
        }.onFailure { L.w(TAG, "migrate failed for $path", it) }.getOrNull()
    }

    private fun mimeFor(ext: String): String = when (ext.lowercase(Locale.US)) {
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        else -> "application/octet-stream"
    }

    private const val TAG = "FolderMigrationJob"
}
