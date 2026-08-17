// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.recorder

import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import dev.lyo.callrec.codec.AacEncoder
import dev.lyo.callrec.codec.AudioLevelMeter
import dev.lyo.callrec.codec.PcmEncoder
import dev.lyo.callrec.codec.WavEncoder
import dev.lyo.callrec.core.L
import dev.lyo.callrec.settings.RecordingFormat
import dev.lyo.callrec.storage.RecordingFile
import dev.lyo.callrec.storage.RecordingStorage
import java.io.FileInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Smart recorder. Combines a hard-coded fallback ladder with an in-call live
 * verification loop:
 *
 *   1. Pick a [Strategy]. Order: cached preferred → ladder, skipping
 *      `knownSilent` and `knownFailedInit`.
 *   2. Open the AudioRecord(s) via Shizuku UserService.
 *   3. Pump PCM into the encoder, **and** through an [AudioLevelMeter]
 *      that updates [levels] for the UI in real time.
 *   4. After [SILENCE_GRACE_MS] of contiguous silence on EVERY active
 *      track, mark the strategy as silent in [CapabilitiesStore], stop, and
 *      try the next strategy automatically.
 *
 * The cache means once we've found the working strategy on this device, the
 * next call hits it on the first try — no audible burning of the ladder.
 */
class RecorderController(
    private val client: ShizukuClient,
    private val storage: RecordingStorage,
    private val capabilities: CapabilitiesStore,
    private val scope: CoroutineScope,
    private var sampleRate: Int = 16_000,
    private val formatProvider: () -> RecordingFormat = { RecordingFormat.AAC },
) {

    /** Picks `wav`/`m4a` and the matching encoder based on the current setting. */
    private fun currentExt(): String = when (formatProvider()) {
        RecordingFormat.WAV -> "wav"
        RecordingFormat.AAC -> "m4a"
    }
    private fun newEncoder(file: RecordingFile): PcmEncoder = when (formatProvider()) {
        RecordingFormat.WAV -> WavEncoder(file)
        RecordingFormat.AAC -> AacEncoder(file)
    }

    sealed interface Outcome {
        data class Dual(val uplink: RecordingFile, val downlink: RecordingFile, val strategy: Strategy) : Outcome
        data class Single(val file: RecordingFile, val strategy: Strategy) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    sealed interface RecordingState {
        data object Idle : RecordingState
        data class Probing(val attempting: Strategy) : RecordingState
        data class Active(val outcome: Outcome) : RecordingState
        data class Failed(val reason: String) : RecordingState
    }

    /** Live level snapshot. UI reads this to draw the waveform bars. */
    data class Levels(
        val uplinkRms: Float = 0f,
        val downlinkRms: Float = 0f,
        /** True iff there's an active downlink/remote-side track. */
        val hasDownlink: Boolean = false,
        val totalFrames: Long = 0L,
        /**
         * Voice-memo session — there is no remote party at all. UI suppresses
         * the second meter row entirely (instead of greying it out) so the
         * user is not led to believe "the other side exists but is silent".
         */
        val voiceMemo: Boolean = false,
    )

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state

    private val _levels = MutableStateFlow(Levels())
    val levels: StateFlow<Levels> = _levels

    private val pumpThreads = mutableListOf<Thread>()
    private var watchdog: Job? = null

    @Volatile private var uplinkMeter: AudioLevelMeter? = null
    @Volatile private var downlinkMeter: AudioLevelMeter? = null
    @Volatile private var activeOutcome: Outcome? = null
    @Volatile private var stopping = false

    fun setSampleRate(hz: Int) { sampleRate = hz }

    /**
     * Starts the most likely strategy and verifies it produces audible PCM.
     * If the verification fails, automatically rolls down the ladder.
     *
     * The whole flow runs on [scope] — the call site fires-and-forgets and
     * subscribes to [state] / [levels].
     */
    fun start(callId: String, voiceMemo: Boolean = false) {
        scope.launch { startInternal(callId, voiceMemo) }
    }

    private suspend fun startInternal(callId: String, voiceMemo: Boolean) {
        if (activeOutcome != null) { L.w("Recorder", "start() while already active — ignored"); return }
        val svc = client.service.value
        if (svc == null) {
            L.e("Recorder", "UserService not bound — Shizuku binding race?")
            _state.value = RecordingState.Failed("UserService not bound")
            return
        }
        stopping = false
        // Voice-memo mode is mic-only by design: no call → no remote-side
        // audio path → dual/voicecall strategies will all fall through to
        // SingleMic anyway, just slower and with a misleading "downlink
        // silent" UI flash. Surface that intent to the UI immediately so the
        // status banner hides the second meter row before levels arrive.
        _levels.value = Levels(voiceMemo = voiceMemo)
        // Bypass health gate — refuse early on Failed (avoids cache poison),
        // proceed but don't mutate cache on Degraded, normal flow on Full.
        val bypassHealth = try {
            svc.bypassHealth
        } catch (_: android.os.RemoteException) {
            L.e("Recorder", "bypassHealth IPC failed — daemon unreachable")
            _state.value = RecordingState.Failed("daemon unreachable")
            return
        }
        if (bypassHealth == 0 /* Failed */) {
            L.e("Recorder", "Bypass non-functional on ${android.os.Build.FINGERPRINT}")
            _state.value = RecordingState.Failed("Bypass non-functional on this build")
            return
        }
        val mutateCache = bypassHealth == 2 /* Full */

        val caps = capabilities.current() ?: Capabilities(android.os.Build.FINGERPRINT, null)
        L.i("Recorder", "caps preferred=${caps.preferredStrategy?.name ?: "-"} silent=${caps.knownSilent.map { it.name }} failedInit=${caps.knownFailedInit.map { it.name }}")
        if (voiceMemo) {
            // No call audio path — pin to MIC and skip both the device
            // capability cache and the silence watchdog (mic ambient is
            // expected to be quiet at start).
            L.i("Recorder", "voiceMemo=true → MIC-only ladder")
            startVoiceMemo(svc, callId)
            return
        }
        val ladder = orderedLadder(caps).ifEmpty {
            // All strategies blacklisted — likely the cache got polluted from
            // prior attempts that failed for transient reasons (Shizuku not
            // bound yet, etc). Fall back to the full natural ladder rather
            // than refuse — better to retry than to declare the device dead.
            L.w("Recorder", "ladder filter emptied — using full natural list as last resort")
            capabilities.update { it.copy(knownFailedInit = emptySet(), knownSilent = emptySet()) }
            Strategy.values().toList()
        }
        L.i("Recorder", "callId=$callId ladder=${ladder.map { it.name }}")

        for (strategy in ladder) {
            if (stopping) return
            _state.value = RecordingState.Probing(strategy)
            L.d("Recorder", "trying ${strategy.name}…")
            when (val result = openStrategy(svc, callId, strategy)) {
                is OpenResult.Transient -> {
                    L.w("Recorder", "${strategy.name} TRANSIENT: ${result.reason}")
                    _state.value = RecordingState.Failed(result.reason)
                    return  // bail without cache mutation
                }
                is OpenResult.InitFailure -> {
                    L.w("Recorder", "${strategy.name} INIT_FAIL: ${result.reason}")
                    if (mutateCache) {
                        capabilities.update { it.copy(knownFailedInit = it.knownFailedInit + strategy) }
                    }
                    continue
                }
                is OpenResult.Success -> {
                    L.i("Recorder", "${strategy.name} opened → verifying audibility")
                    // Strategy is open — kick off pumps and watch for silence.
                    adopt(result.outcome)
                    val verdict = waitForSignalOrSilence()
                    when (verdict) {
                        Verdict.Audible -> {
                            L.i("Recorder", "${strategy.name} AUDIBLE — adopting (uplink=${uplinkMeter?.lastRms} dn=${downlinkMeter?.lastRms})")
                            if (mutateCache) {
                                capabilities.update {
                                    it.copy(
                                        preferredStrategy = strategy,
                                        // Recovery: this strategy worked, drop it from
                                        // silence list and reset its strike count.
                                        knownSilent = it.knownSilent - strategy,
                                        silentStrikes = it.silentStrikes - strategy,
                                    )
                                }
                            }
                            return
                        }
                        Verdict.Silent -> {
                            L.w("Recorder", "${strategy.name} SILENT for ${SILENCE_GRACE_MS}ms (uplink=${uplinkMeter?.lastRms} dn=${downlinkMeter?.lastRms})")
                            // Special case: SingleMic is the guaranteed last resort.
                            if (strategy == Strategy.SingleMic) {
                                L.i("Recorder", "SingleMic ambient was quiet but adopting anyway (last-resort)")
                                if (mutateCache) {
                                    capabilities.update { it.copy(preferredStrategy = strategy) }
                                }
                                return
                            }
                            // Tell the daemon to stop+release this AudioRecord BEFORE
                            // we drain the pumps. Without this the next strategy fails
                            // with `state=3 not IDLE` because the previous AudioRecord
                            // is still in RECORDSTATE_RECORDING.
                            runCatching { svc.stop() }
                            // Increment the consecutive-silent counter; only blacklist
                            // after SILENT_STRIKES_BEFORE_BLACKLIST in a row. One-off
                            // silence on Samsung means "modem hadn't opened the path
                            // yet" — not a permanent capability gap.
                            if (mutateCache) {
                                capabilities.update { caps ->
                                    val nextStrikes = (caps.silentStrikes[strategy] ?: 0) + 1
                                    val updated = caps.silentStrikes + (strategy to nextStrikes)
                                    caps.copy(
                                        silentStrikes = updated,
                                        knownSilent = if (nextStrikes >= Capabilities.SILENT_STRIKES_BEFORE_BLACKLIST)
                                            caps.knownSilent + strategy else caps.knownSilent,
                                    )
                                }
                            }
                            teardown()
                            // Drop the just-written silent files — only a couple of
                            // seconds of zero PCM, not worth keeping.
                            when (val out = activeOutcome) {
                                is Outcome.Dual -> {
                                    runCatching { java.io.File(out.uplink.path).delete() }
                                    runCatching { java.io.File(out.downlink.path).delete() }
                                }
                                is Outcome.Single -> runCatching { java.io.File(out.file.path).delete() }
                                else -> Unit
                            }
                            activeOutcome = null
                            // loop continues to next strategy
                        }
                        Verdict.UserStopped -> return
                    }
                }
            }
        }
        _state.value = RecordingState.Failed("All strategies returned silence")
    }

    private suspend fun startVoiceMemo(
        svc: dev.lyo.callrec.aidl.IRecorderService,
        callId: String,
    ) {
        _state.value = RecordingState.Probing(Strategy.SingleMic)
        when (val result = openStrategy(svc, callId, Strategy.SingleMic)) {
            is OpenResult.Transient -> {
                L.w("Recorder", "voice-memo TRANSIENT: ${result.reason}")
                _state.value = RecordingState.Failed(result.reason)
            }
            is OpenResult.InitFailure -> {
                L.w("Recorder", "voice-memo INIT_FAIL: ${result.reason}")
                _state.value = RecordingState.Failed(result.reason)
            }
            is OpenResult.Success -> {
                adopt(result.outcome)
                startWatchdog()
            }
        }
    }

    /**
     * Builds the strategy try-order:
     *   - Start with the cached preferred (if any), bumped to the front.
     *   - Then default ladder order.
     *   - Drop strategies known to fail init or known silent.
     */
    private fun orderedLadder(caps: Capabilities): List<Strategy> {
        val skipFailed = caps.knownFailedInit
        val skipSilent = caps.knownSilent
        val natural = Strategy.values().toList()
        val withPreferred = caps.preferredStrategy
            ?.let { listOf(it) + natural.filter { s -> s != it } }
            ?: natural
        val usable = withPreferred.filterNot { it in skipFailed }
        // If everything is in `knownSilent`, give it one more chance: silent
        // history may be stale (HAL behaviour can flip after BT switching).
        return if (usable.any { it !in skipSilent }) usable.filterNot { it in skipSilent } else usable
    }

    private fun openStrategy(
        svc: dev.lyo.callrec.aidl.IRecorderService,
        callId: String,
        strategy: Strategy,
    ): OpenResult = try {
        val outcome = if (strategy.isDual) tryDual(svc, callId, strategy)
                      else trySingle(svc, callId, strategy)
        when {
            outcome != null -> OpenResult.Success(outcome)
            else -> OpenResult.InitFailure(runCatching { svc.lastError }.getOrNull().orEmpty().ifEmpty { "init failed" })
        }
    } catch (_: android.os.DeadObjectException) {
        OpenResult.Transient("daemon disconnected")
    } catch (e: android.os.RemoteException) {
        OpenResult.Transient("remote IPC: ${e.message}")
    } catch (e: SecurityException) {
        // Permanent: signing pin mismatch in daemon. Surface loud, do NOT cache.
        L.e("Recorder", "verifyCaller rejected — release signature mismatch?", e)
        OpenResult.Transient("signature mismatch — reinstall required")
    }

    private fun tryDual(
        svc: dev.lyo.callrec.aidl.IRecorderService,
        callId: String,
        strategy: Strategy,
    ): Outcome.Dual? {
        val upPair = ParcelFileDescriptor.createReliablePipe()
        val dnPair = ParcelFileDescriptor.createReliablePipe()
        val (upRead, upWrite) = upPair[0] to upPair[1]
        val (dnRead, dnWrite) = dnPair[0] to dnPair[1]

        val mask = try {
            svc.startDualRecord(strategy.uplinkSource!!, strategy.downlinkSource!!, sampleRate, upWrite, dnWrite)
        } catch (e: android.os.RemoteException) {
            // ALL RemoteException subclasses (DeadObject, TransactionTooLarge,
            // DeadSystemRuntime, etc.) bubble to openStrategy → Transient.
            // Without this branch, `catch (_: Throwable)` below swallows them
            // and returns 0 → InitFailure → poisons the capability cache for
            // a strategy that's actually working — the daemon just hiccuped.
            runCatching { upWrite.close() }; runCatching { dnWrite.close() }
            runCatching { upRead.close() }; runCatching { dnRead.close() }
            throw e
        } catch (_: Throwable) { 0 }
        runCatching { upWrite.close() }; runCatching { dnWrite.close() }

        if (mask != 0b11) {
            runCatching { upRead.close() }; runCatching { dnRead.close() }
            runCatching { svc.stop() }
            return null
        }

        val upMeter = AudioLevelMeter(sampleRate).also { uplinkMeter = it }
        val dnMeter = AudioLevelMeter(sampleRate).also { downlinkMeter = it }
        val ext = currentExt()
        val upFile = storage.create(callId, "uplink", ext)
        val dnFile = storage.create(callId, "downlink", ext)
        pumpThreads += spawnPump(upRead, upFile, channels = 1, meter = upMeter).also { it.start() }
        pumpThreads += spawnPump(dnRead, dnFile, channels = 1, meter = dnMeter).also { it.start() }
        return Outcome.Dual(upFile, dnFile, strategy)
    }

    private fun trySingle(
        svc: dev.lyo.callrec.aidl.IRecorderService,
        callId: String,
        strategy: Strategy,
    ): Outcome.Single? {
        val pair = ParcelFileDescriptor.createReliablePipe()
        val (read, write) = pair[0] to pair[1]
        val channelMask = if (strategy.stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val ok = try {
            svc.startSingleRecord(strategy.singleSource!!, sampleRate, channelMask, write)
        } catch (e: android.os.RemoteException) {
            // See tryDual: bubble ALL RemoteException subclasses up so
            // openStrategy classifies them as Transient — without this the
            // `catch (_: Throwable)` swallows everything except DeadObject
            // and the cache learns false negatives.
            runCatching { write.close() }
            runCatching { read.close() }
            throw e
        } catch (_: Throwable) { 0 }
        runCatching { write.close() }

        if (ok == 0) { runCatching { read.close() }; return null }

        val meter = AudioLevelMeter(sampleRate).also { uplinkMeter = it }
        downlinkMeter = null
        val tag = when (strategy) {
            Strategy.SingleVoiceCallStereo -> "voicecall_stereo"
            Strategy.SingleVoiceCallMono -> "voicecall_mono"
            Strategy.SingleMic -> "mic"
            else -> "single"
        }
        val file = storage.create(callId, tag, currentExt())
        val channels = if (strategy.stereo) 2 else 1
        pumpThreads += spawnPump(read, file, channels = channels, meter = meter).also { it.start() }
        return Outcome.Single(file, strategy)
    }

    private fun adopt(out: Outcome) {
        activeOutcome = out
        _levels.value = _levels.value.copy(
            uplinkRms = 0f,
            downlinkRms = 0f,
            hasDownlink = out is Outcome.Dual,
            totalFrames = 0L,
        )
        _state.value = RecordingState.Active(out)
    }

    private sealed interface OpenResult {
        data class Success(val outcome: Outcome) : OpenResult
        data class InitFailure(val reason: String) : OpenResult
        data class Transient(val reason: String) : OpenResult
    }

    private enum class Verdict { Audible, Silent, UserStopped }

    /**
     * Watches the meters for [SILENCE_GRACE_MS]. Returns:
     *   - [Verdict.Audible] as soon as any active track reports an above-floor RMS.
     *   - [Verdict.Silent] after the grace window if every active track has been
     *     silent the whole time.
     *   - [Verdict.UserStopped] if [stop] was called externally.
     */
    private suspend fun waitForSignalOrSilence(): Verdict {
        val deadline = System.currentTimeMillis() + SILENCE_GRACE_MS
        while (System.currentTimeMillis() < deadline) {
            if (stopping) return Verdict.UserStopped
            val up = uplinkMeter
            val dn = downlinkMeter
            if (up == null) return Verdict.Silent // race; shouldn't happen
            _levels.value = _levels.value.copy(
                uplinkRms = up.lastRms,
                downlinkRms = dn?.lastRms ?: 0f,
                totalFrames = up.totalFrames,
            )
            // Audible if either side has live signal above the floor.
            if (up.isAudible || (dn != null && dn.isAudible)) {
                startWatchdog()
                return Verdict.Audible
            }
            delay(LEVEL_TICK_MS)
        }
        // Sustained silence over the full grace window.
        startWatchdog() // even if silent we keep updating UI; caller decides what to do
        return Verdict.Silent
    }

    /** Background loop that just keeps [levels] flowing while we record. */
    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (activeOutcome != null && !stopping) {
                val up = uplinkMeter ?: break
                val dn = downlinkMeter
                _levels.value = _levels.value.copy(
                    uplinkRms = up.lastRms,
                    downlinkRms = dn?.lastRms ?: 0f,
                    hasDownlink = dn != null,
                    totalFrames = up.totalFrames,
                )
                delay(LEVEL_TICK_MS)
            }
        }
    }

    /**
     * Stop the active recording, drain pumps, finalise file headers, and
     * return the (possibly downgraded) outcome.
     *
     * Post-mortem cleanup: if a Dual outcome ended up with one side
     * permanently silent (max RMS below the audible floor over the entire
     * call), drop that file and downgrade the result to Single. On Samsung
     * this is the common case for VOICE_UPLINK — the modem refuses to
     * expose the local-mic stream even with shell UID, but VOICE_DOWNLINK
     * sums both sides via sidetone, so the downlink track has the full
     * conversation. Without this downgrade the user ends up with two files,
     * one of which is two minutes of zero PCM.
     *
     * Suspending so callers can await on a coroutine without blocking. The
     * pump threads can take up to 2 s each to drain on EOF — calling this
     * synchronously from the main thread (as the earlier `fun stop(): Outcome?`
     * did) risked an ANR up to 4 s. The join now runs on `Dispatchers.IO`.
     */
    suspend fun stop(): Outcome? {
        stopping = true
        client.service.value?.runCatching { stop() }
        // Snapshot per-side max RMS BEFORE teardown nulls out the meters.
        val upMax = uplinkMeter?.maxRms ?: 0f
        val dnMax = downlinkMeter?.maxRms ?: 0f
        val upFloor = uplinkMeter?.calibratedFloor ?: 0.001f
        val dnFloor = downlinkMeter?.calibratedFloor ?: 0.001f
        withContext(Dispatchers.IO) { teardown() }
        val rawOutcome = activeOutcome
        val out = downgradeIfHalfSilent(rawOutcome, upMax, dnMax, upFloor, dnFloor)
        activeOutcome = null
        watchdog?.cancel(); watchdog = null
        _levels.value = Levels()
        _state.value = RecordingState.Idle
        return out
    }

    private fun downgradeIfHalfSilent(
        out: Outcome?, upMax: Float, dnMax: Float,
        upFloor: Float, dnFloor: Float,
    ): Outcome? {
        if (out !is Outcome.Dual) return out
        val upSilent = upMax < upFloor + AUDIBLE_DELTA
        val dnSilent = dnMax < dnFloor + AUDIBLE_DELTA
        return when {
            upSilent && !dnSilent -> {
                L.i("Recorder", "downgrade: uplink silent (max=$upMax) — keeping downlink only")
                runCatching { storage.delete(out.uplink.path) }
                Outcome.Single(out.downlink, out.strategy)
            }
            dnSilent && !upSilent -> {
                L.i("Recorder", "downgrade: downlink silent (max=$dnMax) — keeping uplink only")
                runCatching { storage.delete(out.downlink.path) }
                Outcome.Single(out.uplink, out.strategy)
            }
            else -> out
        }
    }

    private fun teardown() {
        // Pumps drain on EOF (UserService closed write end).
        // 5s gives encoder.close()'s muxer.stop() time to write the moov atom on slow storage
        pumpThreads.forEach { runCatching { it.join(5_000) } }
        pumpThreads.clear()
        uplinkMeter = null
        downlinkMeter = null
    }

    private fun spawnPump(
        pfd: ParcelFileDescriptor,
        file: RecordingFile,
        channels: Int,
        meter: AudioLevelMeter,
    ): Thread = Thread({
        var encoder: PcmEncoder? = null
        var input: FileInputStream? = null
        try {
            // open() can throw on cut-down ROMs; finally must still close the pipe so the daemon's pump stops blocking on the read end
            encoder = newEncoder(file).also { it.open(sampleRate, channels) }
            input = FileInputStream(pfd.fileDescriptor)
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                encoder.writePcm(buf, 0, n)
                meter.update(buf, 0, n)
            }
        } finally {
            runCatching { encoder?.close() }
            runCatching { input?.close() }
            runCatching { pfd.close() }
        }
    }, "callrec-pump-${file.tag}").apply { isDaemon = true }

    companion object {
        /**
         * Mirrors [AudioLevelMeter.AUDIBLE_DELTA]. We can't reference it directly
         * because it's `private` over there; this duplication is intentional —
         * keeping the threshold private to the meter avoids exporting it as API.
         * Update both if either changes.
         */
        private const val AUDIBLE_DELTA = 0.008f
        /** UI tick + watchdog cadence. */
        private const val LEVEL_TICK_MS = 60L
        /**
         * Window we wait for SOME signal before declaring silence. Pixel
         * ringback fires within ~1 s, so 2.5 s would be enough — but Samsung
         * One UI takes 3-5 s after OFFHOOK before the modem actually opens
         * the audio path. With a 2.5 s window we declared SingleVoiceCallStereo
         * silent on calls that were perfectly recordable a second later. 5 s
         * is the sweet spot: still feels instant on Pixel, gives Samsung the
         * time it needs.
         */
        private const val SILENCE_GRACE_MS = 5_000L
    }
}
