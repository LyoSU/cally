plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.lyo.callrec.aidl"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    buildFeatures {
        aidl = true
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }
}
