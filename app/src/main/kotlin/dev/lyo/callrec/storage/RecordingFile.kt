// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.storage

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * @property name display name shown to the user (no extension)
 * @property tag  source tag: "uplink" / "downlink" / "voicecall_mono" / "mic"
 * @property path resolved absolute file path, or (when the user has picked a
 *   SAF recording folder) a `content://` document URI — see [RecordingStorage.create].
 */
data class RecordingFile(
    val name: String,
    val tag: String,
    val path: String,
    private val appCtx: Context,
) {
    val isSaf: Boolean get() = RecordingPaths.isSaf(path)

    fun toFile(): File = File(path)

    /**
     * Write-mode FD for the encoder layer (`AacEncoder`'s `MediaMuxer`,
     * `WavEncoder`'s `FileChannel`) — one code path serves both legacy and
     * SAF destinations, since both are just a [ParcelFileDescriptor] once
     * opened. Caller owns the returned descriptor and must close it.
     */
    fun openWriteFd(): ParcelFileDescriptor =
        if (isSaf) {
            requireNotNull(appCtx.contentResolver.openFileDescriptor(android.net.Uri.parse(path), "rw")) {
                "openFileDescriptor returned null for $path"
            }
        } else {
            val file = File(path)
            file.parentFile?.mkdirs()
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE,
            )
        }
}
