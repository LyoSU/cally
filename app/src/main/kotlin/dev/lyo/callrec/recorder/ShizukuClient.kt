// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.recorder

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dev.lyo.callrec.BuildConfig
import dev.lyo.callrec.aidl.IRecorderService
import dev.lyo.callrec.core.L
import dev.lyo.callrec.userservice.RecorderService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Coroutine-friendly façade over the Shizuku binding API.
 *
 *  - Tracks both server presence (Binder ping) and our user-service binding.
 *  - Survives device rotation (held by [AppContainer], not the Activity).
 *  - Uses `daemon = true` so the privileged process stays alive between calls.
 *    [refresh] compares the live daemon's `getVersion()` against our build —
 *    if they diverge after an APK upgrade we tear down with `remove = true`.
 */
class ShizukuClient(private val ctx: Context) {

    private val _state = MutableStateFlow(ShizukuState.NotRunning)
    val state: StateFlow<ShizukuState> = _state

    private val _service = MutableStateFlow<IRecorderService?>(null)
    val service: StateFlow<IRecorderService?> = _service

    private val args: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(ctx.packageName, RecorderService::class.java.name),
        )
            .daemon(true)
            .processNameSuffix("recorder")
            .debuggable(BuildConfig.DEBUG)
            .version(USER_SERVICE_VERSION)
            .tag("callrec-recorder")
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = IRecorderService.Stub.asInterface(binder)
            val daemonVersion = runCatching { svc.version }.getOrDefault(-1)
            L.i("Shizuku", "onServiceConnected daemon v=$daemonVersion (we want $USER_SERVICE_VERSION)")
            if (daemonVersion != USER_SERVICE_VERSION) {
                L.w("Shizuku", "Daemon stale — unbinding with remove=true to respawn")
                runCatching { Shizuku.unbindUserService(args, this, /* remove = */ true) }
                _service.value = null
                return
            }
            _service.value = svc
        }
        override fun onServiceDisconnected(name: ComponentName) {
            L.w("Shizuku", "onServiceDisconnected")
            _service.value = null
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) {
            _state.value = ShizukuState.Ready
            permissionContinuation?.resume(true)
        } else {
            _state.value = ShizukuState.Denied
            permissionContinuation?.resume(false)
        }
        permissionContinuation = null
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _state.value = ShizukuState.NotRunning
        _service.value = null
    }

    @Volatile private var permissionContinuation: kotlin.coroutines.Continuation<Boolean>? = null

    fun attach() {
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        refresh()
    }

    fun detach() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    /** Re-evaluate Shizuku presence + permission. Cheap; safe to call often. */
    fun refresh() {
        val s = when {
            !Shizuku.pingBinder() -> ShizukuState.NotRunning
            @Suppress("DEPRECATION") Shizuku.isPreV11() -> ShizukuState.Outdated
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                ShizukuState.Ready
            Shizuku.shouldShowRequestPermissionRationale() -> ShizukuState.Denied
            else -> ShizukuState.NeedPermission
        }
        if (_state.value != s) L.i("Shizuku", "state ${_state.value} → $s")
        _state.value = s
    }

    /**
     * Trigger Shizuku's permission dialog. Suspends until the listener fires.
     * Cancellation is best-effort: the dialog is system-owned, but we drop
     * the continuation so any subsequent grant/deny is ignored.
     */
    suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { cont ->
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            cont.resume(true); return@suspendCancellableCoroutine
        }
        permissionContinuation = cont
        Shizuku.requestPermission(REQUEST_CODE)
        cont.invokeOnCancellation { permissionContinuation = null }
    }

    /** Bind the user-service. No-op if already bound. */
    fun bind() {
        if (_service.value != null) { L.v("Shizuku", "bind: already bound"); return }
        if (_state.value != ShizukuState.Ready) { L.w("Shizuku", "bind skipped, state=${_state.value}"); return }
        L.i("Shizuku", "bindUserService → starting daemon")
        runCatching { Shizuku.bindUserService(args, conn) }
            .onFailure { L.e("Shizuku", "bindUserService threw", it) }
    }

    /**
     * Release the binder reference but **keep the daemon alive** (`remove =
     * false`) so the next bind reuses the warm process. Pass `remove = true`
     * to tear it down — used during uninstall flows or version mismatch.
     */
    fun unbind(remove: Boolean = false) {
        runCatching { Shizuku.unbindUserService(args, conn, remove) }
        _service.value = null
    }

    companion object {
        private const val REQUEST_CODE = 0xCA11
        // Must match userservice/build.gradle.kts → userServiceVersion.
        // Bump together when the AIDL contract or service semantics change.
        private const val USER_SERVICE_VERSION = 10
    }
}
