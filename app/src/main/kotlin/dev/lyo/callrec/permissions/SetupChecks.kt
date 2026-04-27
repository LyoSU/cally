// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.permissions

import android.content.Context
import android.provider.Settings
import dev.lyo.callrec.di.AppContainer
import dev.lyo.callrec.recorder.ShizukuState

/**
 * Single source of truth for "is the app set up well enough to record?".
 *
 * The mandatory prerequisites are:
 *   - Shizuku ready (UserService can be bound)
 *   - Runtime permissions (RECORD_AUDIO, READ_PHONE_STATE, POST_NOTIFICATIONS)
 *   - canDrawOverlays (SYSTEM_ALERT_WINDOW) — required for the auto-record
 *     path: the OverlayTrick briefly adds an invisible window so Android 14+
 *     allows starting a foreground service of type=microphone from a
 *     BroadcastReceiver. Without it auto-detect is dead in the water on
 *     Pixel/Samsung.
 *   - Battery exemption — without this Doze/Freecess kills our FGS partway
 *     through long calls.
 *
 * Accessibility is **optional** as of v0.2. We keep the field on this struct
 * so the UI can show an "Optional fallback" row, but [allReady] does not
 * include it.
 */
data class SetupStatus(
    val shizukuReady: Boolean,
    val runtimePermsGranted: Boolean,
    val overlayGranted: Boolean,
    val batteryExempt: Boolean,
    val accessibilityEnabled: Boolean,
) {
    val allReady: Boolean get() =
        shizukuReady && runtimePermsGranted && overlayGranted && batteryExempt

    companion object {
        fun probe(ctx: Context, container: AppContainer): SetupStatus {
            container.shizuku.refresh()
            return SetupStatus(
                shizukuReady = container.shizuku.state.value == ShizukuState.Ready,
                runtimePermsGranted = AppPermissions.allGranted(ctx),
                overlayGranted = Settings.canDrawOverlays(ctx),
                batteryExempt = BatteryOptimizations.isIgnoring(ctx),
                accessibilityEnabled = dev.lyo.callrec.accessibility.AccessibilityHelper.isEnabled(ctx),
            )
        }
    }
}
