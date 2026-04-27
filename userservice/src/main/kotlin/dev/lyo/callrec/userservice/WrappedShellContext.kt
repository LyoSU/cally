// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.userservice

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.ContextParams
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Process
import android.util.Log
import android.view.Display

/**
 * The mechanism that makes AudioRecord(VOICE_*) actually open from a shell-UID
 * Shizuku UserService process.
 *
 * Three key elements:
 *
 *   1. **Pretend to be `com.android.shell`** — that package ships in the
 *      system image, has UID 2000 (shell), and holds RECORD_AUDIO,
 *      CAPTURE_AUDIO_OUTPUT, MODIFY_AUDIO_ROUTING via signature permissions.
 *      AudioFlinger's `createFromTrustedUidNoPackage` accepts (uid=2000,
 *      pkg="com.android.shell") because that's a real system principal.
 *      Pretending to be our own app (uid=2000 + "dev.lyo.callrec.debug")
 *      fails the same gate because that combination doesn't exist in the
 *      package database.
 *
 *   2. **Patch ActivityThread, not Instrumentation.** AudioFlinger reaches
 *      caller identity through:
 *         AudioRecord.mAttributionSource
 *           ← Context.getAttributionSource()
 *             ← Application.getAttributionSource()
 *               ← ActivityThread.currentApplication() = mInitialApplication
 *                 ← ActivityThread.mBoundApplication.appInfo (LoadedApk)
 *      We must make every link of that chain return our wrapped identity.
 *      Critically, [ActivityThread.mSystemThread = true] flags this as a
 *      system process, which lets the gate skip per-package AppOps lookup.
 *
 *   3. **AttributionSource keeps the package**.
 *      AttributionSource.Builder(2000).setPackageName("com.android.shell")
 *      is set unconditionally — the "drop pkg for trusted UIDs" approach
 *      doesn't work. The trusted-UID path validates against the
 *      package database; "com.android.shell" *does* exist there with
 *      uid=2000.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
internal class WrappedShellContext(
    base: Context,
) : ContextWrapper(base) {

    /**
     * The package whose identity we wear. Must be a real system package with
     * uid=2000 — `com.android.shell` is the canonical choice and exists on
     * every Android image.
     */
    private val opPackage: String = SHELL_PACKAGE

    init {
        // Build a fake Application bound to *this* wrapper. All identity
        // queries through Application.getAttributionSource() / getOpPackageName
        // funnel back to our overrides below.
        val fakeApp: Application? = runCatching {
            Instrumentation.newApplication(Application::class.java, this)
        }.onFailure { Log.w(TAG, "newApplication failed", it) }.getOrNull()

        // ── Patch ActivityThread internals ─────────────────────────────────
        // These are the fields AudioFlinger ultimately consults via the
        // AudioRecord → AttributionSource → Application chain.
        runCatching {
            val atCls = Class.forName("android.app.ActivityThread")
            val atInstance = atCls.getMethod("currentActivityThread").invoke(null)
                ?: atCls.getMethod("systemMain").invoke(null)

            // sCurrentActivityThread — global static the framework consults to
            // find "the" ActivityThread. Some paths bypass currentApplication()
            // and reach the AT directly; this guarantees they hit ours.
            runCatching {
                atCls.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }
                    .set(null, atInstance)
            }

            // mSystemThread = true → marks this AT as belonging to the system
            // process. AudioFlinger and AppOps treat system-process callers
            // as pre-authorised for media-related permissions, skipping the
            // per-package check that's the source of all our prior failures.
            runCatching {
                atCls.getDeclaredField("mSystemThread").apply { isAccessible = true }
                    .setBoolean(atInstance, true)
                Log.i(TAG, "WrappedShellContext: ActivityThread.mSystemThread=true")
            }.onFailure { Log.w(TAG, "mSystemThread set failed", it) }

            // mInitialApplication is what currentApplication() returns. Wire
            // it to our fake Application so getAttributionSource() chain
            // resolves back to this wrapper.
            if (fakeApp != null) {
                runCatching {
                    atCls.getDeclaredField("mInitialApplication").apply { isAccessible = true }
                        .set(atInstance, fakeApp)
                    Log.i(TAG, "WrappedShellContext: ActivityThread.mInitialApplication patched")
                }.onFailure { Log.w(TAG, "mInitialApplication set failed", it) }
            }

            // mBoundApplication is an AppBindData struct holding ApplicationInfo
            // among other fields. AudioFlinger sometimes resolves the package
            // via this path; populate it with our pretend identity.
            runCatching {
                val mBound = atCls.getDeclaredField("mBoundApplication")
                    .apply { isAccessible = true }
                val bound = mBound.get(atInstance) ?: kotlin.run {
                    val bindCls = Class.forName("android.app.ActivityThread\$AppBindData")
                    val ctor = bindCls.getDeclaredConstructor().apply { isAccessible = true }
                    ctor.newInstance().also { mBound.set(atInstance, it) }
                }
                val appInfo = ApplicationInfo().apply {
                    packageName = opPackage
                    uid = Process.myUid()
                }
                Class.forName("android.app.ActivityThread\$AppBindData")
                    .getDeclaredField("appInfo")
                    .apply { isAccessible = true }
                    .set(bound, appInfo)
                Log.i(TAG, "WrappedShellContext: AppBindData.appInfo patched (pkg=$opPackage)")
            }.onFailure { Log.w(TAG, "AppBindData patch failed", it) }
        }.onFailure { Log.e(TAG, "ActivityThread patches failed", it) }

        Log.i(TAG, "WrappedShellContext ready: pkg=$opPackage uid=${Process.myUid()}")
    }

    // ── Identity overrides — what AppOps / AudioFlinger see ────────────────

    override fun getPackageName(): String = opPackage
    override fun getOpPackageName(): String = opPackage

    /**
     * AttributionSource WITH packageName. The
     * "createFromTrustedUidNoPackage" path validates the supplied package
     * against the package database. `com.android.shell` exists in the DB
     * with uid=2000, so the validator accepts. (Our app's package fails
     * the same check because it has uid≠2000.)
     */
    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(Process.myUid())
            .setPackageName(opPackage)
            .build()

    override fun getDeviceId(): Int = 0

    /**
     * Patches mContext on captured system services so that any AppOps query
     * those services perform later sees our wrapped identity, not the raw
     * system Context.
     */
    override fun getSystemService(name: String): Any? {
        val svc = super.getSystemService(name) ?: return null
        if (name in CONTEXT_BOUND_SERVICES) {
            runCatching {
                svc.javaClass.getDeclaredField("mContext").apply { isAccessible = true }
                    .set(svc, this)
            }
        }
        return svc
    }

    override fun getApplicationContext(): Context = this
    override fun createAttributionContext(attributionTag: String?): Context = this
    override fun createConfigurationContext(overrideConfiguration: Configuration): Context = this
    override fun createContext(contextParams: ContextParams): Context = this
    override fun createDeviceContext(deviceId: Int): Context = this
    override fun createDisplayContext(display: Display): Context = this
    override fun createPackageContext(packageName: String?, flags: Int): Context = this

    companion object {
        private const val TAG = "Callrec"
        // Real system package shipped on every Android image. Has uid=2000
        // and carries the signature-level audio permissions (RECORD_AUDIO,
        // CAPTURE_AUDIO_OUTPUT, MODIFY_AUDIO_ROUTING) that AudioFlinger gates
        // on. Wearing this identity is what makes shell-UID AudioRecord work.
        private const val SHELL_PACKAGE = "com.android.shell"
        private val CONTEXT_BOUND_SERVICES = setOf(
            Context.AUDIO_SERVICE,
            Context.TELEPHONY_SERVICE,
            Context.MEDIA_SESSION_SERVICE,
            Context.MEDIA_ROUTER_SERVICE,
            Context.APP_OPS_SERVICE,
        )
    }
}
