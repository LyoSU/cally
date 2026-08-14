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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import rikka.shizuku.Shizuku

class ShizukuClient(private val ctx: Context) {

    private val _health = MutableStateFlow<DaemonHealth>(DaemonHealth.NotRunning)
    val health: StateFlow<DaemonHealth> = _health

    /** Backward-compat accessor used by existing callers (CallMonitorService, RecorderController). */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    val service: StateFlow<IRecorderService?> =
        _health.map { (it as? DaemonHealth.Bound)?.service }
            .stateIn(GlobalScope, SharingStarted.Eagerly, null)

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
                L.w("Shizuku", "Daemon stale — unbinding with remove=true")
                runCatching { Shizuku.unbindUserService(args, this, /* remove = */ true) }
                _health.value = DaemonHealth.Stale
                return
            }
            _health.value = DaemonHealth.Bound(svc)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            L.w("Shizuku", "onServiceDisconnected")
            recompute()
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        recompute()
        if (result == PackageManager.PERMISSION_GRANTED) {
            permissionContinuation?.resume(true)
        } else {
            permissionContinuation?.resume(false)
        }
        permissionContinuation = null
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { recompute() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { recompute() }

    @Volatile private var permissionContinuation: kotlin.coroutines.Continuation<Boolean>? = null

    fun attach() {
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        recompute()
    }

    fun detach() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    /** Re-derive [health] from current Shizuku/binding state. Idempotent; safe to call often. */
    fun recompute() {
        val current = _health.value
        // If we have a live Bound, keep it — onServiceDisconnected will replace.
        if (current is DaemonHealth.Bound) return

        val s = when {
            // Distinguish "Shizuku app not installed" from "installed but not
            // running" — the notification + onboarding card render different
            // copy and CTA for each. pingBinder() returns false in both
            // cases, so we probe PackageManager to disambiguate.
            !Shizuku.pingBinder() -> if (isShizukuInstalled()) DaemonHealth.NotRunning else DaemonHealth.NotInstalled
            @Suppress("DEPRECATION") Shizuku.isPreV11() -> DaemonHealth.NotRunning
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> DaemonHealth.NoPermission
            else -> {
                runCatching { Shizuku.bindUserService(args, conn) }
                    .onFailure { L.e("Shizuku", "bindUserService threw", it) }
                current  // keep current until onServiceConnected fires
            }
        }
        if (_health.value != s) {
            L.i("Shizuku", "health ${_health.value} → $s")
            _health.value = s
        }
    }

    /** Verify a currently-Bound daemon still answers AIDL. Call from RESUMED lifecycle. */
    suspend fun verifyHealth() {
        val bound = _health.value as? DaemonHealth.Bound ?: return
        val ok = withContext(Dispatchers.IO) {
            runCatching { bound.service.version == USER_SERVICE_VERSION }.getOrDefault(false)
        }
        if (!ok) {
            L.w("Shizuku", "verifyHealth ping failed — marking Unhealthy")
            _health.value = DaemonHealth.Unhealthy("ping failed")
        }
    }

    suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { cont ->
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            cont.resume(true); return@suspendCancellableCoroutine
        }
        permissionContinuation = cont
        Shizuku.requestPermission(REQUEST_CODE)
        cont.invokeOnCancellation { permissionContinuation = null }
    }

    fun bind() = recompute()

    fun unbind(remove: Boolean = false) {
        runCatching { Shizuku.unbindUserService(args, conn, remove) }
        // Voluntarily dropping our own binding doesn't mean permission was
        // revoked — re-check checkSelfPermission() instead of assuming
        // NoPermission, or every call-end falsely re-triggers the
        // "permission needed" notification/onboarding prompt.
        _health.value = when {
            !Shizuku.pingBinder() -> DaemonHealth.NotRunning
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> DaemonHealth.NoPermission
            else -> DaemonHealth.NotRunning
        }
    }

    fun refresh() = recompute()

    /**
     * Cheap PackageManager probe used by [recompute] to tell `NotInstalled`
     * apart from `NotRunning`. The manifest declares `<queries>` for the
     * canonical `moe.shizuku.privileged.api` package — without it,
     * Android 11+ would return NameNotFoundException even on installed
     * devices.
     */
    private fun isShizukuInstalled(): Boolean = runCatching {
        ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
    }.isSuccess

    companion object {
        private const val REQUEST_CODE = 0xCA11
        private const val USER_SERVICE_VERSION = 12
    }
}
