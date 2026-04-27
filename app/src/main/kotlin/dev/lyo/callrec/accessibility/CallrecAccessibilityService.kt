// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Empty stub.
 *
 * The service is declared in the manifest *only* so the user can flip it on
 * in Settings → Accessibility, which is one of the platform-recognised
 * exemptions for "FGS of type=microphone started from background". We do
 * NOT actually use accessibility for telephony observation — that's done
 * by [dev.lyo.callrec.telephony.CallStateReceiver] (manifest broadcast).
 *
 * This service does nothing while running. It deliberately doesn't read
 * window content, doesn't track gestures, doesn't spy on anything.
 *
 * As of v0.2 it is **optional** — the overlay-trick path
 * (`OverlayTrick.briefly`) is what we actually rely on now. Kept declared so
 * users on aggressive OEMs (Xiaomi/MIUI etc.) can enable it as a fallback.
 */
class CallrecAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }
    override fun onInterrupt() { /* no-op */ }
}
