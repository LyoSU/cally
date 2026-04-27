// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.userservice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One AudioRecord pump per stream. Two notable patterns:
 *
 *   1. **AudioRecord ctor on the main Looper, not the Binder thread.**
 *      AudioFlinger's AppOps RECORD_AUDIO check walks a thread-local that's
 *      blank in our shell-spawned `app_process`. Constructing on the
 *      main Looper threads it through ActivityThread's well-known message
 *      pump, which has the right plumbing for the permission check to
 *      resolve through the supplied [Context]. Without this, ctor returns
 *      STATE_UNINITIALIZED for every source.
 *
 *   2. **The `Context` is a [WrappedShellContext]**, not the raw system
 *      Context. That wrapper makes AppOps see our app's package + UID 2000,
 *      not <unknown>.
 */
internal class AudioRecorderJob(
    private val tag: String,
    private val source: Int,
    private val sampleRate: Int,
    private val channelMask: Int,
    private val outFd: ParcelFileDescriptor,
    private val context: Context,
) {
    private val running = AtomicBoolean(false)
    private val lastError = AtomicReference<String?>(null)
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    val errorMessage: String? get() = lastError.get()

    fun init(): Boolean {
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuf <= 0) {
            lastError.set("getMinBufferSize=$minBuf for source=$source sr=$sampleRate ch=$channelMask")
            return false
        }
        val channels = if (channelMask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
        val targetBuf = (sampleRate * channels * 2) / 4 // ~250ms
        val bufSize = maxOf(minBuf * 2, targetBuf)

        val rec = constructOnMainLooper(source, sampleRate, channelMask, encoding, bufSize)
            ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            val msg = "AudioRecord state=${rec.state} (not INITIALIZED) source=$source"
            Log.w(TAG, msg)
            lastError.set(msg)
            rec.release()
            return false
        }
        record = rec
        Log.i(TAG, "AudioRecord OK src=$source sr=$sampleRate ch=$channelMask buf=$bufSize")
        return true
    }

    /**
     * Synchronously construct an AudioRecord on the main Looper of this
     * process. Blocking the Binder thread for ~10–80 ms is acceptable.
     * The alternative (constructing inline) silently fails AppOps on shell UID.
     *
     * Uses [Context] only to anchor the operation to this process's main
     * thread context — Context is not a parameter of AudioRecord ctor, but
     * the static lookup that AppOps performs reads thread-local hooks set up
     * by ActivityThread when running on the main Looper.
     */
    private fun constructOnMainLooper(
        src: Int, sr: Int, ch: Int, enc: Int, buf: Int,
    ): AudioRecord? {
        val mainLooper = Looper.getMainLooper()
        val onMain = mainLooper.thread === Thread.currentThread()
        if (onMain) return tryCtor(src, sr, ch, enc, buf)

        val result = AtomicReference<AudioRecord?>(null)
        val err = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)
        Handler(mainLooper).post {
            try {
                result.set(tryCtor(src, sr, ch, enc, buf))
            } catch (t: Throwable) {
                err.set(t)
            } finally {
                latch.countDown()
            }
        }
        val ok = latch.await(2, TimeUnit.SECONDS)
        if (!ok) {
            lastError.set("AudioRecord ctor on main Looper timed out (2s)")
            return null
        }
        err.get()?.let {
            lastError.set("ctor threw: ${it.javaClass.simpleName}: ${it.message}")
            return null
        }
        return result.get()
    }

    private fun tryCtor(src: Int, sr: Int, ch: Int, enc: Int, buf: Int): AudioRecord? {
        return try {
            // Builder API (≥ API 31) is the only way to make AudioRecord pick
            // up our [WrappedShellContext]'s AttributionSource. The legacy
            // five-arg ctor pulls attribution from ActivityThread statics, not
            // from any Context we can override — Context.getAttributionSource()
            // never reaches the native AudioFlinger gate via that path.
            //
            // setContext() extracts attribution AND device-specific session
            // ids from the supplied Context. For shell UID our wrapper returns
            // an AttributionSource without packageName (createFromTrustedUid
            // path), which AudioFlinger validates and accepts.
            val format = android.media.AudioFormat.Builder()
                .setEncoding(enc)
                .setSampleRate(sr)
                .setChannelMask(ch)
                .build()
            AudioRecord.Builder()
                .setContext(context)
                .setAudioSource(src)
                .setAudioFormat(format)
                .setBufferSizeInBytes(buf)
                .build()
        } catch (t: Throwable) {
            lastError.set("AudioRecord ctor: ${t.javaClass.simpleName}: ${t.message}")
            Log.w(TAG, "ctor threw", t)
            null
        }
    }

    fun start() {
        val rec = checkNotNull(record) { "init() must precede start()" }
        running.set(true)
        thread = Thread({ pump(rec) }, "callrec-$tag").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    fun stop(joinMs: Long = 1500) {
        if (!running.compareAndSet(true, false)) return
        runCatching { record?.stop() }
        thread?.let { runCatching { it.join(joinMs) } }
        thread = null
        runCatching { record?.release() }
        record = null
    }

    private fun pump(rec: AudioRecord) {
        val out = FileOutputStream(outFd.fileDescriptor)
        val buf = ByteArray(8 * 1024)
        try {
            rec.startRecording()
            while (running.get()) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                when {
                    n > 0 -> out.write(buf, 0, n)
                    n == AudioRecord.ERROR_INVALID_OPERATION ||
                        n == AudioRecord.ERROR_BAD_VALUE ||
                        n == AudioRecord.ERROR_DEAD_OBJECT -> {
                        lastError.set("AudioRecord.read returned $n")
                        break
                    }
                    n == 0 -> { /* spurious */ }
                    else -> {
                        lastError.set("AudioRecord.read returned $n")
                        break
                    }
                }
            }
        } catch (t: Throwable) {
            lastError.set("pump: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { out.flush() }
            runCatching { out.close() }
            runCatching { outFd.close() }
        }
    }

    companion object {
        private const val TAG = "Callrec"
    }
}
