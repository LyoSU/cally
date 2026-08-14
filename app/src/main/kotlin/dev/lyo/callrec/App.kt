// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.lyo.callrec.cleanup.CleanupJob
import dev.lyo.callrec.di.AppContainer
import dev.lyo.callrec.notify.DaemonHealthNotification
import dev.lyo.callrec.notify.NotificationChannels
import dev.lyo.callrec.storage.RecordingPaths
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
        // On every transition to RESUMED (app foregrounded), do a one-shot
        // AIDL ping against the bound daemon to detect zombie state. Costs
        // nothing while in background. Listener-driven for the rest.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                container.appScope.launch { container.shizuku.verifyHealth() }
            }
        })
        container = AppContainer(this)
        container.shizuku.health
            .onEach { DaemonHealthNotification.update(this, it) }
            .launchIn(container.appScope)
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
            runCatching { CleanupJob.runOnce(applicationContext, container.settings, container.db) }
                .onFailure { Log.w("Callrec", "[App] cleanup failed: ${it.message}", it) }
        }
        // Reconcile DB rows against the filesystem: if the user (or another
        // app) removed an audio file out-of-band, the row would otherwise
        // sit forever and tap-to-play would silently no-op. Only finalised
        // rows are scanned so an in-flight recording is never mistaken for
        // a desync.
        container.appScope.launch {
            runCatching { reconcileMissingFiles() }
                .onFailure { Log.w("Callrec", "[App] reconcile failed: ${it.message}", it) }
        }
        Log.i("Callrec", "[App] onCreate done (Shizuku pre-warm queued)")
    }

    private suspend fun reconcileMissingFiles() {
        val dao = container.db.calls()
        val rows = dao.selectAllFinalised()
        val orphans = rows.filter { rec ->
            val upGone = !RecordingPaths.exists(applicationContext, rec.uplinkPath)
            val dnGone = rec.downlinkPath?.let { !RecordingPaths.exists(applicationContext, it) } ?: true
            upGone && dnGone
        }
        if (orphans.isEmpty()) return
        Log.i("Callrec", "[App] reconcile: pruning ${orphans.size} row(s) with missing audio")
        dao.deleteAll(orphans.map { it.callId })
    }
}
