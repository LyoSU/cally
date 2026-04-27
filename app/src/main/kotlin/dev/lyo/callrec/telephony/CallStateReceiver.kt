// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dev.lyo.callrec.App
import dev.lyo.callrec.core.L
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Manifest-registered trigger for auto-record.
 *
 * Captures the state from the system broadcast extras and passes it
 * explicitly into [CallMonitorService] via intent action + extras. The
 * service does NOT subscribe to a TelephonyCallback (we'd lose the race
 * against the initial-IDLE callback the platform fires synchronously on
 * register), it just trusts whatever this receiver dispatched.
 *
 * Before each `startForegroundService(type=microphone)` we briefly add an
 * invisible overlay window so the OS promotes the process to a foreground
 * state — that's how we bypass the Android 14+ FGS-from-background
 * restriction. Requires SYSTEM_ALERT_WINDOW.
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        L.i("Receiver", "PHONE_STATE → $state${number?.let { " ($it)" } ?: ""}")
        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK,
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // Battery: don't pay the overlay-promote + Shizuku-bind cost
                // when auto-record is OFF. The user's only path to record is
                // then the manual mic button → that route already starts the
                // service explicitly, no PHONE_STATE wakeup needed.
                //
                // Use container.appScope so we don't leak a fresh
                // CoroutineScope per broadcast — the previous local
                // `CoroutineScope(Dispatchers.IO)` was never cancelled.
                val pending = goAsync()
                val container = (ctx.applicationContext as App).container
                container.appScope.launch {
                    val auto = runCatching { container.settings.autoRecord.first() }
                        .getOrDefault(true)
                    try {
                        if (!auto) {
                            L.d("Receiver", "auto-record OFF — skip FGS for $state")
                            return@launch
                        }
                        // Pre-promote the process to a foreground UI state
                        // via the overlay trick — without it, Android 14+
                        // rejects FGS_TYPE start from a manifest receiver.
                        // Cheap (1×1 px, transparent, removed after ~3 s).
                        OverlayTrick.briefly(ctx)
                        val svc = Intent(ctx, CallMonitorService::class.java).apply {
                            action = CallMonitorService.ACTION_CALL_START
                            putExtra(CallMonitorService.EXTRA_PHONE_STATE, state)
                            if (number != null) putExtra(CallMonitorService.EXTRA_NUMBER, number)
                        }
                        runCatching { ContextCompat.startForegroundService(ctx, svc) }
                            .onFailure { L.e("Receiver", "startForegroundService failed: ${it.message}") }
                    } finally {
                        // Release the goAsync hold whether we started FGS or
                        // skipped — without finish() the system thinks the
                        // receiver is still working and delays the next one.
                        pending.finish()
                    }
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                // IDLE → tell the service to wind down. Idempotent: if the
                // service was never started (auto-record OFF), this is a
                // cheap no-op intent dispatch.
                val svc = Intent(ctx, CallMonitorService::class.java).apply {
                    action = CallMonitorService.ACTION_CALL_END
                }
                runCatching { ctx.startService(svc) }
                    .onFailure { L.d("Receiver", "startService(end) noop: ${it.message}") }
            }
        }
    }
}
