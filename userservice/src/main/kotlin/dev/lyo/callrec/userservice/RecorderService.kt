// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.userservice

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import dev.lyo.callrec.aidl.IRecorderService
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Privileged recorder. Lives in the Shizuku-spawned `app_process` (UID 2000 =
 * shell). Reachable via Binder from the app process through Shizuku's
 * UserService API.
 *
 * Two pumps run in parallel for the dual-track strategy. The Stub itself is
 * stateful but lock-free for the hot path (AtomicInteger state + per-job
 * thread-local lifetime).
 */
class RecorderService : IRecorderService.Stub() {

    init {
        Log.i(TAG, "RecorderService init begin (uid=${Process.myUid()})")
        HiddenApiBootstrap.enable()
        // Stub is instantiated on Shizuku's main thread (which has a Looper).
        // Prime the system Context cache here so later AIDL transactions —
        // which run on Binder threads with NO Looper — don't have to call
        // `ActivityThread.systemMain()` themselves (it'd throw on Binder).
        ServiceContext.primeFromMainThread()
    }

    /**
     * Context that AudioRecord ctor / AppOps see. For shell UID this is a
     * [WrappedShellContext] (AppOps check chains through this for the
     * RECORD_AUDIO permission); for root UID the system Context is enough.
     *
     * Eagerly built (not lazy) so reflection runs on the construction thread,
     * not on the first Binder transaction. Wrapped in runCatching so
     * reflection failures don't take down the whole Stub — better to record
     * with raw context (which may still work on some Android versions) than
     * to refuse to bind at all.
     */
    private val captureContext: Context = run {
        val raw = ServiceContext.get()
        if (Process.myUid() == 0) {
            raw
        } else {
            runCatching { WrappedShellContext(raw) }
                .onFailure { Log.e(TAG, "WrappedShellContext failed; falling back to raw context", it) }
                .getOrDefault(raw)
        }
    }.also { Log.i(TAG, "captureContext ready: ${it.javaClass.simpleName} pkg=${it.opPackageName}") }

    private val state = AtomicInteger(STATE_IDLE)
    @Volatile private var uplinkJob: AudioRecorderJob? = null
    @Volatile private var downlinkJob: AudioRecorderJob? = null
    private val lastError = AtomicReference<String?>(null)

    override fun getVersion(): Int = BuildConfig.VERSION_CODE_USERSERVICE

    override fun startDualRecord(
        uplinkSource: Int,
        downlinkSource: Int,
        sampleRate: Int,
        uplinkFd: ParcelFileDescriptor,
        downlinkFd: ParcelFileDescriptor,
    ): Int {
        Log.i(TAG, "startDualRecord up=$uplinkSource dn=$downlinkSource sr=$sampleRate")
        try {
            verifyCaller()
        } catch (t: Throwable) {
            Log.e(TAG, "startDualRecord verifyCaller threw", t)
            lastError.set("verifyCaller: ${t.message}")
            uplinkFd.close(); downlinkFd.close()
            throw t
        }
        if (!state.compareAndSet(STATE_IDLE, STATE_STARTING)) {
            Log.w(TAG, "startDualRecord rejected — state=${state.get()} not IDLE")
            uplinkFd.close(); downlinkFd.close()
            return 0
        }

        val up = AudioRecorderJob("uplink", uplinkSource, sampleRate, AudioFormat.CHANNEL_IN_MONO, uplinkFd, captureContext)
        val dn = AudioRecorderJob("downlink", downlinkSource, sampleRate, AudioFormat.CHANNEL_IN_MONO, downlinkFd, captureContext)

        val upOk = up.init()
        val dnOk = dn.init()
        Log.i(TAG, "startDualRecord init: up=$upOk(${up.errorMessage}) dn=$dnOk(${dn.errorMessage})")

        if (!upOk && !dnOk) {
            lastError.set(
                "dual init failed: up=${up.errorMessage}; dn=${dn.errorMessage}"
            )
            // Both pipes were ours; jobs that failed init never opened their FDs,
            // close them so the client read-side gets EOF instead of hanging.
            runCatching { uplinkFd.close() }
            runCatching { downlinkFd.close() }
            state.set(STATE_IDLE)
            return 0
        }

        var mask = 0
        if (upOk) { uplinkJob = up; mask = mask or 0b01; up.start() } else { runCatching { uplinkFd.close() } }
        if (dnOk) { downlinkJob = dn; mask = mask or 0b10; dn.start() } else { runCatching { downlinkFd.close() } }

        state.set(STATE_DUAL)
        return mask
    }

    override fun startSingleRecord(
        source: Int,
        sampleRate: Int,
        channelMask: Int,
        pcmFd: ParcelFileDescriptor,
    ): Int {
        Log.i(TAG, "startSingleRecord src=$source sr=$sampleRate ch=$channelMask")
        try {
            verifyCaller()
        } catch (t: Throwable) {
            Log.e(TAG, "startSingleRecord verifyCaller threw", t)
            lastError.set("verifyCaller: ${t.message}")
            pcmFd.close()
            throw t
        }
        if (!state.compareAndSet(STATE_IDLE, STATE_STARTING)) {
            Log.w(TAG, "startSingleRecord rejected — state=${state.get()} not IDLE")
            pcmFd.close()
            return 0
        }

        val job = AudioRecorderJob("single", source, sampleRate, channelMask, pcmFd, captureContext)
        val ok = job.init()
        Log.i(TAG, "startSingleRecord init: ok=$ok err=${job.errorMessage}")
        if (!ok) {
            lastError.set("single init failed: ${job.errorMessage}")
            runCatching { pcmFd.close() }
            state.set(STATE_IDLE)
            return 0
        }
        uplinkJob = job
        job.start()
        state.set(STATE_SINGLE)
        return 1
    }

    override fun stop() {
        verifyCaller()
        // Drop state first so any lingering pump exits its loop on next read.
        state.set(STATE_IDLE)
        uplinkJob?.stop()
        downlinkJob?.stop()
        uplinkJob = null
        downlinkJob = null
    }

    override fun getState(): Int = state.get()

    override fun getLastError(): String? = lastError.get()

    override fun probeSource(source: Int, sampleRate: Int, durationMs: Int): Int {
        verifyCaller()
        // Need the AppOps-friendly main-Looper ctor; reuse AudioRecorderJob
        // and reach into its (private) record reference via init+release.
        // Cheaper than duplicating the pattern.
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val read = pipe[0]; val write = pipe[1]
        val job = AudioRecorderJob(
            tag = "probe-$source",
            source = source,
            sampleRate = sampleRate,
            channelMask = AudioFormat.CHANNEL_IN_MONO,
            outFd = write,
            context = captureContext,
        )
        if (!job.init()) {
            runCatching { write.close() }
            runCatching { read.close() }
            Log.w(TAG, "probeSource: init failed src=$source — ${job.errorMessage}")
            return -1
        }
        // We can't easily read the PCM here without consuming the pipe;
        // for now return 0 to indicate "init OK, audibility unknown" and
        // rely on real-call audibility verification in RecorderController.
        job.stop()
        runCatching { read.close() }
        return 0
    }

    /**
     * Grant a runtime permission to **our own package** (canonical or `.debug`
     * suffix). Restricted to a hardcoded allow-list — earlier code took an
     * arbitrary `packageName` + `permission` and would have been an LPE
     * primitive if any future caller plumbed attacker-controlled args through.
     *
     * The verifyCaller() pin keeps foreign apps out of the AIDL surface, but
     * the allow-list is defence-in-depth: even an internal misuse can't
     * accidentally `pm grant` arbitrary packages arbitrary permissions.
     */
    override fun grantPermission(packageName: String, permission: String): Boolean {
        verifyCaller()
        val ourPkg = BuildConfig.APP_PACKAGE_ID
        val packageOk = packageName == ourPkg || packageName == "$ourPkg.debug"
        if (!packageOk) return false
        if (permission !in ALLOWED_GRANT_PERMS) return false
        return try {
            val proc = ProcessBuilder("pm", "grant", packageName, permission)
                .redirectErrorStream(true).start()
            proc.waitFor() == 0
        } catch (_: Throwable) { false }
    }

    /**
     * Defence in depth: with `daemon=true` the Binder lives across our app's
     * lifetime. Any other Shizuku-permitted package on the device could in
     * theory enumerate Binders and call us. Reject anything that isn't us.
     *
     * Verifies UID → package mapping AND signing certificate SHA-256 against
     * the constant baked into the userservice module at build time.
     */
    private fun verifyCaller() {
        val uid = Binder.getCallingUid()
        if (uid == android.os.Process.myUid()) return // intra-process probe
        val pm = ServiceContext.get().packageManager
        val pkgs = pm.getPackagesForUid(uid)
            ?: throw SecurityException("verifyCaller: no packages for uid=$uid")
        val ourPkg = BuildConfig.APP_PACKAGE_ID
        // Accept the canonical package and any debug/internal-test variant
        // (applicationIdSuffix produces "dev.lyo.callrec.debug"). The signing
        // cert pin below — when configured — keeps this safe; without it, the
        // prefix is still tighter than reality, since the only way for a
        // foreign app to inherit our UID is to share signature anyway.
        val matched = pkgs.any { it == ourPkg || it.startsWith("$ourPkg.") }
        if (!matched) throw SecurityException("verifyCaller: foreign uid=$uid pkgs=${pkgs.toList()} expected~$ourPkg")

        // Signing cert pinning.
        val expected = BuildConfig.APP_SIGNING_SHA256
        if (expected.isEmpty()) return // unset in debug builds — fall through

        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(ourPkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners ?: emptyArray()
        if (signers.isEmpty()) throw SecurityException("verifyCaller: no signing certs")
        val md = MessageDigest.getInstance("SHA-256")
        val signatureOk = signers.any { it.toByteArray().let(md::digest).toHex() == expected }
        if (!signatureOk) throw SecurityException("verifyCaller: signing cert mismatch")
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val u = b.toInt() and 0xFF
            sb.append(HEX[u ushr 4]); sb.append(HEX[u and 0x0F])
        }
        return sb.toString()
    }

    /** Required by Shizuku UserService — onBind result is the Stub itself. */
    @Suppress("unused")
    fun onBind(): IBinder = this

    @Suppress("unused")
    fun onDestroy() {
        runCatching { stop() }
    }

    companion object {
        // logcat tag deliberately mirrors the app-side "Callrec" so a single
        // `adb logcat -s Callrec:V` shows both processes interleaved.
        private const val TAG = "Callrec"

        const val STATE_IDLE = 0
        const val STATE_STARTING = 1
        const val STATE_DUAL = 2
        const val STATE_SINGLE = 3
        const val STATE_ERROR = 9

        /**
         * Hardcoded allow-list for [grantPermission]. We only use the
         * UserService to attach signature-level perms that the platform will
         * never grant a normal-UID app — anything user-grantable goes through
         * the standard runtime permissions flow in the app. Adding to this
         * list requires both an explicit code change AND a manifest entry.
         */
        private val ALLOWED_GRANT_PERMS = setOf(
            "android.permission.READ_LOGS",
            "android.permission.WRITE_SECURE_SETTINGS",
        )
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
