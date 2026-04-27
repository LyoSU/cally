// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.storage

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dev.lyo.callrec.core.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ZipExport {
    private const val TAG = "ZipExport"

    /** Zip the given files into the app cache, return the URI under FileProvider. */
    suspend fun zipToCache(ctx: Context, files: List<File>, archiveName: String): Uri =
        withContext(Dispatchers.IO) {
            val dir = ctx.cacheDir.resolve("export").apply { mkdirs() }
            val out = dir.resolve(archiveName)
            // Avoid stale partial archive on retry — replace atomically.
            if (out.exists()) out.delete()
            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                val seen = HashSet<String>()
                files.forEach { f ->
                    if (!f.exists() || !f.isFile) {
                        L.w(TAG, "skipping missing file: ${f.absolutePath}")
                        return@forEach
                    }
                    val entryName = uniqueName(seen, f.name)
                    zos.putNextEntry(ZipEntry(entryName))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            L.i(TAG, "zipped ${files.size} files → ${out.absolutePath} (${out.length()} B)")
            val authority = "${ctx.packageName}.fileprovider"
            FileProvider.getUriForFile(ctx, authority, out)
        }

    private fun uniqueName(seen: HashSet<String>, name: String): String {
        if (seen.add(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (true) {
            val candidate = "$base-$i$ext"
            if (seen.add(candidate)) return candidate
            i++
        }
    }
}
