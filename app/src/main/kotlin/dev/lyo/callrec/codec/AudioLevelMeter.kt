// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.codec

import kotlin.math.sqrt

/**
 * Running RMS over 16-bit little-endian PCM. Cheap enough to run on every
 * pump tick (a few microseconds for an 8 KB buffer). The result is consumed
 * both by the UI (live level meter) and by the controller's silence detector
 * (auto-fallback if the chosen source is HAL-muted).
 *
 * RMS is normalised to the full-scale of int16 → returns a `Float` in
 * `[0.0f, 1.0f]`. Real-world voice content lives roughly 0.02-0.30; anything
 * < ~0.001 sustained for more than a second is "silence".
 */
class AudioLevelMeter {

    @Volatile var lastRms: Float = 0f
        private set
    /** Highest RMS observed since [reset] — used post-recording to decide whether a track was permanently silent. */
    @Volatile var maxRms: Float = 0f
        private set
    @Volatile var totalFrames: Long = 0L
        private set
    /** Frames at or below the silence floor since [reset]. */
    @Volatile var silentFrames: Long = 0L
        private set

    /**
     * Compute RMS over the slice and update running counters.
     * @param buf  raw PCM bytes (16-bit LE)
     * @param off  byte offset
     * @param len  byte length (must be even — caller guarantees)
     */
    fun update(buf: ByteArray, off: Int, len: Int) {
        if (len < 2) return
        val frames = len / 2
        var sumSq = 0.0
        var i = off
        val end = off + len
        while (i < end) {
            // Little-endian decode: low byte first.
            val low = buf[i].toInt() and 0xFF
            val high = buf[i + 1].toInt() // signed
            val sample = (high shl 8) or low
            val v = sample.toDouble()
            sumSq += v * v
            i += 2
        }
        val rms = (sqrt(sumSq / frames) / FULL_SCALE).toFloat().coerceIn(0f, 1f)
        lastRms = rms
        if (rms > maxRms) maxRms = rms
        totalFrames += frames
        if (rms < SILENCE_FLOOR) silentFrames += frames
        else silentFrames = 0  // any audible sample resets the streak
    }

    fun reset() {
        lastRms = 0f
        maxRms = 0f
        totalFrames = 0L
        silentFrames = 0L
    }

    /**
     * Time-aware silence detection. Returns true iff the meter has been
     * receiving below-threshold audio for at least [windowMs] of contiguous
     * frames at the given [sampleRate].
     */
    fun isSilent(sampleRate: Int, windowMs: Long = 2_000): Boolean =
        silentFrames * 1_000L / sampleRate >= windowMs

    companion object {
        private const val FULL_SCALE = 32_768.0
        /** -60 dBFS — quieter than ambient room noise, anything above is "voice". */
        private const val SILENCE_FLOOR = 0.001f
    }
}
