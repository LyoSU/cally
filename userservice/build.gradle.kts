plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
val userServiceVersion = 10
val signingSha256 = providers.gradleProperty("callrec.signingSha256").orElse("").get()

android {
    namespace = "dev.lyo.callrec.userservice"
    compileSdk = 36

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
