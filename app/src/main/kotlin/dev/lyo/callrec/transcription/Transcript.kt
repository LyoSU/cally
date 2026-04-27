// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.transcription

import dev.lyo.callrec.core.L
import org.json.JSONObject

/**
 * Structured transcript shape we ask the cloud model to produce. JSON-based so
 * we can render with timestamps, speaker bubbles, and non-speech cues without
 * doing brittle regex parsing on free-form text.
 *
 * Stored verbatim in the DB's `transcript` column. UI parses on each render —
 * cheap, < 100 segments per call typically.
 */
data class Transcript(
    val language: String?,
    val durationSec: Double?,
    val segments: List<Segment>,
) {
    data class Segment(
        val startSec: Double,
        val endSec: Double,
        val speaker: Speaker,
        val text: String,
        /** "friendly" | "tense" | "neutral" | "excited" | "sad" | "angry" | null. */
        val tone: String?,
        /** "laugh" | "sigh" | "pause" | "background_music" | "background_voice" | … */
        val nonSpeech: List<String>,
    )

    enum class Speaker { ME, THEM, UNKNOWN }
}

object TranscriptCodec {

    /** Returns a parsed transcript, or null if the string isn't valid JSON. */
    fun parse(raw: String?): Transcript? {
        if (raw.isNullOrBlank()) {
            L.d("Transcript", "parse(null/blank) → null")
            return null
        }
        val trimmed = stripCodeFences(raw.trim())
        if (!trimmed.startsWith("{")) {
            L.d("Transcript", "parse: not JSON, starts with '${trimmed.take(40)}'")
            return null
        }
        return runCatching {
            val root = JSONObject(trimmed)
            val segs = root.optJSONArray("segments") ?: return@runCatching null
            val out = ArrayList<Transcript.Segment>(segs.length())
            for (i in 0 until segs.length()) {
                val s = segs.getJSONObject(i)
                out += Transcript.Segment(
                    startSec = s.optDouble("start", 0.0),
                    endSec = s.optDouble("end", 0.0),
                    speaker = when (s.optString("speaker", "unknown").lowercase()) {
                        "me" -> Transcript.Speaker.ME
                        "them" -> Transcript.Speaker.THEM
                        else -> Transcript.Speaker.UNKNOWN
                    },
                    text = s.optString("text", "").trim(),
                    tone = s.optString("tone").takeIf { it.isNotBlank() },
                    nonSpeech = s.optJSONArray("non_speech")?.let { arr ->
                        List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
                    } ?: emptyList(),
                )
            }
            val t = Transcript(
                language = root.optString("language").takeIf { it.isNotBlank() },
                durationSec = if (root.has("duration_sec")) root.optDouble("duration_sec") else null,
                segments = out,
            )
            L.i("Transcript", "parsed ${out.size} segments, lang=${t.language}")
            t
        }.onFailure { L.w("Transcript", "parse failed: ${it.message}") }.getOrNull()
    }

    /** Some providers wrap JSON in ```json ... ``` fences. Strip them. */
    private fun stripCodeFences(s: String): String {
        if (!s.startsWith("```")) return s
        val firstNewline = s.indexOf('\n')
        if (firstNewline < 0) return s
        val withoutOpen = s.substring(firstNewline + 1)
        val closeIdx = withoutOpen.lastIndexOf("```")
        return if (closeIdx >= 0) withoutOpen.substring(0, closeIdx).trim() else withoutOpen
    }
}
