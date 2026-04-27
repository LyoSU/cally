// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.codec

/**
 * Sink for raw 16-bit little-endian PCM frames coming out of the Shizuku
 * pipe. Implementations must be thread-confined (one writer thread).
 *
 * Lifecycle: [open] → many [writePcm] → [close]. After [close] the encoder
 * is finalised — for WAV that means rewriting the RIFF header sizes.
 */
interface PcmEncoder : AutoCloseable {
    /** @param sampleRateHz e.g. 16_000; @param channelCount 1 (mono) or 2 (stereo) */
    fun open(sampleRateHz: Int, channelCount: Int)
    fun writePcm(buf: ByteArray, off: Int, len: Int)
    override fun close()
}
