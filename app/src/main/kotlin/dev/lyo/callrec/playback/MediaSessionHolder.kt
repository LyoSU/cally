// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.playback

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.media.session.MediaButtonReceiver
import dev.lyo.callrec.R
import dev.lyo.callrec.core.L
import dev.lyo.callrec.notify.NotificationChannels
import dev.lyo.callrec.ui.MainActivity

/**
 * One MediaSession per process. Owned by AppContainer so its lifecycle
 * spans the entire app — the session is created once on first attach and
 * reused across PlaybackScreen instances.
 *
 * Workflow:
 *  1. PlaybackScreen calls [attach] with a [PlaybackController] and an
 *     intent to open this very record.
 *  2. Whenever the screen's playback state changes (play/pause/seek/
 *     duration update) it calls [push] — we serialise that into both
 *     MediaMetadataCompat (for lockscreen + shade) and
 *     PlaybackStateCompat (transport actions + scrubber position).
 *  3. Lockscreen / shade / BT headset transport commands flow back
 *     through the MediaSession.Callback into the controller.
 *  4. When the screen disposes it calls [detach] which deactivates the
 *     session and cancels the notification.
 *
 * We deliberately don't make this a full bound foreground service. That
 * would let playback survive screen exit, but adds significant lifecycle
 * complexity (managing FGS start/stop, handling process death cleanly,
 * sharing MediaPlayer across multiple owners). v1 covers the lockscreen
 * + notif-shade controls case which is 80% of perceived UX value.
 */
class MediaSessionHolder(private val ctx: Context) {

    private var session: MediaSessionCompat? = null
    private var controller: PlaybackController? = null
    private var notifShown = false

    fun attach(controller: PlaybackController) {
        this.controller = controller
        val s = session ?: createSession().also { session = it }
        s.isActive = true
        push() // initial metadata + state
    }

    fun detach() {
        controller = null
        session?.isActive = false
        cancelNotification()
    }

    /**
     * Mirror the controller's current state into the MediaSession + post
     * the matching MediaStyle notification. Called from PlaybackScreen
     * on each state change (play, pause, seek, speed change, position
     * tick — UI throttles ticks to ~10 Hz so notification updates don't
     * spam the system NotificationManager).
     */
    fun push() {
        val c = controller ?: return
        val s = session ?: return
        s.setMetadata(buildMetadata(c))
        s.setPlaybackState(buildPlaybackState(c))
        showOrUpdateNotification(s, c)
    }

    private fun createSession(): MediaSessionCompat {
        val holder = this
        return MediaSessionCompat(ctx, "callrec.playback").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { holder.controller?.play(); holder.push() }
                override fun onPause() { holder.controller?.pause(); holder.push() }
                override fun onSeekTo(pos: Long) { holder.controller?.seekTo(pos); holder.push() }
                override fun onSkipToPrevious() { holder.controller?.skipBack10(); holder.push() }
                override fun onSkipToNext() { holder.controller?.skipForward10(); holder.push() }
                override fun onRewind() { holder.controller?.skipBack10(); holder.push() }
                override fun onFastForward() { holder.controller?.skipForward10(); holder.push() }
                override fun onCustomAction(action: String?, extras: Bundle?) {
                    // No custom actions yet — left as a hook for speed select.
                }
            })
            setSessionActivity(
                PendingIntent.getActivity(
                    ctx, 0,
                    Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
    }

    private fun buildMetadata(c: PlaybackController): MediaMetadataCompat =
        MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, c.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, c.subtitle)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, c.durationMs)
            .build()

    private fun buildPlaybackState(c: PlaybackController): PlaybackStateCompat {
        val state = if (c.isPlaying) PlaybackStateCompat.STATE_PLAYING
        else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_REWIND or
            PlaybackStateCompat.ACTION_FAST_FORWARD
        return PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, c.positionMs, c.speed, SystemClock.elapsedRealtime())
            .build()
    }

    private fun showOrUpdateNotification(session: MediaSessionCompat, c: PlaybackController) {
        val nm = ctx.getSystemService<NotificationManager>() ?: return
        val isPlaying = c.isPlaying

        val skipBack = MediaButtonReceiver.buildMediaButtonPendingIntent(
            ctx, PlaybackStateCompat.ACTION_REWIND,
        )
        val skipFwd = MediaButtonReceiver.buildMediaButtonPendingIntent(
            ctx, PlaybackStateCompat.ACTION_FAST_FORWARD,
        )
        val playPause = MediaButtonReceiver.buildMediaButtonPendingIntent(
            ctx, PlaybackStateCompat.ACTION_PLAY_PAUSE,
        )

        val notif = NotificationCompat.Builder(ctx, NotificationChannels.ID_PLAYBACK)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(c.title)
            .setContentText(c.subtitle)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(session.controller.sessionActivity)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            // Mark deletable when paused so the user can dismiss it; while
            // playing keep it sticky so accidentally swiping doesn't kill
            // playback.
            .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(
                ctx, PlaybackStateCompat.ACTION_STOP,
            ))
            .addAction(
                android.R.drawable.ic_media_rew,
                ctx.getString(R.string.playback_skip_back), skipBack,
            )
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
                ctx.getString(if (isPlaying) R.string.playback_pause else R.string.playback_play),
                playPause,
            )
            .addAction(
                android.R.drawable.ic_media_ff,
                ctx.getString(R.string.playback_skip_forward), skipFwd,
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()

        runCatching { nm.notify(NOTIF_ID, notif) }
            .onFailure { L.w("Playback", "media notif post failed: ${it.message}") }
        notifShown = true
    }

    private fun cancelNotification() {
        if (!notifShown) return
        val nm = ctx.getSystemService<NotificationManager>() ?: return
        nm.cancel(NOTIF_ID)
        notifShown = false
    }

    fun release() {
        controller = null
        cancelNotification()
        session?.isActive = false
        session?.release()
        session = null
    }

    private companion object {
        const val NOTIF_ID = 0xC412
    }
}
