// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.accessibility

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils

/**
 * Lightweight check for whether the user has enabled our (empty)
 * AccessibilityService in Settings → Accessibility.
 *
 * As of v0.2 this is purely informational — the auto-record flow no longer
 * depends on an active accessibility service (we use the overlay trick to
 * bypass the FGS-from-background restriction). [SetupStatus]
 * still surfaces the boolean so users on aggressive OEMs (Xiaomi/MIUI) can
 * enable accessibility as a hardening fallback if the overlay path proves
 * unreliable on their device.
 */
object AccessibilityHelper {
    fun isEnabled(ctx: Context): Boolean {
        val expected = ComponentName(ctx, CallrecAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        if (enabled.isEmpty()) return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        for (entry in splitter) {
            if (entry.equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
