// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.telephony

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dev.lyo.callrec.core.L

/**
 * FGS-from-background bypass.
 *
 * Android 14+ blocks `startForegroundService(type=microphone)` from a manifest
 * BroadcastReceiver because the process is in PROCESS_STATE_RECEIVER, not
 * foreground. The official escape hatch is one of: an Activity in foreground,
 * an overlay window, an AccessibilityService, or a SYSTEM_ALERT_WINDOW grant
 * that's actively in use.
 *
 * We pick the cheapest option: briefly add a 1×1 px transparent overlay
 * through WindowManager. The system promotes the process to a foreground
 * state for as long as the overlay exists, which is enough to legally call
 * `startForegroundService(type=microphone)`. After ~3 s the overlay is
 * removed; the FGS is now alive in its own right and survives.
 *
 * Caller flow:
 *   if (OverlayTrick.canShow(ctx)) OverlayTrick.briefly(ctx)
 *   ContextCompat.startForegroundService(ctx, intent)
 */
object OverlayTrick {

    private const val LIFETIME_MS = 3_000L

    /** True if `SYSTEM_ALERT_WINDOW` was granted by the user. */
    fun canShow(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /**
     * Add an invisible overlay synchronously and schedule its removal. Safe
     * to call from a BroadcastReceiver. No-ops if the permission is missing.
     */
    fun briefly(ctx: Context) {
        if (!canShow(ctx)) {
            L.w("Overlay", "canDrawOverlays=false — skipping (FGS will likely be denied)")
            return
        }
        val app = ctx.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = View(app).apply {
            setBackgroundColor(Color.argb(1, 0, 0, 0)) // alpha=1 → essentially invisible
        }
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE | NOT_TOUCHABLE | LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }
        runCatching { wm.addView(view, lp) }
            .onSuccess { L.d("Overlay", "added invisible 1×1 overlay (FGS bypass)") }
            .onFailure {
                L.e("Overlay", "addView failed: ${it.message}")
                return
            }
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { wm.removeView(view) }
                .onSuccess { L.d("Overlay", "removed overlay") }
        }, LIFETIME_MS)
    }
}
