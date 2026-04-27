// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import dev.lyo.callrec.playback.MediaSessionHolder
import dev.lyo.callrec.recorder.CapabilitiesStore
import dev.lyo.callrec.recorder.RecorderController
import dev.lyo.callrec.recorder.ShizukuClient
import dev.lyo.callrec.settings.AppSettings
import dev.lyo.callrec.settings.RecordingFormat
import dev.lyo.callrec.storage.RecordingStorage
import dev.lyo.callrec.storage.RecordingsDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

private val Context.dataStore by preferencesDataStore(name = "callrec.settings")

/**
 * Hand-rolled DI container. One instance per process, owned by [App].
 *
 * Lazy fields ensure that nothing touches Shizuku, the audio HAL, or
 * RoomDatabase on the cold path of Application.onCreate — we want the splash
 * to disappear quickly. Heavy work happens on first access from a coroutine.
 */
class AppContainer(private val ctx: Context) {

    /** Long-lived scope for foreground services and one-shot persistence work. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: AppSettings by lazy { AppSettings(ctx.dataStore) }

    val db: RecordingsDb by lazy { RecordingsDb.create(ctx) }

    val storage: RecordingStorage by lazy {
        RecordingStorage(
            appCtx = ctx.applicationContext,
            settings = settings,
        )
    }

    val shizuku: ShizukuClient by lazy { ShizukuClient(ctx.applicationContext) }

    val capabilities: CapabilitiesStore by lazy { CapabilitiesStore(ctx.applicationContext) }

    /**
     * Hot StateFlow mirror of the recording-format setting. The earlier
     * implementation called `runBlocking(Dispatchers.IO) { settings.format.first() }`
     * inside the recorder's `formatProvider`, which (a) blocks the calling
     * coroutine — including the lifecycle coroutine of CallMonitorService when
     * a call starts — and (b) re-reads the protobuf on every call. Caching
     * once via [stateIn] eliminates the block; updates from Settings still
     * propagate immediately through the same StateFlow.
     */
    val recordingFormat: StateFlow<RecordingFormat> by lazy {
        settings.format.stateIn(
            scope = appScope,
            started = SharingStarted.Eagerly,
            initialValue = RecordingFormat.AAC,
        )
    }

    val recorder: RecorderController by lazy {
        RecorderController(
            client = shizuku,
            storage = storage,
            capabilities = capabilities,
            scope = appScope,
            // Non-blocking — reads cached StateFlow value.
            formatProvider = { recordingFormat.value },
        )
    }

    /**
     * One MediaSession + MediaStyle notification host for the whole app.
     * Owned at container level so it survives across PlaybackScreen
     * recompositions and back-stack navigation.
     */
    val mediaSession: MediaSessionHolder by lazy { MediaSessionHolder(ctx.applicationContext) }
}
