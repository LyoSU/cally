// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.codec

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import dev.lyo.callrec.core.L
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Central PCM decoder — single source of truth for "give me int16 mono LE
 * samples from this file". Used by [AudioMixer] (to mix two tracks into
 * stereo), by [Waveform] (to build amplitude bins for the player UI), and by
 * anything else that needs to look at raw audio. Keeping the decode path in
 * one place means a fix here flows through every consumer.
 *
 * Supports:
 *  - WAV (RIFF/PCM int16, mono or stereo — stereo is auto-downmixed by
 *    averaging L+R per frame).
 *  - M4A / AAC (decoded via MediaExtractor + MediaCodec; output buffer
 *    layout follows the codec's declared channel count).
 */
object PcmDecoder {

    /** Same as [readBytes] but reinterpreted as int16 LE shorts. */
    fun readShorts(file: File): ShortArray? {
        val bytes = readBytes(file) ?: return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(bytes.size / 2)
        var i = 0
        while (buf.remaining() >= 2) {
            out[i++] = buf.short
        }
        return out
    }

    /**
     * Estimated total mono int16 sample count for [file] without decoding it.
     * Used by streaming consumers (e.g. [Waveform]) to size their output up
     * front. Reads RIFF header for WAV; for AAC reads container duration via
     * MediaExtractor (no codec spin-up). Returns null on parse failure.
     *
     * The estimate may differ from the actual decode by a few hundred samples
     * for variable-bitrate AAC because container duration is rounded — that's
     * fine for waveform binning.
     */
    fun estimateSampleCount(file: File): Int? = runCatching {
        when (file.extension.lowercase()) {
            "wav" -> wavMonoSampleCount(file)
            "m4a", "mp4", "aac" -> aacMonoSampleCount(file)
            else -> null
        }
    }.getOrNull()

    private fun wavMonoSampleCount(file: File): Int? {
        RandomAccessFile(file, "r").use { raf ->
            val riff = ByteArray(12); raf.readFully(riff)
            if (String(riff, 0, 4) != "RIFF" || String(riff, 8, 4) != "WAVE") return null
            var channels = 1
            while (raf.filePointer < raf.length()) {
                val tag = ByteArray(4); raf.readFully(tag)
                val len = readIntLe(raf)
                when (String(tag)) {
                    "fmt " -> {
                        val fmt = ByteArray(len); raf.readFully(fmt)
                        channels = ((fmt[2].toInt() and 0xff) or ((fmt[3].toInt() and 0xff) shl 8))
                    }
                    "data" -> return len / (2 * channels.coerceAtLeast(1))
                    else -> raf.skipBytes(len)
                }
            }
        }
        return null
    }

    private fun aacMonoSampleCount(file: File): Int? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) continue
                val sr = runCatching { f.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrNull() ?: continue
                val durUs = runCatching { f.getLong(MediaFormat.KEY_DURATION) }.getOrNull() ?: continue
                return ((durUs * sr) / 1_000_000L).toInt().coerceAtLeast(0)
            }
        } finally { ex.release() }
        return null
    }

    /**
     * Stream-decode [file] into mono int16 chunks, invoking [onChunk] for each
     * decoder/file output buffer. The buffer passed to the callback is reused
     * — copy out anything you need to retain across calls.
     *
     * Returns the total sample count actually delivered, or null on failure.
     *
     * Why streaming: a 22-minute call decodes to ~42 MB of PCM. Loading the
     * whole thing into a ByteArray (as [readBytes] does) just to bin it down
     * to 200 floats is wasteful; this lets [Waveform] process samples as they
     * come and never hold more than one decoder buffer at a time.
     */
    fun streamMono(file: File, onChunk: (samples: ShortArray, count: Int) -> Unit): Int? = runCatching {
        when (file.extension.lowercase()) {
            "wav" -> streamWavMono(file, onChunk)
            "m4a", "mp4", "aac" -> streamAacMono(file, onChunk)
            else -> {
                L.w(TAG, "unknown extension ${file.extension} — skipping")
                null
            }
        }
    }.onFailure { L.e(TAG, "stream decode failed for ${file.name}: ${it.message}") }.getOrNull()

    private fun streamWavMono(file: File, onChunk: (ShortArray, Int) -> Unit): Int {
        RandomAccessFile(file, "r").use { raf ->
            val riff = ByteArray(12); raf.readFully(riff)
            if (String(riff, 0, 4) != "RIFF" || String(riff, 8, 4) != "WAVE") error("not RIFF/WAVE")
            var channels = 1
            var dataLen = -1
            while (raf.filePointer < raf.length()) {
                val tagBytes = ByteArray(4); raf.readFully(tagBytes)
                val len = readIntLe(raf)
                when (String(tagBytes)) {
                    "fmt " -> {
                        val fmt = ByteArray(len); raf.readFully(fmt)
                        channels = ((fmt[2].toInt() and 0xff) or ((fmt[3].toInt() and 0xff) shl 8))
                    }
                    "data" -> { dataLen = len; break }
                    else -> raf.skipBytes(len)
                }
            }
            if (dataLen <= 0) error("no data chunk")
            val byteBuf = ByteArray(STREAM_BUF_BYTES)
            val shortBuf = ShortArray(STREAM_BUF_BYTES / 2)
            var emitted = 0
            var remaining = dataLen
            while (remaining > 0) {
                val want = minOf(byteBuf.size, remaining)
                val read = raf.read(byteBuf, 0, want)
                if (read <= 0) break
                remaining -= read
                if (channels <= 1) {
                    val frames = read / 2
                    val bb = ByteBuffer.wrap(byteBuf, 0, frames * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until frames) shortBuf[i] = bb.short
                    onChunk(shortBuf, frames)
                    emitted += frames
                } else {
                    // Downmix L+R → mono on the fly.
                    val frames = read / (2 * channels)
                    val bb = ByteBuffer.wrap(byteBuf, 0, frames * 2 * channels).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until frames) {
                        var sum = 0
                        repeat(channels) { sum += bb.short.toInt() }
                        shortBuf[i] = (sum / channels).toShort()
                    }
                    onChunk(shortBuf, frames)
                    emitted += frames
                }
            }
            return emitted
        }
    }

    private fun streamAacMono(file: File, onChunk: (ShortArray, Int) -> Unit): Int {
        val ex = MediaExtractor().apply { setDataSource(file.path) }
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i; format = f; break
            }
        }
        if (trackIndex < 0 || format == null) { ex.release(); error("no audio track") }
        ex.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        var emitted = 0
        // Reusable scratch — avoids allocating a ShortArray per output buffer.
        var scratch = ShortArray(STREAM_BUF_BYTES / 2)
        try {
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    ob.position(info.offset)
                    val byteCount = info.size
                    val shortCount = byteCount / 2
                    if (channels <= 1) {
                        if (scratch.size < shortCount) scratch = ShortArray(shortCount)
                        val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        sb.get(scratch, 0, shortCount)
                        onChunk(scratch, shortCount)
                        emitted += shortCount
                    } else {
                        val frames = shortCount / channels
                        if (scratch.size < frames) scratch = ShortArray(frames)
                        val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        for (i in 0 until frames) {
                            var sum = 0
                            repeat(channels) { sum += sb.get().toInt() }
                            scratch[i] = (sum / channels).toShort()
                        }
                        onChunk(scratch, frames)
                        emitted += frames
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEos = true
                }
            }
        } finally {
            codec.stop(); codec.release(); ex.release()
        }
        return emitted
    }

    /** Decode to mono int16 LE bytes. Returns null on any failure. */
    fun readBytes(file: File): ByteArray? = runCatching {
        when (file.extension.lowercase()) {
            "wav" -> readWavMono(file)
            "m4a", "mp4", "aac" -> decodeAacMono(file)
            else -> {
                L.w(TAG, "unknown extension ${file.extension} — skipping")
                null
            }
        }
    }.onFailure { L.e(TAG, "decode failed for ${file.name}: ${it.message}") }.getOrNull()

    // ─── WAV ──────────────────────────────────────────────────────────────────

    private fun readWavMono(file: File): ByteArray {
        RandomAccessFile(file, "r").use { raf ->
            // RIFF header (12) + 'fmt ' chunk header (8) + ≥16 fmt body.
            val riff = ByteArray(12); raf.readFully(riff)
            if (String(riff, 0, 4) != "RIFF" || String(riff, 8, 4) != "WAVE") {
                error("not a RIFF/WAVE file: $file")
            }
            // Walk chunks until we find 'fmt ' and 'data'.
            var channels = 1
            var dataOffset = -1L
            var dataLen = -1
            while (raf.filePointer < raf.length()) {
                val tagBytes = ByteArray(4); raf.readFully(tagBytes)
                val len = readIntLe(raf)
                val tag = String(tagBytes)
                when (tag) {
                    "fmt " -> {
                        val fmt = ByteArray(len); raf.readFully(fmt)
                        // bytes 2..3 = numChannels (LE16)
                        channels = ((fmt[2].toInt() and 0xff) or ((fmt[3].toInt() and 0xff) shl 8))
                    }
                    "data" -> { dataOffset = raf.filePointer; dataLen = len; break }
                    else -> raf.skipBytes(len)
                }
            }
            if (dataOffset < 0 || dataLen <= 0) error("no data chunk in $file")
            raf.seek(dataOffset)
            val raw = ByteArray(dataLen)
            raf.readFully(raw)
            return if (channels <= 1) raw else downmixStereoToMono(raw)
        }
    }

    /** Average L + R per frame to collapse stereo PCM int16 into mono. */
    private fun downmixStereoToMono(stereo: ByteArray): ByteArray {
        val frames = stereo.size / 4
        val mono = ByteArray(frames * 2)
        val src = ByteBuffer.wrap(stereo).order(ByteOrder.LITTLE_ENDIAN)
        val dst = ByteBuffer.wrap(mono).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            val l = src.short.toInt()
            val r = src.short.toInt()
            dst.putShort(((l + r) shr 1).toShort())
        }
        return mono
    }

    private fun readIntLe(raf: RandomAccessFile): Int {
        val b = ByteArray(4); raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    // ─── AAC / M4A ────────────────────────────────────────────────────────────

    private fun decodeAacMono(file: File): ByteArray {
        val ex = MediaExtractor().apply { setDataSource(file.path) }
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i; format = f; break
            }
        }
        if (trackIndex < 0 || format == null) error("no audio track in $file")
        ex.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val raw = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        try {
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    val chunk = ByteArray(info.size)
                    ob.position(info.offset); ob.get(chunk)
                    raw.write(chunk)
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEos = true
                }
            }
        } finally {
            codec.stop(); codec.release(); ex.release()
        }
        val bytes = raw.toByteArray()
        return if (channels <= 1) bytes else downmixStereoToMono(bytes)
    }

    private const val TAG = "PcmDecoder"
    private const val STREAM_BUF_BYTES = 16 * 1024
}
