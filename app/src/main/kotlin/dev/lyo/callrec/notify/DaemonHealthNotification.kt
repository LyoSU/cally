// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dev.lyo.callrec.R
import dev.lyo.callrec.recorder.DaemonHealth
import dev.lyo.callrec.ui.MainActivity

object DaemonHealthNotification {
    private const val NOTIF_ID = 0xCA12
    const val EXTRA_FROM_HEALTH_NOTIF = "from_daemon_health_notif"

    fun update(ctx: Context, health: DaemonHealth) {
        val nm = ctx.getSystemService<NotificationManager>() ?: return
        // Anything non-faulty clears the notification instead of rewording
        // it. Gating on `is Bound` here meant the healthy Idle pause between
        // two calls raised an alarm every time a call ended.
        if (!health.isFault) {
            runCatching { nm.cancel(NOTIF_ID) }
            return
        }
        val (titleRes, bodyRes) = when (health) {
            DaemonHealth.NotInstalled -> R.string.daemon_notinstalled_title to R.string.daemon_notinstalled_body
            DaemonHealth.NotRunning -> R.string.daemon_notrunning_title to R.string.daemon_notrunning_body
            DaemonHealth.NoPermission -> R.string.daemon_nopermission_title to R.string.daemon_nopermission_body
            DaemonHealth.Stale -> R.string.daemon_stale_title to R.string.daemon_stale_body
            is DaemonHealth.Unhealthy -> R.string.daemon_unhealthy_title to R.string.daemon_unhealthy_body
            DaemonHealth.Idle, is DaemonHealth.Bound -> return
        }
        val tap = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_FROM_HEALTH_NOTIF, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(ctx, NotificationChannels.ID_STATUS)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(ctx.getString(titleRes))
            .setContentText(ctx.getString(bodyRes))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .build()
        runCatching { nm.notify(NOTIF_ID, notif) }
            .onFailure { /* POST_NOTIFICATIONS denied — silent drop is acceptable */ }
    }
}
