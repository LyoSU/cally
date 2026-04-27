// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import dev.lyo.callrec.core.L
import dev.lyo.callrec.storage.RecordingFile
import java.nio.ByteBuffer

/**
 * AAC-LC encoder writing into an MP4 container (.m4a). Sync queue+drain loop:
 * each [writePcm] feeds available input buffers and drains all currently-ready
 * output buffers; [close] flags EOS and pumps the rest until done.
 *
 * Bitrate is conservative for voice — 32 kbps for 16 kHz mono is transparent
 * for speech and yields ~4 KB/s on disk (≈8x smaller than 16-bit PCM WAV).
 */
class AacEncoder(private val file: RecordingFile) : PcmEncoder {

    private lateinit var codec: MediaCodec
    private lateinit var muxer: MediaMuxer
    private val bufferInfo = MediaCodec.BufferInfo()

    private var sampleRate = 0
    private var channels = 0
    private var trackIndex = -1
    private var muxerStarted = false
    private var totalPcmFrames = 0L // per-channel frame count → drives PTS

    override fun open(sampleRateHz: Int, channelCount: Int) {
        require(sampleRateHz > 0) { "sampleRate must be positive" }
        require(channelCount in 1..2) { "channels must be 1 or 2" }
        sampleRate = sampleRateHz
        channels = channelCount

        // Force the file to exist; MediaMuxer needs the path to be writable.
        file.openOrCreate()

        val bitrate = if (channelCount == 2) 64_000 else 32_000
        val format = MediaFormat.createAudioFormat(MIME_AAC, sampleRateHz, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec = MediaCodec.createEncoderByType(MIME_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        L.d("AacEncoder", "open ${file.path} ${sampleRateHz}Hz ch=$channelCount @${bitrate}bps")
    }

    override fun writePcm(buf: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        var written = 0
        while (written < len) {
            val idx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (idx < 0) {
                // No input slot now — drain output to make space, then retry.
                drain(endOfStream = false)
                continue
            }
            val ib = codec.getInputBuffer(idx) ?: continue
            ib.clear()
            val chunk = minOf(ib.remaining(), len - written)
            ib.put(buf, off + written, chunk)
            val pts = totalPcmFrames * 1_000_000L / sampleRate
            codec.queueInputBuffer(idx, 0, chunk, pts, 0)
            // 2 bytes/sample/channel → frames consumed = chunk / (2 * channels)
            totalPcmFrames += chunk / (2L * channels)
            written += chunk
            drain(endOfStream = false)
        }
    }

    override fun close() {
        try {
            // Send EOS through an empty input buffer, then drain until done.
            val idx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US * 4)
            if (idx >= 0) {
                val pts = totalPcmFrames * 1_000_000L / sampleRate
                codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            } else {
                L.w("AacEncoder", "no input buffer for EOS — output may be truncated")
            }
            drain(endOfStream = true)
        } catch (t: Throwable) {
            L.e("AacEncoder", "close drain failed", t)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
        }
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    // Keep spinning — encoder still has frames to emit before EOS.
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "format changed twice" }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIdx >= 0 -> {
                    val ob: ByteBuffer = codec.getOutputBuffer(outIdx) ?: continue
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // Codec config travels via outputFormat → discard the duplicate.
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        ob.position(bufferInfo.offset)
                        ob.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, ob, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
                else -> return
            }
        }
    }

    private companion object {
        const val MIME_AAC = "audio/mp4a-latm"
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
