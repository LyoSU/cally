import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

// Pinned constants the UserService bakes into BuildConfig. Kept here (not in
// gradle.properties) because they're load-bearing for security:
//   - APP_PACKAGE_ID  → verifyCaller() rejects any caller not from this UID.
//   - APP_SIGNING_SHA256 → verifyCaller() pins the release signing certificate.
//                          Empty in debug builds (verification falls through).
//   - VERSION_CODE_USERSERVICE → bumped whenever the AIDL contract or pump
//                          semantics change; `daemon=true` daemons surviving
//                          an upgrade compare versions and respawn if stale.
val appPackageId = "dev.lyo.callrec"
// Bump on every change to RecorderService / AudioRecorderJob / verifyCaller —
// the Shizuku daemon (daemon=true) checks this and respawns if its in-memory
// version differs from the freshly-installed APK's version.
//
// 12: getBypassHealth() added; probeSource() and grantPermission() removed.
//     AIDL contract changed — in-flight v11 daemon must respawn.
val userServiceVersion = 12

// Auto-derive the release certificate's SHA-256 from the keystore so the
// verifyCaller() pin always matches the APK we just signed — without forcing
// the developer to copy a hex string into a separate property. Manual override
// via `signingSha256=…` in keystore.properties wins if present (useful when
// the keystore lives elsewhere or is hardware-backed and not loadable here).
// Empty result → debug build → verifyCaller short-circuits, which is
// intentional for sideloaded development APKs.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val signingSha256: String = run {
    keystoreProps.getProperty("signingSha256")?.takeIf { it.isNotBlank() }?.let { return@run it }
    val storeFile = keystoreProps.getProperty("storeFile") ?: return@run ""
    val storePassword = keystoreProps.getProperty("storePassword") ?: return@run ""
    val keyAlias = keystoreProps.getProperty("keyAlias") ?: return@run ""
    val ksFile = rootProject.file(storeFile)
    if (!ksFile.exists()) return@run ""
    // PKCS12 first (modern keytool default since JDK 9), JKS as legacy fallback.
    val ks: KeyStore? = sequenceOf("PKCS12", "JKS").mapNotNull { type ->
        runCatching {
            KeyStore.getInstance(type).apply {
                ksFile.inputStream().use { load(it, storePassword.toCharArray()) }
            }
        }.getOrNull()
    }.firstOrNull()
    val cert = ks?.getCertificate(keyAlias) ?: return@run ""
    MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        .joinToString("") { "%02x".format(it) }
}

android {
    namespace = "dev.lyo.callrec.userservice"
    compileSdk = 37

    defaultConfig {
        minSdk = 31

        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "APP_PACKAGE_ID", "\"$appPackageId\"")
        buildConfigField("String", "APP_SIGNING_SHA256", "\"$signingSha256\"")
        buildConfigField("int", "VERSION_CODE_USERSERVICE", "$userServiceVersion")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }
}

dependencies {
    api(project(":aidl"))
    implementation(libs.hiddenapibypass)
}
