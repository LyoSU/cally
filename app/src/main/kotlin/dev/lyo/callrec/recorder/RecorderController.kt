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
    fun start(callId: String) {
        scope.launch { startInternal(callId) }
    }

    private suspend fun startInternal(callId: String) {
        if (activeOutcome != null) { L.w("Recorder", "start() while already active — ignored"); return }
        val svc = client.service.value
        if (svc == null) {
            L.e("Recorder", "UserService not bound — Shizuku binding race?")
            _state.value = RecordingState.Failed("UserService not bound")
            return
        }
        stopping = false

        val caps = capabilities.current() ?: Capabilities(android.os.Build.FINGERPRINT, null)
        L.i("Recorder", "caps preferred=${caps.preferredStrategy?.name ?: "-"} silent=${caps.knownSilent.map { it.name }} failedInit=${caps.knownFailedInit.map { it.name }}")
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
            val attempt = openStrategy(svc, callId, strategy)
            if (attempt == null) {
                val daemonErr = runCatching { svc.lastError }.getOrNull().orEmpty()
                L.w("Recorder", "${strategy.name} init FAILED. daemon=$daemonErr")
                capabilities.update { it.copy(knownFailedInit = it.knownFailedInit + strategy) }
                continue
            }
            L.i("Recorder", "${strategy.name} opened → verifying audibility")
            // Strategy is open — kick off pumps and watch for silence.
            adopt(attempt)
            val verdict = waitForSignalOrSilence()
            when (verdict) {
                Verdict.Audible -> {
                    L.i("Recorder", "${strategy.name} AUDIBLE — adopting (uplink=${uplinkMeter?.lastRms} dn=${downlinkMeter?.lastRms})")
                    capabilities.update {
                        it.copy(
                            preferredStrategy = strategy,
                            // Recovery: this strategy worked, drop it from
                            // silence list and reset its strike count.
                            knownSilent = it.knownSilent - strategy,
                            silentStrikes = it.silentStrikes - strategy,
                        )
                    }
                    return
                }
                Verdict.Silent -> {
                    L.w("Recorder", "${strategy.name} SILENT for ${SILENCE_GRACE_MS}ms (uplink=${uplinkMeter?.lastRms} dn=${downlinkMeter?.lastRms})")
                    // Special case: SingleMic is the guaranteed last resort.
                    if (strategy == Strategy.SingleMic) {
                        L.i("Recorder", "SingleMic ambient was quiet but adopting anyway (last-resort)")
                        capabilities.update { it.copy(preferredStrategy = strategy) }
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
                    capabilities.update { caps ->
                        val nextStrikes = (caps.silentStrikes[strategy] ?: 0) + 1
                        val updated = caps.silentStrikes + (strategy to nextStrikes)
                        caps.copy(
                            silentStrikes = updated,
                            knownSilent = if (nextStrikes >= Capabilities.SILENT_STRIKES_BEFORE_BLACKLIST)
                                caps.knownSilent + strategy else caps.knownSilent,
                        )
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
        _state.value = RecordingState.Failed("All strategies returned silence")
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
    ): Outcome? {
        return if (strategy.isDual) {
            tryDual(svc, callId, strategy)
        } else {
            trySingle(svc, callId, strategy)
        }
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
        } catch (_: Throwable) { 0 }
        runCatching { upWrite.close() }; runCatching { dnWrite.close() }

        if (mask != 0b11) {
            runCatching { upRead.close() }; runCatching { dnRead.close() }
            runCatching { svc.stop() }
            return null
        }

        val upMeter = AudioLevelMeter().also { uplinkMeter = it }
        val dnMeter = AudioLevelMeter().also { downlinkMeter = it }
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
        } catch (_: Throwable) { 0 }
        runCatching { write.close() }

        if (ok == 0) { runCatching { read.close() }; return null }

        val meter = AudioLevelMeter().also { uplinkMeter = it }
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
        _levels.value = Levels(hasDownlink = out is Outcome.Dual)
        _state.value = RecordingState.Active(out)
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
            // Keep the UI fresh.
            _levels.value = _levels.value.copy(
                uplinkRms = up.lastRms,
                downlinkRms = dn?.lastRms ?: 0f,
                totalFrames = up.totalFrames,
            )
            // Audible if either side has live signal above the floor.
            if (up.lastRms > AUDIBLE_THRESHOLD || (dn != null && dn.lastRms > AUDIBLE_THRESHOLD)) {
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
                _levels.value = Levels(
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
     * Stop the active recording. Returns the (possibly downgraded) outcome.
     *
     * Post-mortem cleanup: if a Dual outcome ended up with one side
     * permanently silent (max RMS below the audible floor over the entire
     * call), drop that file and downgrade the result to Single. On Samsung
     * this is the common case for VOICE_UPLINK — the modem refuses to
     * expose the local-mic stream even with shell UID, but VOICE_DOWNLINK
     * sums both sides via sidetone, so the downlink track has the full
     * conversation. Without this downgrade the user ends up with two files,
     * one of which is two minutes of zero PCM.
     */
    /**
     * Stop the active recording, drain pumps, finalise file headers, and
     * return the (possibly downgraded) outcome.
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
        withContext(Dispatchers.IO) { teardown() }
        val rawOutcome = activeOutcome
        val out = downgradeIfHalfSilent(rawOutcome, upMax, dnMax)
        activeOutcome = null
        watchdog?.cancel(); watchdog = null
        _levels.value = Levels()
        _state.value = RecordingState.Idle
        return out
    }

    private fun downgradeIfHalfSilent(out: Outcome?, upMax: Float, dnMax: Float): Outcome? {
        if (out !is Outcome.Dual) return out
        val upSilent = upMax < AUDIBLE_THRESHOLD
        val dnSilent = dnMax < AUDIBLE_THRESHOLD
        return when {
            upSilent && !dnSilent -> {
                L.i("Recorder", "downgrade: uplink silent (max=$upMax) — keeping downlink only")
                runCatching { java.io.File(out.uplink.path).delete() }
                Outcome.Single(out.downlink, out.strategy)
            }
            dnSilent && !upSilent -> {
                L.i("Recorder", "downgrade: downlink silent (max=$dnMax) — keeping uplink only")
                runCatching { java.io.File(out.downlink.path).delete() }
                Outcome.Single(out.uplink, out.strategy)
            }
            else -> out
        }
    }

    private fun teardown() {
        // Pumps drain on EOF (UserService closed write end).
        pumpThreads.forEach { runCatching { it.join(2_000) } }
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
        val encoder = newEncoder(file).apply { open(sampleRate, channels) }
        val input = FileInputStream(pfd.fileDescriptor)
        val buf = ByteArray(8 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                encoder.writePcm(buf, 0, n)
                meter.update(buf, 0, n)
            }
        } finally {
            runCatching { encoder.close() }
            runCatching { input.close() }
            runCatching { pfd.close() }
        }
    }, "callrec-pump-${file.tag}").apply { isDaemon = true }

    companion object {
        /**
         * Above this normalised RMS (1.0 = full scale) we declare "audible".
         *
         * 0.005 ≈ -46 dBFS. Empirically:
         *  • Mic preamp drift / DC bias on Pixel 10 telephony stream: ~0.003
         *    (-50 dBFS) — we want to REJECT this, otherwise we adopt a stream
         *    that records silence and the user gets an empty file.
         *  • Ringback tones (~0.05 RMS, -26 dBFS) — clearly above.
         *  • Conversational voice (~0.02..0.20, -34..-14 dBFS) — well above.
         *
         * Earlier 0.0008 was set so SingleMic could pass on quiet rooms;
         * MIC has its own carve-out (last-resort force-adopt) so the
         * threshold can be tighter for the rest.
         */
        private const val AUDIBLE_THRESHOLD = 0.005f
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
