// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** On-disk container choice. AAC defaults — ~5–8x smaller than WAV for voice. */
enum class RecordingFormat { WAV, AAC }

/**
 * User-tunable knobs. Defaults reflect our recommendations from
 * IMPLEMENTATION_GUIDE §1.5 and §10:
 *  - 16 kHz (HAL native; doubles the bandwidth vs an 8 kHz baseline).
 *  - Auto-record on OFFHOOK including ringback.
 *  - Onboarding completed flag — drives the launch destination.
 */
class AppSettings(private val store: DataStore<Preferences>) {

    val sampleRate: Flow<Int> = store.data.map { it[KEY_SAMPLE_RATE] ?: 16_000 }
    suspend fun setSampleRate(v: Int) = store.edit { it[KEY_SAMPLE_RATE] = v }

    val autoRecord: Flow<Boolean> = store.data.map { it[KEY_AUTO_RECORD] ?: true }
    suspend fun setAutoRecord(v: Boolean) = store.edit { it[KEY_AUTO_RECORD] = v }

    val onboardingDone: Flow<Boolean> = store.data.map { it[KEY_ONBOARDING_DONE] ?: false }
    suspend fun setOnboardingDone(v: Boolean) = store.edit { it[KEY_ONBOARDING_DONE] = v }

    val recordIncludingRingback: Flow<Boolean> = store.data.map { it[KEY_RING_INCLUDED] ?: true }
    suspend fun setRecordIncludingRingback(v: Boolean) = store.edit { it[KEY_RING_INCLUDED] = v }

    val format: Flow<RecordingFormat> = store.data.map {
        runCatching { RecordingFormat.valueOf(it[KEY_FORMAT] ?: RecordingFormat.AAC.name) }
            .getOrDefault(RecordingFormat.AAC)
    }
    suspend fun setFormat(v: RecordingFormat) = store.edit { it[KEY_FORMAT] = v.name }

    // Cloud STT via OpenAI-compatible API. Default = Google's compat layer
    // with a multimodal Gemini Flash, which natively accepts audio input.
    // The user supplies their own API key (BYOK) — we never proxy.
    val sttBaseUrl: Flow<String> = store.data.map {
        it[KEY_STT_BASE_URL] ?: DEFAULT_STT_BASE_URL
    }
    suspend fun setSttBaseUrl(v: String) = store.edit { it[KEY_STT_BASE_URL] = v }

    val sttApiKey: Flow<String> = store.data.map { it[KEY_STT_API_KEY] ?: "" }
    suspend fun setSttApiKey(v: String) = store.edit { it[KEY_STT_API_KEY] = v }

    val sttModel: Flow<String> = store.data.map { it[KEY_STT_MODEL] ?: DEFAULT_STT_MODEL }
    suspend fun setSttModel(v: String) = store.edit { it[KEY_STT_MODEL] = v }

    /**
     * Auto-cleanup: max-age policy. `null` (the default) means OFF — no
     * recordings are deleted by age. When set, recordings older than N days
     * are pruned at app start. Allowed values: 7 / 14 / 30 / 60 / 90 / 180 / 365.
     */
    val autoCleanupMaxAgeDays: Flow<Int?> = store.data.map {
        val v = it[KEY_CLEANUP_MAX_AGE_DAYS] ?: 0
        if (v <= 0) null else v
    }
    suspend fun setAutoCleanupMaxAgeDays(v: Int?) = store.edit {
        if (v == null || v <= 0) it.remove(KEY_CLEANUP_MAX_AGE_DAYS)
        else it[KEY_CLEANUP_MAX_AGE_DAYS] = v
    }

    /**
     * Auto-cleanup: max-size policy in whole gigabytes. `null` means OFF.
     * When set, oldest non-favourite recordings are pruned until total
     * recording size is below the cap. Allowed values: 1 / 2 / 5 / 10 / 20 / 50.
     */
    val autoCleanupMaxSizeGb: Flow<Int?> = store.data.map {
        val v = it[KEY_CLEANUP_MAX_SIZE_GB] ?: 0
        if (v <= 0) null else v
    }
    suspend fun setAutoCleanupMaxSizeGb(v: Int?) = store.edit {
        if (v == null || v <= 0) it.remove(KEY_CLEANUP_MAX_SIZE_GB)
        else it[KEY_CLEANUP_MAX_SIZE_GB] = v
    }

    companion object {
        // OpenRouter — proxies the same OpenAI chat-completions schema across
        // hundreds of providers, accepts audio attachments natively for
        // multimodal models. The default model uses OpenRouter's slug format.
        const val DEFAULT_STT_BASE_URL = "https://openrouter.ai/api/v1"
        const val DEFAULT_STT_MODEL = "google/gemini-3.1-flash-lite-preview"
    }

    private object Keys {
        val SAMPLE_RATE = intPreferencesKey("sample_rate")
        val AUTO_RECORD = booleanPreferencesKey("auto_record")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val RING_INCLUDED = booleanPreferencesKey("ring_included")
        val FORMAT = stringPreferencesKey("recording_format")
        val STT_BASE_URL = stringPreferencesKey("stt_base_url")
        val STT_API_KEY = stringPreferencesKey("stt_api_key")
        val STT_MODEL = stringPreferencesKey("stt_model")
        val CLEANUP_MAX_AGE_DAYS = intPreferencesKey("auto_cleanup_max_age_days")
        val CLEANUP_MAX_SIZE_GB = intPreferencesKey("auto_cleanup_max_size_gb")
    }

    private val KEY_SAMPLE_RATE get() = Keys.SAMPLE_RATE
    private val KEY_AUTO_RECORD get() = Keys.AUTO_RECORD
    private val KEY_ONBOARDING_DONE get() = Keys.ONBOARDING_DONE
    private val KEY_RING_INCLUDED get() = Keys.RING_INCLUDED
    private val KEY_FORMAT get() = Keys.FORMAT
    private val KEY_STT_BASE_URL get() = Keys.STT_BASE_URL
    private val KEY_STT_API_KEY get() = Keys.STT_API_KEY
    private val KEY_STT_MODEL get() = Keys.STT_MODEL
    private val KEY_CLEANUP_MAX_AGE_DAYS get() = Keys.CLEANUP_MAX_AGE_DAYS
    private val KEY_CLEANUP_MAX_SIZE_GB get() = Keys.CLEANUP_MAX_SIZE_GB
}
