// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.ui

import android.app.Activity
import android.os.Bundle

/**
 * Transparent zero-UI activity launched from `CallMonitorService.onTaskRemoved`.
 *
 * The trick: when the user swipes us from Recents the OS demotes the process
 * out of foreground and our FGS becomes a candidate for early reaping. Briefly
 * launching this Activity keeps the process foreground long enough for the
 * service to stabilise. We finish in `onCreate` so nothing is shown.
 */
class DummyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
