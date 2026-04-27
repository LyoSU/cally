// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.codec

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioLevelMeterTest {

    @Test fun `silent buffer yields rms near zero`() {
        val m = AudioLevelMeter()
        m.update(ByteArray(2_048), 0, 2_048)
        assertThat(m.lastRms).isLessThan(0.001f)
        assertThat(m.silentFrames).isEqualTo(1_024)
    }

    @Test fun `audible sine wave yields nontrivial rms`() {
        val m = AudioLevelMeter()
        // 1 second of 1 kHz sine at 16 kHz, half-amplitude.
        val sr = 16_000
        val frames = sr
        val buf = ByteArray(frames * 2)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * 1_000 * i / sr) * 16_000).toInt().toShort()
            buf[i * 2] = (v.toInt() and 0xFF).toByte()
            buf[i * 2 + 1] = ((v.toInt() ushr 8) and 0xFF).toByte()
        }
        m.update(buf, 0, buf.size)
        // RMS of half-amplitude sine = (16000 / 32768) / sqrt(2) ≈ 0.345.
        assertThat(m.lastRms).isWithin(0.05f).of(0.345f)
        assertThat(m.silentFrames).isEqualTo(0)
    }

    @Test fun `silentFrames resets when audible sample arrives`() {
        val m = AudioLevelMeter()
        m.update(ByteArray(1_000), 0, 1_000)        // 500 frames of silence
        assertThat(m.silentFrames).isEqualTo(500)

        // Now a chunk with one loud sample at the end.
        val mixed = ByteArray(1_000)
        // last sample = 0x7FFF (max positive int16), little endian
        mixed[mixed.size - 2] = 0xFF.toByte()
        mixed[mixed.size - 1] = 0x7F
        m.update(mixed, 0, mixed.size)
        assertThat(m.silentFrames).isEqualTo(0)
    }

    @Test fun `isSilent uses sample-rate aware window`() {
        val m = AudioLevelMeter()
        // 16 kHz, 2 s of silence = 32 000 frames.
        m.update(ByteArray(64_000), 0, 64_000)
        assertThat(m.isSilent(sampleRate = 16_000, windowMs = 2_000)).isTrue()
        assertThat(m.isSilent(sampleRate = 16_000, windowMs = 5_000)).isFalse()
    }
}
