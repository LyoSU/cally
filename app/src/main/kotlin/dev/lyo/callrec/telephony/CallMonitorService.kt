// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.telephony

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.lyo.callrec.App
import dev.lyo.callrec.contacts.CallLogResolver
import dev.lyo.callrec.contacts.ContactResolver
import dev.lyo.callrec.core.L
import dev.lyo.callrec.notify.CompletedRecordingNotification
import dev.lyo.callrec.notify.RecordingNotification
import dev.lyo.callrec.recorder.RecorderController
import dev.lyo.callrec.storage.CallRecord
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that owns one call's recording session end-to-end.
 *
 * State machine is driven entirely by the `action` of the `Intent` it
 * receives — *no* TelephonyCallback inside the service. That avoids the race
 * where `registerTelephonyCallback` synchronously fires an initial
 * IDLE event before the real OFFHOOK arrives, which used to make the service
 * wind itself down 20 ms after starting.
 *
 *   ACTION_CALL_START   → kickoff() → RecorderController.start
 *   ACTION_CALL_END     → stopRecording + stopSelf
 *   ACTION_MANUAL_START → kickoff() (user pressed Record in the app)
 *   ACTION_MANUAL_STOP  → stop, demote
 *
 * On `onTaskRemoved` (user swiped from Recents) we launch a transparent
 * dummy activity so the process is briefly foreground again and the FGS
 * does not get reaped.
 */
class CallMonitorService : LifecycleService() {

    private val container by lazy { (application as App).container }
    private var currentCallId: String? = null
    @Volatile private var callStartedAt: Long? = null
    @Volatile private var initialNumber: String? = null
    private var manualMode: Boolean = false
    private var recordingStarted: Boolean = false

    override fun onCreate() {
        super.onCreate()
        L.i("Service", "onCreate")
        startForegroundCompat(
            RecordingNotification.build(this, RecorderController.Outcome.Failed("…")),
        )
        observeRecorderState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action ?: ACTION_CALL_START
        L.i("Service", "onStartCommand action=$action")
        when (action) {
            ACTION_MANUAL_STOP -> {
                // Must suspend on stopRecording BEFORE stopSelf — otherwise
                // the FGS dies while pump threads are still in encoder.close()
                // and AAC files end up 0 bytes (no MP4 moov atom written).
                lifecycleScope.launch {
                    stopRecordingAndAwait("user")
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_CALL_END -> {
                lifecycleScope.launch {
                    stopRecordingAndAwait("call_ended")
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_MANUAL_START -> {
                manualMode = true
                if (!recordingStarted) {
                    recordingStarted = true
                    lifecycleScope.launch { kickoff() }
                }
            }
            ACTION_CALL_START -> {
                manualMode = false
                // Empty on Android 9+ for non-default-dialer apps; CallLog
                // post-mortem in stopRecording covers that case.
                intent?.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() }?.let {
                    initialNumber = it
                }
                if (!recordingStarted) {
                    recordingStarted = true
                    lifecycleScope.launch { kickoff() }
                }
            }
        }
        // STICKY so OEM aggressive process killers respawn us with a null
        // Intent — onStartCommand will see null → action=CALL_START → safe
        // because recordingStarted guards against re-entry.
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun observeRecorderState() = lifecycleScope.launch {
        container.recorder.state.collectLatest { rs ->
            val outcome = when (rs) {
                is RecorderController.RecordingState.Active -> rs.outcome
                is RecorderController.RecordingState.Probing ->
                    RecorderController.Outcome.Failed("probing:${rs.attempting.displayName}")
                is RecorderController.RecordingState.Failed ->
                    RecorderController.Outcome.Failed(rs.reason)
                RecorderController.RecordingState.Idle ->
                    RecorderController.Outcome.Failed("…")
            }
            startForegroundCompat(RecordingNotification.build(this@CallMonitorService, outcome))

            if (rs is RecorderController.RecordingState.Active) {
                persistOutcome(rs.outcome)
            }
        }
    }

    private suspend fun kickoff() {
        L.i("Service", "kickoff() begin")
        val client = container.shizuku
        client.attach(); client.refresh(); client.bind()
        // Suspend on the StateFlow until the UserService bind completes —
        // earlier code spun a polling loop with `delay(80)` ticks that woke
        // the dispatcher 12.5 times/sec for up to 12 s. filterNotNull/first
        // is a single suspend point, no wakeups while idle.
        val svc = withTimeoutOrNull(12_000) {
            client.service.filterNotNull().first()
        }
        if (svc == null) {
            L.e("Service", "kickoff: Shizuku UserService bind timeout (12s) — bailing")
            stopSelf(); return
        }

        // Wait for the modem to actually open the audio path. On Samsung One UI
        // the OFFHOOK broadcast fires when the user *initiates* the call, but
        // AudioManager.getMode() reports NORMAL until the modem's voice path
        // is hot — usually 1–4 s later. Probing AudioRecord before that window
        // gives `state=RECORDING` with zero RMS (silent stream) which used to
        // make us blacklist the working strategy. Pixel transitions in <500 ms.
        //
        // Replaces the earlier `delay(150)` polling loop with the proper
        // change-listener API (API 30+, our minSdk = 31). Zero wakeups while
        // the modem is still warming up; we resume immediately on transition.
        if (!manualMode) {
            val am = getSystemService(android.content.Context.AUDIO_SERVICE)
                as android.media.AudioManager
            if (am.mode != android.media.AudioManager.MODE_IN_CALL &&
                am.mode != android.media.AudioManager.MODE_IN_COMMUNICATION
            ) {
                val mode = withTimeoutOrNull(6_000) {
                    awaitVoiceMode(am)
                }
                if (mode == null) {
                    L.w("Service", "kickoff: still MODE_NORMAL after 6 s — proceeding anyway")
                } else {
                    L.i("Service", "kickoff: AudioManager.mode=$mode (audio path live)")
                }
            } else {
                L.i("Service", "kickoff: AudioManager.mode already live (${am.mode})")
            }
        }

        L.i("Service", "kickoff: starting recorder")
        val callId = container.storage.newCallId()
        currentCallId = callId
        container.recorder.start(callId)
    }

    private suspend fun persistOutcome(outcome: RecorderController.Outcome) {
        val id = currentCallId ?: return
        val (mode, up, dn) = when (outcome) {
            is RecorderController.Outcome.Dual -> Triple(outcome.strategy.name, outcome.uplink.path, outcome.downlink.path)
            is RecorderController.Outcome.Single -> Triple(outcome.strategy.name, outcome.file.path, null)
            is RecorderController.Outcome.Failed -> return
        }
        val started = callStartedAt ?: System.currentTimeMillis().also { callStartedAt = it }
        val number = initialNumber?.takeIf { it.isNotBlank() }
        val name = number?.let { ContactResolver.resolveName(this, it) }
        container.db.calls().upsert(
            CallRecord(
                callId = id,
                startedAt = started,
                endedAt = null,
                contactNumber = number,
                contactName = name,
                mode = mode,
                uplinkPath = up,
                downlinkPath = dn,
            ),
        )
    }

    /**
     * Suspend until the recorder is fully stopped — pump threads joined,
     * AAC muxer finalised, MP4 moov atom written, files closed. Callers
     * MUST await this before calling stopSelf(): if the FGS dies while
     * encoders are mid-close, MediaMuxer never writes the trailer and the
     * resulting .m4a is 0 bytes (or a 0-second file that opens with ENOENT
     * because openOrCreate happened but writeSampleData never did).
     *
     * [recorder.stop] internally moves the blocking pump-join onto
     * Dispatchers.IO, so suspending here does not block the main thread.
     */
    private suspend fun stopRecordingAndAwait(reason: String) {
        L.i("Service", "stopRecording reason=$reason")
        val id = currentCallId
        val startedAt = callStartedAt
        currentCallId = null
        callStartedAt = null
        initialNumber = null
        recordingStarted = false
        val appCtx = applicationContext // capture before scope hop
        // Block #1 — must finish on lifecycleScope to keep FGS alive while
        // the encoder finalises. After this returns, files are on disk.
        val outcome = container.recorder.stop()
        if (id == null || outcome == null || outcome is RecorderController.Outcome.Failed) {
            return
        }
        // Block #2 — bookkeeping + user-visible notification. Move to
        // appScope so a `stopSelf()` from a sibling onStartCommand doesn't
        // cancel our continuation halfway through the suspend chain
        // (persistOutcomeFinal → markEnded → byId → show). lifecycleScope
        // cancels with the service; appScope outlives it.
        container.appScope.launch {
            persistOutcomeFinal(id, outcome)
            container.db.calls().markEnded(id, System.currentTimeMillis())
            // Surface the saved-recording notification so the user notices
            // a recording happened even when their phone was face-down or
            // they were speaking via headset and never glanced at the FGS
            // notification. Tap → playback for that callId.
            runCatching {
                val saved = container.db.calls().byId(id)
                if (saved == null) {
                    L.w("Service", "completed-notif: byId($id) returned null")
                } else {
                    CompletedRecordingNotification.show(appCtx, saved)
                }
            }.onFailure { L.w("Service", "completed-notif failed: ${it.message}") }
            // Post-mortem: only the system-committed CallLog row carries
            // the dialed number for outgoing calls (and for incoming on
            // Android 9+ where EXTRA_INCOMING_NUMBER is redacted).
            runCatching {
                val existing = container.db.calls().byId(id)
                if (existing?.contactNumber.isNullOrBlank() && startedAt != null) {
                    val num = CallLogResolver.mostRecentNumber(appCtx, startedAt)
                    if (!num.isNullOrBlank()) {
                        val name = ContactResolver.resolveName(appCtx, num)
                        container.db.calls().updateContact(id, num, name)
                        L.i("Service", "post-mortem contact resolved: $num / ${name ?: "<no name>"}")
                    } else {
                        L.d("Service", "post-mortem CallLog returned no row")
                    }
                }
            }.onFailure { L.w("Service", "post-mortem contact lookup failed: ${it.message}") }
        }
    }

    /**
     * Re-write mode/uplinkPath/downlinkPath after the recorder has settled
     * on its final outcome. Different from [persistOutcome] which fires on
     * the Active transition with the *initial* outcome — we may downgrade
     * Dual → Single (one side silent + file deleted) by the time we stop.
     */
    private suspend fun persistOutcomeFinal(id: String, outcome: RecorderController.Outcome) {
        val (mode, up, dn) = when (outcome) {
            is RecorderController.Outcome.Dual ->
                Triple(outcome.strategy.name, outcome.uplink.path, outcome.downlink.path)
            is RecorderController.Outcome.Single ->
                Triple(outcome.strategy.name, outcome.file.path, null)
            is RecorderController.Outcome.Failed -> return
        }
        runCatching {
            container.db.calls().updateOutcome(id, mode, up, dn)
        }.onFailure { L.w("Service", "persistOutcomeFinal failed: ${it.message}") }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // When the user swipes the app from Recents, briefly launch a
        // transparent activity so the OS keeps the process in a foreground
        // state long enough for the FGS to avoid being culled.
        // Safe even if recording is not active — DummyActivity finishes
        // immediately.
        try {
            val intent = Intent(this, dev.lyo.callrec.ui.DummyActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            L.e("Service", "onTaskRemoved DummyActivity start failed: ${e.message}")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // lifecycleScope is already cancelled by the time onDestroy runs.
        // Use appScope so the encoder finalisation actually completes — it
        // outlives the service. Not awaited (we don't have a coroutine
        // context here), so the process must stay alive a few seconds longer
        // than the service. In practice the FGS only goes through onDestroy
        // after our own stopSelf() — by which point recording.stop() has
        // already finished synchronously inside the lifecycleScope path
        // above. This launch is the safety net for OEM-killed services.
        container.appScope.launch { stopRecordingAndAwait("destroy") }
        container.shizuku.detach()
        container.shizuku.unbind(remove = false)
        super.onDestroy()
    }

    private fun startForegroundCompat(notif: android.app.Notification) {
        // foregroundServiceType=specialUse on Android 14+: bypasses the
        // microphone-FGS-from-background restriction entirely. The actual
        // microphone access happens in the Shizuku UserService process
        // (UID shell), not our app, so we don't need type=microphone.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    RecordingNotification.ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(RecordingNotification.ID, notif)
            }
        } catch (e: SecurityException) {
            L.e("Service", "startForeground denied: ${e.message}")
            stopSelf()
        }
    }

    /**
     * Suspend until [am] reports MODE_IN_CALL or MODE_IN_COMMUNICATION via
     * `OnModeChangedListener` (API 30+). Replaces a 150 ms polling loop:
     * the listener fires once when the modem actually opens the audio path,
     * and we resume immediately. Zero wakeups while waiting.
     *
     * Uses the main looper's executor (via ContextCompat.getMainExecutor)
     * instead of a fresh single-thread pool — the listener body is trivial
     * (one comparison + one resume) and pinning to main avoids leaking a
     * worker thread on every call. Earlier code created a new Executor and
     * only shutdown'd on cancellation, leaking one thread per recorded call.
     */
    private suspend fun awaitVoiceMode(am: android.media.AudioManager): Int =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val executor = androidx.core.content.ContextCompat.getMainExecutor(this)
            lateinit var listener: android.media.AudioManager.OnModeChangedListener
            listener = android.media.AudioManager.OnModeChangedListener { mode ->
                if (mode == android.media.AudioManager.MODE_IN_CALL ||
                    mode == android.media.AudioManager.MODE_IN_COMMUNICATION
                ) {
                    runCatching { am.removeOnModeChangedListener(listener) }
                    if (cont.isActive) cont.resumeWith(Result.success(mode))
                }
            }
            am.addOnModeChangedListener(executor, listener)
            cont.invokeOnCancellation {
                runCatching { am.removeOnModeChangedListener(listener) }
            }
            // Re-check after registering — modem may have transitioned
            // between our first read and listener registration.
            val now = am.mode
            if (now == android.media.AudioManager.MODE_IN_CALL ||
                now == android.media.AudioManager.MODE_IN_COMMUNICATION
            ) {
                runCatching { am.removeOnModeChangedListener(listener) }
                if (cont.isActive) cont.resumeWith(Result.success(now))
            }
        }

    companion object {
        const val ACTION_MANUAL_START = "dev.lyo.callrec.action.MANUAL_START"
        const val ACTION_MANUAL_STOP = "dev.lyo.callrec.action.MANUAL_STOP"
        const val ACTION_CALL_START = "dev.lyo.callrec.action.CALL_START"
        const val ACTION_CALL_END = "dev.lyo.callrec.action.CALL_END"

        const val EXTRA_PHONE_STATE = "phone_state"
        const val EXTRA_NUMBER = "number"
    }
}
