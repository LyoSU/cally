// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.userservice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Inside the Shizuku-spawned `app_process` we don't have an Application — we
 * obtain a system Context via reflection on `android.app.ActivityThread`.
 *
 * Subtlety: AIDL transactions are dispatched on the Binder thread pool which
 * has **no Looper** prepared. `ActivityThread.systemMain()` constructs an
 * `ActivityThread` whose ctor instantiates a `Handler`, which throws
 * "Can't create handler inside thread … that has not called Looper.prepare()".
 *
 * We therefore:
 *   1. First try `ActivityThread.currentActivityThread()` — returns an
 *      existing instance if one was created already (e.g. because Shizuku
 *      itself prepared one on the main Looper). No Handler ctor → safe on
 *      any thread.
 *   2. If null, marshal `systemMain()` onto the process's main Looper via
 *      Handler.post + CountDownLatch and wait synchronously. Cheap (~ms).
 */
internal object ServiceContext {
    private const val TAG = "Callrec"
    @Volatile private var cached: Context? = null

    fun get(): Context {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val ctx = obtainSystemContext()
            cached = ctx
            return ctx
        }
    }

    private fun obtainSystemContext(): Context {
        val cls = Class.forName("android.app.ActivityThread")

        // Path 1: reuse existing ActivityThread if one is already registered.
        // Common case: Shizuku creates it on its main thread before the first
        // AIDL transaction lands. No Handler ctor → safe on any thread.
        runCatching {
            val existing = cls.getMethod("currentActivityThread").invoke(null)
            if (existing != null) {
                return cls.getMethod("getSystemContext").invoke(existing) as Context
            }
        }.onFailure { Log.v(TAG, "currentActivityThread reflection: ${it.message}") }

        // Path 2: we need to construct one. systemMain() instantiates a Handler
        // so it MUST run on a Looper thread. Detect where we are:
        val main = Looper.getMainLooper()
            ?: error("No main Looper in this process — Shizuku UserService misconfigured")

        return if (Thread.currentThread() === main.thread) {
            // Lucky: caller is on main Looper — invoke directly.
            val at = cls.getMethod("systemMain").invoke(null)
            cls.getMethod("getSystemContext").invoke(at) as Context
        } else {
            // Marshal onto main Looper and block briefly.
            val result = AtomicReference<Context?>(null)
            val err = AtomicReference<Throwable?>(null)
            val latch = CountDownLatch(1)
            Handler(main).post {
                try {
                    val at = cls.getMethod("systemMain").invoke(null)
                    result.set(cls.getMethod("getSystemContext").invoke(at) as Context)
                } catch (t: Throwable) {
                    err.set(t)
                } finally {
                    latch.countDown()
                }
            }
            if (!latch.await(2, TimeUnit.SECONDS)) {
                error("systemMain() on main Looper timed out (2s)")
            }
            err.get()?.let { throw it }
            result.get() ?: error("systemMain() returned null")
        }
    }

    /**
     * Eagerly initialise from the main Looper. Call from RecorderService's
     * constructor (Shizuku runs that on its main thread before any Binder
     * transactions arrive) so subsequent `get()` calls from Binder threads
     * hit the cache instead of paying the dispatch round-trip.
     */
    fun primeFromMainThread() {
        if (cached != null) return
        runCatching { get() }
            .onSuccess { Log.i(TAG, "ServiceContext primed from main thread") }
            .onFailure { Log.w(TAG, "ServiceContext priming failed", it) }
    }
}
