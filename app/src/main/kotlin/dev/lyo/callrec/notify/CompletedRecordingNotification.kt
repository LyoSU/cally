// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import dev.lyo.callrec.R
import dev.lyo.callrec.core.L
import dev.lyo.callrec.storage.CallRecord
import dev.lyo.callrec.ui.MainActivity

/**
 * Toast-like dismissible notification posted right after a recording is
 * fully written to disk. Tapping it deep-links into the playback screen
 * for that record. Cancels itself on tap (`setAutoCancel(true)`) so the
 * shade doesn't accumulate stale "saved" notifications across the day.
 *
 * Differs from the in-progress [RecordingNotification] (ongoing FGS) in
 * channel + priority + auto-cancel behaviour; we want this one to surface
 * briefly and disappear without nagging.
 */
object CompletedRecordingNotification {

    fun show(ctx: Context, rec: CallRecord) {
        val nm = ctx.getSystemService<NotificationManager>() ?: run {
            L.w(TAG, "show: no NotificationManager"); return
        }
        val nmc = NotificationManagerCompat.from(ctx)
        if (!nmc.areNotificationsEnabled()) {
            L.w(TAG, "show: notifications disabled by user — skip")
            return
        }

        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_OPEN_CALL_ID, rec.callId)
            // Reuse the existing task if the user is already in the app —
            // CLEAR_TOP would dump their nav stack, SINGLE_TOP keeps it.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val tap = PendingIntent.getActivity(
            ctx,
            rec.callId.hashCode(),
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = ctx.getString(R.string.notif_completed_title)
        val subtitle = buildSubtitle(ctx, rec)

        val notif = NotificationCompat.Builder(ctx, NotificationChannels.ID_COMPLETED)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .build()

        // Stable ID per callId so a re-recorded call (same id, rare) replaces
        // the previous notification rather than stacking.
        val notifId = notificationIdFor(rec.callId)
        nm.notify(notifId, notif)
        L.i(TAG, "show: posted callId=${rec.callId} notifId=$notifId")
    }

    private fun buildSubtitle(ctx: Context, rec: CallRecord): String {
        val ended = rec.endedAt ?: System.currentTimeMillis()
        val durationSec = ((ended - rec.startedAt) / 1000L).coerceAtLeast(0L)
        val durationStr = formatDuration(durationSec)
        val who = rec.contactName?.takeIf { it.isNotBlank() }
            ?: rec.contactNumber?.takeIf { it.isNotBlank() }
        return if (who != null) {
            ctx.getString(R.string.notif_completed_text_with_contact, who, durationStr)
        } else {
            ctx.getString(R.string.notif_completed_text_anonymous, durationStr)
        }
    }

    private fun formatDuration(totalSec: Long): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return if (m == 0L) "${s} с" else "%d:%02d".format(m, s)
    }

    private fun notificationIdFor(callId: String): Int =
        // Avoid collision with the foreground notification ID (0xC411) by
        // restricting hash range and offsetting.
        (callId.hashCode() and 0x7FFF) or 0x10000

    const val EXTRA_OPEN_CALL_ID = "callrec.open_call_id"
    private const val TAG = "CompletedNotif"
}
