// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.transcription

import android.content.Context
import android.util.Base64
import dev.lyo.callrec.core.L
import dev.lyo.callrec.di.AppContainer
import dev.lyo.callrec.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloud-based speech-to-text using any OpenAI-compatible chat-completions
 * endpoint that accepts `input_audio` content parts (Gemini, OpenAI gpt-4o-
 * audio, Groq, OpenRouter that proxies any of those, etc.).
 *
 * Default config points at OpenRouter with `gemini-3.1-flash-lite-preview` —
 * the user can override base URL / model / API key in Settings. We never
 * proxy; the request goes straight from the device to whichever endpoint the
 * user configured.
 *
 * Whisper.cpp local was tried first but `tiny` model's Ukrainian accuracy
 * was too low to be useful, and shipping `medium` (~1.4 GB) on every
 * install was a non-starter.
 */
interface Transcriber {
    suspend fun transcribe(audioFile: File): String
}

object TranscriberFactory {
    fun create(container: AppContainer): Transcriber = CloudTranscriber(container.settings)
}

private class CloudTranscriber(private val settings: AppSettings) : Transcriber {

    override suspend fun transcribe(audioFile: File): String = withContext(Dispatchers.IO) {
        val baseUrl = settings.sttBaseUrl.first().trimEnd('/')
        val key = settings.sttApiKey.first()
        val model = settings.sttModel.first()
        require(key.isNotBlank()) {
            "API ключ не вказаний. Налаштування → Транскрипція → API ключ."
        }

        // Send the actual container name. Gemini's OpenAI-compat layer
        // accepts wav/mp3/m4a/aac directly; OpenAI's official Realtime API
        // is stricter (wav/pcm16 only) — users hitting that should toggle
        // RecordingFormat=WAV in Settings.
        val format = when (audioFile.extension.lowercase()) {
            "m4a", "mp4" -> "m4a"
            "aac" -> "aac"
            "wav" -> "wav"
            else -> audioFile.extension.lowercase().ifEmpty { "wav" }
        }
        val url = URL("$baseUrl/chat/completions")
        L.i("STT", "POST host=${url.host} model=$model audio=${audioFile.length()/1024}KB")

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 180_000
            // Stream the request body — earlier code did
            // `Base64.encode(file.readBytes(), …)` which doubles a multi-MB
            // file in memory before the JSON write. With chunked streaming
            // peak heap stays around ~64 KB regardless of audio length.
            setChunkedStreamingMode(0)
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { out -> writeStreamingPayload(out, model, audioFile, format) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            // Don't log response bodies at INFO — provider errors sometimes
            // echo a fragment of the bearer token. DEBUG only.
            L.d("STT", "code=$code body.len=${body.length}")
            if (code !in 200..299) {
                L.w("STT", "HTTP $code (body length=${body.length})")
                error("HTTP $code від ${url.host}")
            }
            parseTranscript(body)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Build and stream the chat-completions JSON envelope around a base64
     * audio payload. The audio is read in 24 KB chunks, base64-encoded into
     * 32 KB output blocks, and written through the connection without ever
     * holding the full file in memory.
     */
    private fun writeStreamingPayload(
        out: OutputStream,
        model: String,
        audioFile: File,
        format: String,
    ) {
        // Build the JSON skeleton with a placeholder where audio bytes go,
        // then split the serialised string and stream the audio between the
        // halves. Lets us reuse JSONObject's escaping for the prompt without
        // hand-rolling encoder logic.
        val placeholder = "__AUDIO_PLACEHOLDER__"
        val audioPart = JSONObject()
            .put("type", "input_audio")
            .put("input_audio", JSONObject()
                .put("data", placeholder)
                .put("format", format))
        val textPart = JSONObject()
            .put("type", "text")
            .put("text", PROMPT)
        val message = JSONObject()
            .put("role", "user")
            .put("content", JSONArray().put(audioPart).put(textPart))
        val root = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(message))
            .put("response_format", JSONObject().put("type", "json_object"))

        val full = root.toString()
        val splitAt = full.indexOf(placeholder)
        check(splitAt > 0) { "JSON serialisation lost the audio placeholder" }
        val prefix = full.substring(0, splitAt).toByteArray(Charsets.UTF_8)
        val suffix = full.substring(splitAt + placeholder.length).toByteArray(Charsets.UTF_8)

        out.write(prefix)
        streamBase64(audioFile, out)
        out.write(suffix)
        out.flush()
    }

    /**
     * Read [audioFile] in 3-byte-aligned chunks and write base64-encoded
     * output to [out]. The 3-byte alignment is required for incremental
     * base64: a chunk that isn't a multiple of 3 produces padding that, when
     * concatenated with the next chunk, no longer decodes correctly. We use
     * `NO_WRAP | NO_PADDING` and only allow the FINAL chunk to introduce
     * padding bytes.
     */
    private fun streamBase64(audioFile: File, out: OutputStream) {
        val chunk = ByteArray(CHUNK_BYTES) // 24 KB → 32 KB base64
        BufferedInputStream(audioFile.inputStream(), CHUNK_BYTES).use { input ->
            var pending: ByteArray? = null
            while (true) {
                val n = input.read(chunk)
                if (n <= 0) break
                if (pending != null) {
                    // Previous read wasn't 3-aligned — emit it now (no padding)
                    // because we have more data following.
                    out.write(Base64.encode(pending, Base64.NO_WRAP or Base64.NO_PADDING))
                    pending = null
                }
                val rem = n % 3
                val aligned = n - rem
                if (aligned > 0) {
                    out.write(Base64.encode(chunk, 0, aligned, Base64.NO_WRAP or Base64.NO_PADDING))
                }
                if (rem > 0) {
                    // Defer the trailing 1-2 bytes; if it turns out to be the
                    // last read, we'll emit them with padding below.
                    pending = chunk.copyOfRange(aligned, n)
                }
            }
            // EOF — flush any tail with proper padding so the decoder closes.
            if (pending != null) {
                out.write(Base64.encode(pending, Base64.NO_WRAP))
            }
        }
    }

    companion object {
        private const val CHUNK_BYTES = 24 * 1024
        // Per-utterance schema. We DON'T pass a JSON schema spec — providers
        // implement it inconsistently — but we describe it precisely in the
        // prompt and let response_format=json_object enforce well-formedness.
        private val PROMPT = """
            Транскрибуй цей запис телефонної розмови. Розпізнавай мову автоматично
            (українська / російська / англійська / суміш — зберігай як є). Якщо
            запис стерео: ЛІВИЙ канал переважно я (домінує там), ПРАВИЙ канал —
            переважно співрозмовник; обидві сторони чути в обох каналах, але
            домінуюча сторона визначає speaker. Якщо моно — використовуй
            "unknown" для невпевнених сегментів.

            Поверни ВИКЛЮЧНО JSON-обʼєкт у такій формі (без markdown-fences):
            {
              "language": "uk",                 // ISO code: uk | ru | en | mixed
              "duration_sec": 142.5,            // приблизна тривалість запису
              "segments": [
                {
                  "start": 0.0,                 // початок у секундах від 0
                  "end": 3.2,                   // кінець у секундах
                  "speaker": "me",              // "me" | "them" | "unknown"
                  "text": "Привіт, як справи?",
                  "tone": "friendly",           // "friendly"|"tense"|"neutral"|"excited"|"sad"|"angry"|"questioning" або null
                  "non_speech": ["laugh"]       // ["laugh","sigh","pause","background_music","background_voice","cough"...] або []
                }
              ]
            }

            Правила:
            • Розбивай на репліки. Зливай короткі підряд-репліки одного спікера якщо вони на одну тему.
            • non_speech: тільки якщо звук помітний (сміх, зітхання, кашель, музика на фоні).
            • Не вигадуй текст — якщо нерозбірливо, постав "[нерозбірливо]" в text.
            • Жодних коментарів, заголовків, пояснень — лише чистий JSON.
        """.trimIndent()
    }

    private fun parseTranscript(body: String): String {
        val root = JSONObject(body)
        val choices = root.optJSONArray("choices") ?: error("Відповідь без choices")
        if (choices.length() == 0) error("Порожній choices у відповіді")
        val msg = choices.getJSONObject(0).optJSONObject("message") ?: error("Без message в choices[0]")
        // Some providers return content as string, others as list of parts.
        return when (val c = msg.opt("content")) {
            is String -> c.trim()
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until c.length()) {
                    val part = c.getJSONObject(i)
                    if (part.optString("type") == "text") sb.append(part.optString("text"))
                }
                sb.toString().trim()
            }
            else -> error("Невідомий формат content")
        }
    }
}
