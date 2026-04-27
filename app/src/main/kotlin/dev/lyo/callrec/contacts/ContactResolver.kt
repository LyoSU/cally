// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.contacts

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import dev.lyo.callrec.core.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Looks up the display name for a phone number via PhoneLookup. PhoneLookup
// matches against multiple stored formats (E.164, national, partial) so we
// don't have to normalise heavily — but we still strip separators in case
// some ROMs are picky.
object ContactResolver {

    suspend fun resolveName(ctx: Context, e164OrLocal: String): String? = withContext(Dispatchers.IO) {
        if (e164OrLocal.isBlank()) return@withContext null
        val cleaned = normalize(e164OrLocal)
        runCatching {
            val uri: Uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(cleaned),
            )
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrElse {
            // READ_CONTACTS may not be granted at runtime — degrade gracefully.
            L.w("Contacts", "PhoneLookup failed: ${it.message}")
            null
        }
    }

    suspend fun resolvePhotoUri(ctx: Context, e164OrLocal: String): String? = withContext(Dispatchers.IO) {
        if (e164OrLocal.isBlank()) return@withContext null
        val cleaned = normalize(e164OrLocal)
        runCatching {
            val uri: Uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(cleaned),
            )
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrElse {
            L.w("Contacts", "PhotoLookup failed: ${it.message}")
            null
        }
    }

    fun normalize(raw: String): String = PhoneNumberUtils.stripSeparators(raw) ?: raw
}
