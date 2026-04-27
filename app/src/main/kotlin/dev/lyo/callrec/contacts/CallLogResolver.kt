// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.contacts

import android.content.Context
import android.provider.CallLog
import dev.lyo.callrec.core.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Post-mortem fallback: the system commits the dialed number into CallLog
// only after IDLE on most OEMs. We query the most recent row whose DATE is
// near our recording's start time (window: 60 s back to absorb clock skew).
object CallLogResolver {

    private const val SLOP_MS = 60_000L

    suspend fun mostRecentNumber(ctx: Context, sinceMs: Long): String? = withContext(Dispatchers.IO) {
        runCatching {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf((sinceMs - SLOP_MS).toString()),
                "${CallLog.Calls.DATE} DESC LIMIT 1",
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrElse {
            // READ_CALL_LOG may be revoked on some OEMs even when manifest-declared.
            L.w("CallLog", "query failed: ${it.message}")
            null
        }
    }
}
