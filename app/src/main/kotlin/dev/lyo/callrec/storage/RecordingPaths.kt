// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.storage

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import dev.lyo.callrec.core.L
import java.io.File
import java.io.InputStream

/**
 * Every place that reads/deletes a recording works off the raw path string
 * persisted in [CallRecord.uplinkPath]/[CallRecord.downlinkPath] — not a
 * [RecordingFile] instance. This centralises the "is it a legacy absolute
 * path or a SAF `content://` document URI" branch so the ~8 call sites
 * (playback, waveform, sharing, transcription, cleanup, bulk-delete,
 * startup reconciliation) don't each reimplement it.
 */
object RecordingPaths {

    fun isSaf(path: String): Boolean = path.startsWith("content://")

    fun exists(ctx: Context, path: String): Boolean =
        if (isSaf(path)) {
            runCatching { DocumentFile.fromSingleUri(ctx, Uri.parse(path))?.exists() ?: false }
                .getOrDefault(false)
        } else {
            File(path).exists()
        }

    fun length(ctx: Context, path: String): Long =
        if (isSaf(path)) {
            runCatching { DocumentFile.fromSingleUri(ctx, Uri.parse(path))?.length() ?: 0L }
                .getOrDefault(0L)
        } else {
            runCatching { File(path).length() }.getOrDefault(0L)
        }

    fun delete(ctx: Context, path: String): Boolean =
        if (isSaf(path)) {
            runCatching { DocumentFile.fromSingleUri(ctx, Uri.parse(path))?.delete() ?: false }
                .getOrDefault(false)
        } else {
            runCatching { File(path).delete() }.getOrDefault(false)
        }

    /** For [android.media.MediaPlayer.setDataSource] (Context, Uri) — content:// as-is, file:// for legacy. */
    fun playableUri(path: String): Uri =
        if (isSaf(path)) Uri.parse(path) else Uri.fromFile(File(path))

    /**
     * Uri suitable for an ACTION_SEND share. SAF `content://` documents are
     * already shareable as-is — the persisted permission grant we hold lets
     * us forward FLAG_GRANT_READ_URI_PERMISSION to the receiving app. Legacy
     * absolute paths need FileProvider since a raw `file://` Uri isn't
     * grantable to another app.
     */
    fun shareUri(ctx: Context, path: String): Uri? =
        if (isSaf(path)) {
            Uri.parse(path)
        } else {
            runCatching { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(path)) }
                .getOrNull()
        }

    fun openInputStream(ctx: Context, path: String): InputStream? =
        if (isSaf(path)) {
            runCatching { ctx.contentResolver.openInputStream(Uri.parse(path)) }.getOrNull()
        } else {
            runCatching { File(path).inputStream() }.getOrNull()
        }

    /** Display name (with extension) — the SAF document's name, or the legacy file's name. */
    fun displayName(ctx: Context, path: String): String? =
        if (isSaf(path)) {
            runCatching { DocumentFile.fromSingleUri(ctx, Uri.parse(path))?.name }.getOrNull()
        } else {
            File(path).name
        }

    /**
     * No-op passthrough for legacy paths — returns the [File] directly, zero
     * copy. For SAF paths, copies the document into `cacheDir/decode/` once
     * and reuses that cached copy on subsequent calls (a recording's audio
     * content never changes after it's finalised, so there's no staleness to
     * guard against). Named with the resolved display-name extension so
     * extension-dispatching readers (`PcmDecoder`, `AudioMixer`) keep working
     * unmodified against the materialized copy. Returns null if the source
     * can't be opened.
     */
    fun materializeToCache(ctx: Context, path: String): File? {
        if (!isSaf(path)) return File(path)
        val uri = Uri.parse(path)
        val name = displayName(ctx, path) ?: uri.lastPathSegment?.substringAfterLast('/') ?: return null
        val dir = ctx.cacheDir.resolve("decode").apply { mkdirs() }
        val out = File(dir, "${uri.toString().hashCode()}-$name")
        if (out.exists() && out.length() > 0L) return out
        return runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            out.takeIf { it.exists() && it.length() > 0L }
        }.onFailure { L.w(TAG, "materializeToCache failed for $path", it) }.getOrNull()
    }

    private const val TAG = "RecordingPaths"
}
