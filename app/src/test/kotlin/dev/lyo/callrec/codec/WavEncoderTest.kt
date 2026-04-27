// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.codec

import com.google.common.truth.Truth.assertThat
import dev.lyo.callrec.storage.RecordingFile
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class WavEncoderTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun newFile(name: String): RecordingFile {
        val f = File(tmp.root, "$name.wav")
        return RecordingFile(name = name, tag = "test", path = f.absolutePath)
    }

    @Test fun `header sizes are rewritten on close`() {
        val rf = newFile("u")
        WavEncoder(rf).use { enc ->
            enc.open(sampleRateHz = 16_000, channelCount = 1)
            // 1 second of zero PCM = 32 000 bytes (16k samples * 2 bytes).
            enc.writePcm(ByteArray(32_000), 0, 32_000)
        }

        val f = File(rf.path)
        assertThat(f.exists()).isTrue()
        assertThat(f.length()).isEqualTo(44 + 32_000)

        RandomAccessFile(f, "r").use { raf ->
            val hdr = ByteArray(44).also { raf.read(it) }
            // RIFF / WAVE
            assertThat(hdr.copyOfRange(0, 4).toString(Charsets.US_ASCII)).isEqualTo("RIFF")
            assertThat(hdr.copyOfRange(8, 12).toString(Charsets.US_ASCII)).isEqualTo("WAVE")
            // data chunk size at offset 40 should equal payload bytes.
            val dataLen = (hdr[40].toInt() and 0xFF) or
                ((hdr[41].toInt() and 0xFF) shl 8) or
                ((hdr[42].toInt() and 0xFF) shl 16) or
                ((hdr[43].toInt() and 0xFF) shl 24)
            assertThat(dataLen).isEqualTo(32_000)
        }
    }

    @Test fun `byte rate matches sampleRate times blockAlign`() {
        val rf = newFile("br")
        WavEncoder(rf).use { it.open(48_000, 2); it.writePcm(ByteArray(16), 0, 16) }
        val f = File(rf.path)
        RandomAccessFile(f, "r").use { raf ->
            val hdr = ByteArray(44).also { raf.read(it) }
            // byteRate at offset 28 (LE int) = sampleRate * channels * bytesPerSample = 48000 * 2 * 2 = 192000
            val br = (hdr[28].toInt() and 0xFF) or
                ((hdr[29].toInt() and 0xFF) shl 8) or
                ((hdr[30].toInt() and 0xFF) shl 16) or
                ((hdr[31].toInt() and 0xFF) shl 24)
            assertThat(br).isEqualTo(192_000)
        }
    }
}
