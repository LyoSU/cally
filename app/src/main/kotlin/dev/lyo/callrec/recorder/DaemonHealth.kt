// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.recorder

import dev.lyo.callrec.aidl.IRecorderService

/**
 * Single source of truth for the privileged-recorder pipeline state, observed
 * from the app process. Combines Shizuku availability, permission, and daemon
 * binding into one composite signal (previously split across a coarse enum and
 * a separate `service: StateFlow<IRecorderService?>` flow).
 *
 * Drivers (in [ShizukuClient]):
 *   - `Shizuku.pingBinder()` distinguishes [NotInstalled]/[NotRunning]
 *   - `Shizuku.checkSelfPermission()` distinguishes [NoPermission]
 *   - `ServiceConnection.onServiceConnected` carries the live binder for [Bound]
 *   - daemon `getVersion()` mismatch yields [Stale]
 *   - daemon `getVersion()` throws yields [Unhealthy]
 *   - a voluntary `unbind()` between calls yields [Idle]
 */
sealed interface DaemonHealth {
    /** Shizuku app not installed on device. */
    data object NotInstalled : DaemonHealth
    /** Shizuku installed but not running. */
    data object NotRunning : DaemonHealth
    /** Shizuku running but our package has not been granted permission. */
    data object NoPermission : DaemonHealth
    /** Daemon binder alive but its `getVersion` differs from our build constant. */
    data object Stale : DaemonHealth
    /** Happy path: daemon bound, version matches, AIDL calls work. */
    data class Bound(val service: IRecorderService) : DaemonHealth
    /** Daemon process alive but AIDL calls fail (zombie / partial crash). */
    data class Unhealthy(val reason: String) : DaemonHealth

    /**
     * Shizuku is installed, running, and has granted us permission — we
     * simply hold no binding right now. This is what the gap between two
     * calls looks like: `CallMonitorService.onDestroy` drops our
     * `ServiceConnection`, while `daemon(true)` keeps the privileged process
     * itself alive, so nothing is actually broken and the next
     * [ShizukuClient.recompute] re-binds without user involvement.
     */
    data object Idle : DaemonHealth

    /**
     * Whether this state is worth interrupting the user over — a
     * notification, a warning banner, an onboarding re-prompt.
     *
     * Every such call site used to spell this as `!is Bound`, which silently
     * classified the perfectly healthy post-call [Idle] pause as a failure
     * and fired a "permission needed" notification after every single call.
     * Route new checks through here rather than re-deriving them.
     */
    val isFault: Boolean get() = this !is Bound && this !is Idle
}
