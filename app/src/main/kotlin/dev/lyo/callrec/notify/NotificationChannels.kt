// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import dev.lyo.callrec.R

object NotificationChannels {
    const val ID_RECORDING = "callrec.recording"
    const val ID_STATUS = "callrec.status"
    const val ID_COMPLETED = "callrec.completed"
    const val ID_PLAYBACK = "callrec.playback"

    fun ensure(ctx: Context) {
        val nm = ctx.getSystemService<NotificationManager>() ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                ID_RECORDING,
                ctx.getString(R.string.notif_channel_recording),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = ctx.getString(R.string.notif_channel_recording_desc)
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                enableVibration(false)
                setSound(null, null)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                ID_STATUS,
                ctx.getString(R.string.notif_channel_status),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = ctx.getString(R.string.notif_channel_status_desc) },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                ID_COMPLETED,
                ctx.getString(R.string.notif_channel_completed),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = ctx.getString(R.string.notif_channel_completed_desc)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                ID_PLAYBACK,
                ctx.getString(R.string.notif_channel_playback),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = ctx.getString(R.string.notif_channel_playback_desc)
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                enableVibration(false)
                setSound(null, null)
            },
        )
    }
}
