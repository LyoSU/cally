// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Runtime permissions our recorder needs to actually function. Shizuku grant
 * is tracked separately — that's a service permission, not an Android one.
 *
 *  - RECORD_AUDIO — calling-attribution chain still requires us to hold it.
 *  - POST_NOTIFICATIONS — without it the FG service runs but silently.
 *  - READ_PHONE_STATE — only way to auto-detect OFFHOOK transitions.
 *  - READ_CALL_LOG — post-mortem lookup of the dialled number at call-end.
 *    PHONE_STATE.EXTRA_INCOMING_NUMBER is redacted on Android 9+ for
 *    non-default-dialers, so CallLog is the only route to a number on
 *    outgoing calls.
 *  - READ_CONTACTS — resolves the number to a display name via
 *    `ContactsContract.PhoneLookup`. Without it we'd only show the digits.
 */
object AppPermissions {
    val RECORD_AUDIO = Manifest.permission.RECORD_AUDIO
    val POST_NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS
    val READ_PHONE_STATE = Manifest.permission.READ_PHONE_STATE
    val READ_CALL_LOG = Manifest.permission.READ_CALL_LOG
    val READ_CONTACTS = Manifest.permission.READ_CONTACTS

    /** Permissions we actively ask for during onboarding. */
    val essential: List<String> = listOf(
        RECORD_AUDIO,
        POST_NOTIFICATIONS,
        READ_PHONE_STATE,
        READ_CALL_LOG,
        READ_CONTACTS,
    )

    /** True iff all [essential] permissions are granted. */
    fun allGranted(ctx: Context): Boolean = essential.all { isGranted(ctx, it) }

    fun isGranted(ctx: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    /** True iff any essential permission is currently denied. */
    fun missing(ctx: Context): List<String> =
        essential.filterNot { isGranted(ctx, it) }
}
