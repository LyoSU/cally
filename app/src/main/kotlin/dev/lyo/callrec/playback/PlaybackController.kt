// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.playback

/**
 * Adapter the PlaybackScreen exposes to the system's MediaSession +
 * MediaStyle notification. We intentionally keep the screen as the
 * MediaPlayer owner — moving the player into a separate service is a
 * larger refactor — but route lock-screen / shade / Bluetooth-headset
 * transport commands through this single interface.
 *
 * Implementations live for the duration of an open PlaybackScreen and
 * are wired in via [MediaSessionHolder.attach]; when the screen disposes
 * it calls [MediaSessionHolder.detach] which clears the session and
 * cancels the notification.
 */
interface PlaybackController {
    val title: String
    val subtitle: String
    val durationMs: Long
    val positionMs: Long
    val isPlaying: Boolean
    val speed: Float

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun skipBack10()
    fun skipForward10()
}
