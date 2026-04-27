// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.playback

/**
 * Mutable [PlaybackController] used by PlaybackScreen. The screen creates
 * one instance, updates its fields whenever the local Compose state
 * changes (playing / position / speed / metadata), then calls
 * `MediaSessionHolder.push()` so the MediaSession + notification mirror
 * the new state.
 *
 * Transport callbacks (play/pause/seek/skip) are wired in by the screen
 * via the `onPlay/onPause/...` lambdas — they end up calling the same
 * MediaPlayer methods the in-screen buttons trigger, which keeps state
 * authoritative on one side (the screen) and the session a pure mirror.
 */
class ScreenPlaybackController : PlaybackController {
    override var title: String = ""
    override var subtitle: String = ""
    override var durationMs: Long = 0L
    override var positionMs: Long = 0L
    override var isPlaying: Boolean = false
    override var speed: Float = 1f

    var onPlay: () -> Unit = {}
    var onPause: () -> Unit = {}
    var onSeek: (Long) -> Unit = {}
    var onSkipBack: () -> Unit = {}
    var onSkipForward: () -> Unit = {}

    override fun play() = onPlay()
    override fun pause() = onPause()
    override fun seekTo(positionMs: Long) = onSeek(positionMs)
    override fun skipBack10() = onSkipBack()
    override fun skipForward10() = onSkipForward()
}
