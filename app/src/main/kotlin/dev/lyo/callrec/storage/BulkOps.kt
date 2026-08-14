// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.storage

import android.content.Context
import dev.lyo.callrec.core.L

object BulkOps {
    fun deleteFiles(ctx: Context, records: List<CallRecord>) {
        for (r in records) {
            // wrap each delete — file may already be gone or path may be invalid
            runCatching { RecordingPaths.delete(ctx, r.uplinkPath) }
                .onFailure { L.w("BulkOps", "delete uplink failed: ${it.message}") }
            r.downlinkPath?.let { p ->
                runCatching { RecordingPaths.delete(ctx, p) }
                    .onFailure { L.w("BulkOps", "delete downlink failed: ${it.message}") }
            }
        }
    }
}
