// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.codec

import dev.lyo.callrec.core.L
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Combines an uplink and downlink mono recording (WAV or M4A/AAC) into a
 * single 2-channel stereo WAV with a **soft-pan** mix (≈75/25) — uplink
 * sits mostly on the left, downlink mostly on the right, but each side is
 * still audible in the other ear. This matches how conference-call apps
 * lay out a recording: spatial separation for the brain to disentangle
 * speakers, without the "one voice in one ear" weirdness of hard L/R.
 *
 * Why not hard-pan (100/0):
 *  - **Listening UX**: pulling out one earbud loses half the conversation.
 *  - **Whisper-based STT**: providers that downmix to mono before inference
 *    see ~50% silence per channel during single-side speech, which confuses
 *    VAD. Soft-pan looks like a real conference recording.
 *
 * Why not mono sum:
 *  - **Gemini-style audio LLMs**: channel separation is a real diarization
 *    hint. Soft-pan keeps the cue.
 *  - **Overlapping speech**: pure sum smears two simultaneous voices.
 *
 * Decoding is delegated to [PcmDecoder] — single source of truth for PCM
 * extraction; this object is purely the pan + RIFF-write step.
 */
object AudioMixer {

    private const val SAMPLE_RATE = 16_000

    /** Pan weights for the dominant side. 0.75 means L = 0.75·up + 0.25·dn. */
    private const val DOMINANT = 0.75f
    private const val BLEED = 1f - DOMINANT

    /**
     * Decode both files to int16 PCM mono, soft-pan into stereo, write WAV.
     * Returns the output file on success, null if either decode fails.
     */
    fun mixToStereoWav(uplink: File, downlink: File, out: File): File? {
        val upPcm = PcmDecoder.readBytes(uplink) ?: return null
        val dnPcm = PcmDecoder.readBytes(downlink) ?: return null
        val frames = min(upPcm.size, dnPcm.size) / 2 // bytes per int16
        val stereo = ByteArray(frames * 4) // 2 channels × 2 bytes
        val upBuf = ByteBuffer.wrap(upPcm).order(ByteOrder.LITTLE_ENDIAN)
        val dnBuf = ByteBuffer.wrap(dnPcm).order(ByteOrder.LITTLE_ENDIAN)
        val outBuf = ByteBuffer.wrap(stereo).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            val u = upBuf.short.toInt()
            val d = dnBuf.short.toInt()
            // L: dominant uplink + bleed downlink. R: mirror.
            // Sum of weights = 1.0 → no clipping for valid int16 inputs.
            val l = (u * DOMINANT + d * BLEED).toInt().coerceIn(-32768, 32767)
            val r = (u * BLEED + d * DOMINANT).toInt().coerceIn(-32768, 32767)
            outBuf.putShort(l.toShort())
            outBuf.putShort(r.toShort())
        }
        writeWav(out, SAMPLE_RATE, channels = 2, pcm = stereo)
        L.i(TAG, "soft-pan mix → ${out.path} (${stereo.size} bytes, $frames frames)")
        return out
    }

    private fun writeWav(file: File, sampleRate: Int, channels: Int, pcm: ByteArray) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val bitsPerSample = 16
            val blockAlign = channels * bitsPerSample / 8
            val byteRate = sampleRate * blockAlign
            val totalSize = 36 + pcm.size
            val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(totalSize); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1)
                putShort(channels.toShort()); putInt(sampleRate); putInt(byteRate)
                putShort(blockAlign.toShort()); putShort(bitsPerSample.toShort())
                put("data".toByteArray()); putInt(pcm.size)
            }.array()
            raf.write(hdr); raf.write(pcm)
        }
    }

    private const val TAG = "Mixer"
}
