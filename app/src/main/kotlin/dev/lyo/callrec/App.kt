// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec

import android.app.Application
import android.util.Log
import dev.lyo.callrec.cleanup.CleanupJob
import dev.lyo.callrec.di.AppContainer
import dev.lyo.callrec.notify.NotificationChannels
import kotlinx.coroutines.launch

class App : Application() {

    /**
     * Manual DI. The graph is intentionally tiny — Hilt would add ~150 KB to
     * the APK and a KSP step for no real win at this scale. `AppContainer`
     * wires Shizuku, storage, controller, and view-model factories lazily.
     */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        Log.i("Callrec", "[App] onCreate begin")
        super.onCreate()
        NotificationChannels.ensure(this)
        container = AppContainer(this)
        // Pre-warm Shizuku UserService daemon on a worker thread. Cold spawn
        // on Samsung takes 5-15 s; doing the bind via Shizuku Binder IPC on
        // the main thread (as the earlier code did) blocked Application
        // startup. attach() registers listeners cheaply and is fine to keep
        // synchronous so the rest of the cold-launch path sees Shizuku
        // already wired.
        container.shizuku.attach()
        container.appScope.launch {
            runCatching {
                container.shizuku.refresh()
                container.shizuku.bind()
            }.onFailure { Log.w("Callrec", "[App] Shizuku warmup failed: ${it.message}") }
        }
        // Fire-and-forget auto-cleanup. Both policies are off by default,
        // so the only cost when nothing is configured is one DataStore read
        // on Dispatchers.IO — the launch returns immediately and never
        // blocks Application.onCreate or the main thread.
        container.appScope.launch {
            runCatching { CleanupJob.runOnce(container.settings, container.db) }
                .onFailure { Log.w("Callrec", "[App] cleanup failed: ${it.message}", it) }
        }
        Log.i("Callrec", "[App] onCreate done (Shizuku pre-warm queued)")
    }
}
