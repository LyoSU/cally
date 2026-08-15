// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.telephony

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.getSystemService
import dev.lyo.callrec.core.L

/**
 * Tells a real SIM call apart from a third-party VoIP call.
 *
 * `ACTION_PHONE_STATE_CHANGED` is not telephony-exclusive: Telecom also
 * broadcasts it for calls registered through a self-managed
 * `ConnectionService`, which is what Discord, WhatsApp, Telegram and Signal
 * have used since Android 9. [CallStateReceiver] therefore wakes up for those
 * too, and used to start a full auto-record session for them.
 *
 * That is worse than an unwanted file. The auto-record ladder probes
 * `VOICE_CALL` / `VOICE_UPLINK` / `VOICE_DOWNLINK` — audio sources carrying
 * the *modem* voice path, which is silent by construction during a VoIP call.
 * The ladder walks every strategy, captures silence, and writes the verdict
 * into the device capability cache as `knownSilent`, so one WhatsApp call can
 * degrade the next genuine one. Voice-memo mode is the supported way to
 * record anything that is not a SIM call.
 *
 * The discriminator is per-subscription telephony state: it knows nothing
 * about self-managed calls and stays `CALL_STATE_IDLE` for their whole
 * duration, while Telecom broadcasts around it.
 */
object CellularCall {

    /**
     * True when at least one SIM reports a ringing or off-hook call.
     *
     * Every failure path answers `true`. A stray VoIP recording is a nuisance
     * the user can delete; a silently skipped real call is the app failing at
     * the one job it has — so uncertainty resolves towards recording.
     */
    fun isActive(ctx: Context): Boolean {
        val tm = ctx.getSystemService<TelephonyManager>() ?: return true
        return runCatching {
            val subIds = activeSubscriptionIds(ctx)
            if (subIds.isEmpty()) {
                // Subscription list unreadable (permission revoked mid-flight,
                // OEM quirk). The default-subscription reading is less correct
                // on dual-SIM but strictly better than giving up.
                return@runCatching tm.callStateForSubscription != TelephonyManager.CALL_STATE_IDLE
            }
            // Per-SIM rather than the default subscription: on a dual-SIM
            // device a call on the non-default SIM leaves the default one
            // IDLE, and gating on that would drop half of real calls.
            subIds.any { subId ->
                tm.createForSubscriptionId(subId).callStateForSubscription !=
                    TelephonyManager.CALL_STATE_IDLE
            }
        }.getOrElse {
            L.w("Receiver", "cellular probe failed (${it.javaClass.simpleName}) — assuming SIM call")
            true
        }
    }

    private fun activeSubscriptionIds(ctx: Context): List<Int> = runCatching {
        ctx.getSystemService<SubscriptionManager>()
            ?.activeSubscriptionInfoList
            ?.map { it.subscriptionId }
            .orEmpty()
    }.getOrDefault(emptyList())
}
