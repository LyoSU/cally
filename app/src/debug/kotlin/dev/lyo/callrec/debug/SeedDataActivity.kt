// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import dev.lyo.callrec.App
import dev.lyo.callrec.recorder.Strategy
import dev.lyo.callrec.storage.CallRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Debug-only entrypoint that wipes the recordings DB and seeds 8 plausible
 * sample calls so we can grab clean screenshots. Trigger from a host shell:
 *
 *   adb shell am start -n dev.lyo.callrec.debug/dev.lyo.callrec.debug.SeedDataActivity
 *
 * Lives under `app/src/debug/` so it cannot ship in a release build.
 *
 * Each fake recording gets a tiny silent WAV (~96 KB) on disk so the playback
 * screen and MediaPlayer prepare path don't crash if we tap into one for the
 * screenshot.
 */
class SeedDataActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope.launch {
            val count = withContext(Dispatchers.IO) { runSeed() }
            Toast.makeText(this@SeedDataActivity, "Seeded $count records", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private suspend fun runSeed(): Int {
        val app = application as App
        val container = app.container
        val dao = container.db.calls()

        val baseDir = getExternalFilesDir(null)?.resolve("recordings")
            ?: filesDir.resolve("recordings")
        baseDir.mkdirs()
        Log.i(TAG, "seeding into ${baseDir.absolutePath}")

        // Wipe existing records — we want a clean slate for screenshots.
        val all = dao.observeAllOnce()
        val ids = all.map { it.callId }
        if (ids.isNotEmpty()) {
            dao.deleteAll(ids)
            // Don't bother deleting on-disk audio for the prior seeded set —
            // they're tiny silent WAVs in the same dir, harmless.
        }

        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000

        val seeds = listOf(
            Seed(
                offsetMs = -3 * 60 * 60 * 1000L, // today, 3h ago
                durSec = 222, // 3:42
                name = "Андрій Коваленко",
                number = "+380671234567",
                strategy = Strategy.DualUplinkDownlink,
                favorite = true,
                notes = "Обговорили ціну на партію. Він дзвонить завтра з рішенням.",
            ),
            Seed(
                offsetMs = -6 * 60 * 60 * 1000L, // today, 6h ago
                durSec = 738, // 12:18
                name = "Мама",
                number = "+380505551234",
                strategy = Strategy.DualUplinkDownlink,
                favorite = true,
                transcript = MAMA_TRANSCRIPT,
            ),
            Seed(
                offsetMs = -1 * day - 7 * 60 * 60 * 1000L, // yesterday
                durSec = 321,
                name = "Олена (Моно)",
                number = "+380442000001",
                strategy = Strategy.SingleVoiceCallStereo,
            ),
            Seed(
                offsetMs = -1 * day - 14 * 60 * 60 * 1000L,
                durSec = 48,
                name = null,
                number = "+380939876543",
                strategy = Strategy.SingleVoiceCallMono,
            ),
            Seed(
                offsetMs = -3 * day,
                durSec = 534, // 8:54
                name = "Тарас",
                number = "+380963141592",
                strategy = Strategy.DualUplinkDownlink,
                notes = "Домовились на четвер о 16:00.",
            ),
            Seed(
                offsetMs = -4 * day,
                durSec = 1331, // 22:11
                name = "Марина (HR)",
                number = "+380957708888",
                strategy = Strategy.DualUplinkDownlink,
            ),
            Seed(
                offsetMs = -10 * day,
                durSec = 92,
                name = "Стоматолог",
                number = "+380445550099",
                strategy = Strategy.SingleMic,
            ),
            Seed(
                offsetMs = -22 * day,
                durSec = 34,
                name = "Доставка Нової Пошти",
                number = "+380500304400",
                strategy = Strategy.SingleMic,
            ),
        )

        for (seed in seeds) {
            val callId = UUID.randomUUID().toString()
            val started = now + seed.offsetMs
            val ended = started + seed.durSec * 1000L

            // Always write a tiny silent WAV for uplink. For dual strategies
            // also write a downlink track so MediaPlayer can prepare both.
            val uplink = File(baseDir, "$callId-uplink.wav")
            writeSilentWav(uplink)
            val downlink = if (seed.strategy.isDual) {
                File(baseDir, "$callId-downlink.wav").also { writeSilentWav(it) }
            } else null

            dao.upsert(
                CallRecord(
                    callId = callId,
                    startedAt = started,
                    endedAt = ended,
                    contactNumber = seed.number,
                    contactName = seed.name,
                    mode = seed.strategy.name,
                    uplinkPath = uplink.absolutePath,
                    downlinkPath = downlink?.absolutePath,
                    notes = seed.notes,
                    favorite = seed.favorite,
                    transcript = seed.transcript,
                ),
            )
        }
        return seeds.size
    }

    private data class Seed(
        val offsetMs: Long,
        val durSec: Int,
        val name: String?,
        val number: String,
        val strategy: Strategy,
        val favorite: Boolean = false,
        val notes: String? = null,
        val transcript: String? = null,
    )

    /**
     * Write a 3-second mono 16-bit 16 kHz silent WAV. ~96 KB — small enough to
     * seed eight tracks with no visible storage impact, but big enough to look
     * normal in the file list and to prepare cleanly in MediaPlayer.
     */
    private fun writeSilentWav(out: File) {
        val sampleRate = 16_000
        val seconds = 3
        val numSamples = sampleRate * seconds
        val byteRate = sampleRate * 2
        val dataSize = numSamples * 2
        val totalSize = 36 + dataSize

        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(totalSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)            // subchunk1 size
        buf.putShort(1)           // audio format = PCM
        buf.putShort(1)           // channels = mono
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(2)           // block align
        buf.putShort(16)          // bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        // Zero-filled by allocate() — that's our silent payload.
        out.writeBytes(buf.array())
    }

    private suspend fun dev.lyo.callrec.storage.CallDao.observeAllOnce(): List<CallRecord> =
        observeAll().first()

    companion object {
        private const val TAG = "SeedData"

        private val MAMA_TRANSCRIPT = """
        {
          "language": "uk",
          "duration_sec": 738,
          "segments": [
            {"start": 0.0, "end": 2.4, "speaker": "me", "text": "Привіт, мам! Ти як?", "tone": "friendly", "non_speech": []},
            {"start": 2.5, "end": 5.8, "speaker": "them", "text": "Привіт, синочку. Все добре, а в тебе?", "tone": "friendly", "non_speech": []},
            {"start": 6.0, "end": 9.2, "speaker": "me", "text": "Все ок. Завтра приїжджаю на вихідні.", "tone": "neutral", "non_speech": []},
            {"start": 9.5, "end": 12.0, "speaker": "them", "text": "О, чудово! Я вареники зроблю.", "tone": "excited", "non_speech": ["laugh"]},
            {"start": 12.4, "end": 14.6, "speaker": "me", "text": "Дякую! З картоплею, як завжди?", "tone": "friendly", "non_speech": []},
            {"start": 14.8, "end": 17.5, "speaker": "them", "text": "І з вишнею ще трошки — як любиш.", "tone": "friendly", "non_speech": []}
          ]
        }
        """.trimIndent()
    }
}
