// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.permissions

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Samsung One UI / Pixel Doze: any "background activity restriction" turns
 * an app into a frozen process whose AccessibilityService binding cannot
 * resurrect it fast enough to start a microphone-typed FG service before
 * the platform's "5-second start" check kicks in.
 *
 * The fix is user-driven: ignore-battery-optimizations exempts the app from
 * Doze, and Samsung's own Freecess freezer respects the same flag.
 */
object BatteryOptimizations {

    fun isIgnoring(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /**
     * Open the system dialog that asks the user to whitelist us. On Samsung,
     * this also surfaces the "Sleeping apps" toggle in their UX flow.
     *
     * Uses the public REQUEST_IGNORE_BATTERY_OPTIMIZATIONS action — Google
     * frowns on this for Play Store apps but our distribution is sideload
     * + GitHub Releases, where it's exactly the correct call.
     */
    @SuppressLint("BatteryLife")
    fun requestExemption(ctx: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(intent) }.isSuccess) return
        // Fallback: the per-app battery settings page where users can flip
        // the same toggle manually.
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(fallback) }
    }
}
