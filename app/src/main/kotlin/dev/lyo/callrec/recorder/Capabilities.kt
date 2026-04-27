// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.recorder

import android.content.Context
import android.media.MediaRecorder.AudioSource
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persisted knowledge about which capture strategy actually worked on this
 * exact device + OS build. Critical for two reasons:
 *
 *   1. Avoids burning ~3 seconds of every call on the fallback ladder when
 *      we already know the answer.
 *   2. Acts as the "we observed silence on strategy X — don't try it again
 *      this OS build" memory, so a one-time HAL refusal doesn't haunt every
 *      future call.
 *
 * Cache is keyed on [Build.FINGERPRINT] so an OS update auto-invalidates it.
 */
@Serializable
data class Capabilities(
    val fingerprint: String,
    /** Last strategy we *successfully* recorded with (audible). */
    val preferredStrategy: Strategy?,
    /** Strategies we observed produce silence on this build — skip them. */
    val knownSilent: Set<Strategy> = emptySet(),
    /** Strategies whose AudioRecord ctor failed outright on the device. */
    val knownFailedInit: Set<Strategy> = emptySet(),
    /**
     * Per-strategy count of consecutive silent probes. We only promote to
     * [knownSilent] after [SILENT_STRIKES_BEFORE_BLACKLIST] in a row, because
     * Samsung modems are non-deterministically slow to open the audio path —
     * one silent probe may simply mean "we sampled before the path was hot".
     * Audible verdict resets the counter for that strategy.
     */
    val silentStrikes: Map<Strategy, Int> = emptyMap(),
) {
    companion object {
        const val SILENT_STRIKES_BEFORE_BLACKLIST = 3
    }
}

/** A capture attempt, ordered from most-preferred to last-resort. */
@Serializable
enum class Strategy(
    val uplinkSource: Int?,
    val downlinkSource: Int?,
    val singleSource: Int?,
    val stereo: Boolean,
    val isDual: Boolean,
) {
    DualUplinkDownlink(AudioSource.VOICE_UPLINK, AudioSource.VOICE_DOWNLINK, null, false, true),
    DualMicDownlink(AudioSource.MIC, AudioSource.VOICE_DOWNLINK, null, false, true),
    SingleVoiceCallStereo(null, null, AudioSource.VOICE_CALL, true, false),
    SingleVoiceCallMono(null, null, AudioSource.VOICE_CALL, false, false),
    SingleMic(null, null, AudioSource.MIC, false, false);

    val displayName: String get() = when (this) {
        DualUplinkDownlink -> "Dual: UPLINK + DOWNLINK"
        DualMicDownlink -> "Dual: MIC + DOWNLINK"
        SingleVoiceCallStereo -> "VOICE_CALL stereo"
        SingleVoiceCallMono -> "VOICE_CALL mono"
        SingleMic -> "MIC only"
    }
}

private val Context.capsDataStore by preferencesDataStore("callrec.capabilities")

/**
 * Capability cache backed by DataStore. Reads return the deserialised value;
 * writes are atomic. We use a single JSON-encoded string preference rather
 * than splitting into many keys because it keeps the migration story simple
 * (one schema bump = one new field).
 */
class CapabilitiesStore(private val ctx: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val store: DataStore<Preferences> get() = ctx.capsDataStore
    private val KEY = stringPreferencesKey("v1")

    val flow: Flow<Capabilities?> = store.data.map { prefs ->
        prefs[KEY]?.let { runCatching { json.decodeFromString<Capabilities>(it) }.getOrNull() }
            ?.takeIf { it.fingerprint == Build.FINGERPRINT }
    }

    suspend fun current(): Capabilities? = flow.first()

    suspend fun update(transform: (Capabilities) -> Capabilities) {
        val cur = current() ?: Capabilities(Build.FINGERPRINT, null)
        val next = transform(cur)
        store.edit { it[KEY] = json.encodeToString(Capabilities.serializer(), next) }
    }

    suspend fun clear() {
        store.edit { it.remove(KEY) }
    }
}

