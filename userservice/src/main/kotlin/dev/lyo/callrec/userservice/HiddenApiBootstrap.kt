// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.userservice

import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Removes the Android P+ hidden-API restriction inside the Shizuku UserService
 * process. We need this to reflectively touch `android.app.ActivityThread`
 * (for a system Context) and several `AudioRecord` internals on certain HALs.
 *
 * Idempotent — safe to call from every Stub method.
 */
internal object HiddenApiBootstrap {
    @Volatile private var enabled = false

    fun enable() {
        if (enabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Scoped to the framework prefixes we actually reflect into.
            // The earlier `addHiddenApiExemptions("")` exempted the entire
            // platform — defence-in-depth: if any future code path ever
            // gets attacker-influenced into this UID-2000 process, narrower
            // scope = smaller attack surface.
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/app/",
                "Landroid/media/",
                "Landroid/content/",
                "Landroid/os/",
                "Landroid/permission/",
            )
        }
        enabled = true
    }
}
