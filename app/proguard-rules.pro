# R8 shrinker rules. Inherits :userservice/consumer-rules.pro automatically;
# this file holds app-specific keeps.

# AIDL stubs are loaded by reflection (Stub.asInterface). Strip nothing.
-keep class dev.lyo.callrec.aidl.** { *; }

# Shizuku reflectively touches its provider. Keep entry points.
-keep class rikka.shizuku.** { *; }

# DataStore preferences key names are stable identifiers for migration.
-keepclassmembers class * extends androidx.datastore.preferences.core.Preferences { *; }

# Room: keep entity field names so generated DAOs can resolve column meta.
-keep class dev.lyo.callrec.storage.CallRecord { *; }

# Compose lambda strip safety (inline classes for state) — handled by R8 7.x+
# automatically; nothing custom needed.

# Strip kotlinx-coroutines debug agent in release.
-dontwarn kotlinx.coroutines.debug.**

# Suppress AndroidX lifecycle metadata noise.
-dontwarn javax.annotation.**
