// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.core

import android.util.Log
import dev.lyo.callrec.BuildConfig

/**
 * Centralised logging shim. All callsites use a single tag `Callrec` so the
 * developer can `adb logcat -s Callrec` to see only our chatter without
 * grepping. In release builds verbose/debug levels are no-ops via R8 inlining.
 */
object L {
    private const val TAG = "Callrec"

    fun v(component: String, msg: String) {
        if (BuildConfig.DEBUG) Log.v(TAG, "[$component] $msg")
    }

    fun d(component: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "[$component] $msg")
    }

    fun i(component: String, msg: String) {
        Log.i(TAG, "[$component] $msg")
    }

    fun w(component: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.w(TAG, "[$component] $msg", t)
        else Log.w(TAG, "[$component] $msg")
    }

    fun e(component: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, "[$component] $msg", t)
        else Log.e(TAG, "[$component] $msg")
    }
}
