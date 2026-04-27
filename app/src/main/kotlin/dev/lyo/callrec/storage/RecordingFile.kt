// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.storage

import java.io.File

/**
 * Abstract over the eventual SAF-backed sink. For MVP we live entirely in
 * `getExternalFilesDir(null)` which is private to our app and survives
 * uninstalls only if the user explicitly opts in. v1.0 will swap this out
 * for [androidx.documentfile.provider.DocumentFile] without changing the
 * encoder API.
 *
 * @property name display name shown to the user (no extension)
 * @property tag  source tag: "uplink" / "downlink" / "voicecall_mono" / "mic"
 * @property path resolved absolute file path
 */
data class RecordingFile(
    val name: String,
    val tag: String,
    val path: String,
) {
    fun openOrCreate(): File {
        val f = File(path)
        f.parentFile?.mkdirs()
        if (!f.exists()) f.createNewFile()
        return f
    }

    fun toFile(): File = File(path)
}
