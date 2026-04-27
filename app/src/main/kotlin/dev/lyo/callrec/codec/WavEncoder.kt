// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.codec

import dev.lyo.callrec.storage.RecordingFile
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RIFF/WAV writer. Keeps the file open as a [RandomAccessFile] so the final
 * close can rewrite the placeholder RIFF/data chunk sizes — otherwise VLC
 * (and any spec-strict decoder) refuses files that don't match the header.
 *
 * Why hand-rolled vs MediaMuxer:
 *  - MediaMuxer's MUXER_OUTPUT_OGG only takes Opus/Vorbis, MUXER_OUTPUT_MPEG_4
 *    only AAC. There is **no** raw-PCM/WAV muxer in AOSP, so we either roll
 *    this 60-line writer or pull a third-party lib.
 *  - The PCM coming from the pipe is already in the encoding (16-bit LE)
 *    that WAV expects — we're just bracketing it with 44 bytes of header.
 */
class WavEncoder(private val file: RecordingFile) : PcmEncoder {

    private lateinit var raf: RandomAccessFile
    private var sampleRate = 0
    private var channels = 0
    private var dataBytes = 0L

    override fun open(sampleRateHz: Int, channelCount: Int) {
        require(sampleRateHz > 0) { "sampleRate must be positive" }
        require(channelCount in 1..2) { "channels must be 1 or 2" }
        sampleRate = sampleRateHz
        channels = channelCount
        raf = RandomAccessFile(file.openOrCreate(), "rw")
        raf.setLength(0)
        // Placeholder header. Sizes get rewritten on close().
        raf.write(buildHeader(dataLen = 0))
    }

    override fun writePcm(buf: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        raf.write(buf, off, len)
        dataBytes += len
    }

    override fun close() {
        try {
            raf.seek(0)
            // dataBytes can exceed Int.MAX_VALUE for ~3+ hour calls at 16k/16-bit;
            // the WAV format has a 32-bit unsigned size field, so we cap at
            // UINT32_MAX (~4 GB). Real-world calls never come close.
            val truncated = dataBytes.coerceAtMost(0xFFFFFFFFL).toInt()
            raf.write(buildHeader(dataLen = truncated))
        } finally {
            runCatching { raf.fd.sync() }
            runCatching { raf.close() }
        }
    }

    private fun buildHeader(dataLen: Int): ByteArray {
        val bitsPerSample = 16
        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val totalSize = 36 + dataLen

        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)                              // PCM format chunk size
            putShort(1)                             // format = 1 (uncompressed PCM)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataLen)
        }.array()
    }
}
