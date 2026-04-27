// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.ui.playback

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.lyo.callrec.codec.AudioMixer
import dev.lyo.callrec.core.L
import dev.lyo.callrec.storage.CallRecord
import java.io.File
import java.util.Locale

/**
 * Centralised share helpers for the playback screen. Three flavours:
 *
 *  - [shareSingle]:  one-track recording → one ACTION_SEND intent.
 *  - [shareSeparate]: dual-track → ACTION_SEND_MULTIPLE with both files.
 *  - [shareStereoMix]: dual-track → mix to one stereo .wav, then SEND.
 *
 * Stereo mixes are cached under `cacheDir/export/<callId>-stereo.wav` (also
 * exposed by the FileProvider's `cache-path/export` mapping). The cache is
 * invalidated by mtime: if either source file is newer than the cached mix,
 * we regenerate. This handles the rare "user re-recorded after a calibration
 * fix and reused the same callId" case.
 */
internal object Sharing {

    fun shareSingle(ctx: Context, rec: CallRecord) {
        val authority = "${ctx.packageName}.fileprovider"
        val file = File(rec.uplinkPath)
        val uri = runCatching { FileProvider.getUriForFile(ctx, authority, file) }.getOrNull()
            ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeFor(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { ctx.startActivity(Intent.createChooser(intent, null)) }
    }

    fun shareSeparate(ctx: Context, rec: CallRecord) {
        val authority = "${ctx.packageName}.fileprovider"
        val files = buildList {
            add(File(rec.uplinkPath))
            rec.downlinkPath?.let { add(File(it)) }
        }
        val uris = files.mapNotNull {
            runCatching { FileProvider.getUriForFile(ctx, authority, it) }.getOrNull()
        }
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeFor(files.first())
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "audio/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(intent, null)) }
    }

    /**
     * Build (or reuse cached) stereo WAV for [rec], then fire ACTION_SEND.
     * Returns true if the share was launched, false if mixing failed.
     *
     * Heavy: must be called from a worker dispatcher.
     */
    fun shareStereoMix(ctx: Context, rec: CallRecord): Boolean {
        val mix = buildOrReuseStereoMix(ctx, rec) ?: return false
        val authority = "${ctx.packageName}.fileprovider"
        val uri = runCatching { FileProvider.getUriForFile(ctx, authority, mix) }.getOrNull()
            ?: return false
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { ctx.startActivity(Intent.createChooser(intent, null)) }
        return true
    }

    /** Returns cached mix if valid, otherwise rebuilds. Null on decode failure. */
    private fun buildOrReuseStereoMix(ctx: Context, rec: CallRecord): File? {
        val downlink = rec.downlinkPath?.let { File(it) } ?: return null
        val uplink = File(rec.uplinkPath)
        val out = File(ctx.cacheDir, "export/${rec.callId}-stereo.wav")
        if (out.exists() && out.lastModified() >= maxOf(uplink.lastModified(), downlink.lastModified())) {
            L.i(TAG, "stereo cache hit → ${out.path}")
            return out
        }
        out.parentFile?.mkdirs()
        return AudioMixer.mixToStereoWav(uplink, downlink, out)
    }

    private fun mimeFor(f: File): String = when (f.extension.lowercase(Locale.US)) {
        "wav" -> "audio/wav"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        else -> "audio/*"
    }

    private const val TAG = "Sharing"
}
