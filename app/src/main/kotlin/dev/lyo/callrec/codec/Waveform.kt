// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.codec

import dev.lyo.callrec.core.L
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * One-shot amplitude reducer for the static waveform in the playback UI.
 *
 * **Streaming**: walks the file via [PcmDecoder.streamMono], keeping only one
 * decoder buffer (~16 KB) and the small `FloatArray` of bins in memory at a
 * time. The earlier implementation pulled the whole track into a `ShortArray`
 * first — for a 22-min call that's ~42 MB just to compute 200 floats. The
 * stream variant keeps peak heap pressure flat regardless of call duration.
 *
 * Each value in the returned array is a peak |sample|/32768 within its window.
 *
 * Why peak (not RMS): voice has lots of brief silence + bursts; peak makes
 * the "talk vs. silent" pattern legible at a glance, which is what a static
 * scrubbing waveform is for. RMS would smooth the silences into a flat ribbon.
 */
object Waveform {

    /**
     * Decode [file] in chunks and reduce it to [binCount] peak-amplitude bins
     * in `[0,1]`. Returns null on decode failure or if the track is too short
     * to bin (<= [binCount] samples).
     */
    fun buildBins(file: File, binCount: Int = 200): FloatArray? {
        val totalEstimate = PcmDecoder.estimateSampleCount(file) ?: return null
        if (totalEstimate < binCount) return null

        val bins = FloatArray(binCount)
        // Fixed bin width based on the up-front estimate. AAC duration is
        // ~1-2 ms accurate, so the last bin may absorb a few extra samples
        // without visible distortion.
        val windowSize = (totalEstimate.toDouble() / binCount).coerceAtLeast(1.0)
        var sampleIndex = 0L
        var peak = 0
        var bin = 0

        val emitted = PcmDecoder.streamMono(file) { samples, count ->
            for (i in 0 until count) {
                val v = abs(samples[i].toInt())
                if (v > peak) peak = v
                sampleIndex++
                val nextBoundary = ((bin + 1) * windowSize).toLong()
                if (sampleIndex >= nextBoundary && bin < binCount - 1) {
                    bins[bin] = peak / 32_768f
                    peak = 0
                    bin++
                }
            }
        } ?: return null

        // Last bin gets the leftover peak.
        bins[binCount - 1] = max(bins[binCount - 1], peak / 32_768f)
        if (emitted == 0) return null
        return bins
    }

    /**
     * Normalise so the loudest bin maps to 1.0 — keeps bars visually tall on
     * quiet recordings where the absolute peak is well below full scale.
     * Returns the same array (mutated) for chaining.
     */
    fun normalize(bins: FloatArray): FloatArray {
        var peak = 0f
        for (v in bins) if (v > peak) peak = v
        if (peak < 1e-3f) {
            L.w("Waveform", "track is essentially silent — leaving bins unscaled")
            return bins
        }
        val scale = 1f / peak
        for (i in bins.indices) bins[i] = (bins[i] * scale).coerceIn(0f, 1f)
        return bins
    }
}
