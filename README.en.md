# Cally — Pixel Call Recorder

Call recording on stock **Pixel 6+** (Tensor) and other modern Android devices, **without root and without an unlocked bootloader**, via Shizuku.

> 🇺🇦 **Full documentation in Ukrainian:** [`README.md`](README.md). This English version is condensed.
>
> **Status: MVP scaffold (v0.1.0).** Core is in place — AIDL bridge, Shizuku UserService, dual-track recorder with fallback chain, AAC encoder, foreground service, Material 3 Expressive UI. A manual test matrix on real devices is required before public release.

## Why Cally

Stock Pixel call recorder apps **can't capture the other party's voice** — Google blocked `VOICE_CALL/UPLINK/DOWNLINK` for non-privileged apps starting Android 10. Native call recording rolled out in the Pixel Phone app in November 2025 is **not available in Ukraine** (and several other regions); region-switch on Pixel doesn't help — the gate is multi-signal (SIM/MCC, Wi-Fi BSSID, GPS, IP). Other workarounds carry significant compromises (Magisk root with Verified Boot loss; closed binaries; mono mix with volume imbalance).

**Cally bypasses the block via context attribution inside the Shizuku UserService**: our private service runs in a shell-UID process (UID 2000), and `AudioRecord` is created with a context whose attribution matches the real system package `com.android.shell` — a package that exists in the package DB with the same UID 2000 and carries signature-level `RECORD_AUDIO`, `CAPTURE_AUDIO_OUTPUT`, and `MODIFY_AUDIO_ROUTING`. AudioFlinger validates `(uid=2000, pkg="com.android.shell")` against the package DB — the pair is genuine — and opens `VOICE_*` sources as for a system component.

The first 5 layers of this technique are publicly known from [scrcpy 2.0](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/Workarounds.java) (yume-chan, March 2023) where it's used to mirror audio output (`REMOTE_SUBMIX`). Cally **applies it to telephony audio sources** (`VOICE_UPLINK/DOWNLINK/CALL`) — we haven't found a public description of this specific application. Layers 6-8 (live-audibility ladder, FGS bypass combination, signing pin) are this project's engineering, in response to specific telephony-recording challenges (Samsung uplink-silence, mic preamp drift on Pixel 10, daemon attack-surface).

## Stack

- **UI:** Jetpack Compose · Material 3 Expressive · Material You dynamic colors
- **Build:** AGP 8.8 · Kotlin 2.1.20 (K2) · KSP · Gradle 8.11 · JDK 21 toolchain
- **Persistence:** Room 2.7 (call metadata) · DataStore Preferences (settings)
- **IPC:** AIDL Binder · Shizuku 13.1.5 (UserService daemon, `daemon=true`)
- **Recording:** shell-UID `app_process` via Shizuku · `WrappedShellContext` (attribution as `com.android.shell`) · `AudioRecord.Builder().setContext(...)` on main Looper · 5-step fallback ladder with live-audibility verification
- **Encoder:** AAC in MP4 (`MediaCodec` + `MediaMuxer`, default) or WAV (RIFF, optional) · per-track
- **FGS:** `type=specialUse` (mic access lives in shell-process) + invisible 1×1 overlay as bypass for Android 14+ "FGS from background"
- **Network:** INTERNET permission only for **optional** cloud transcription via user-configured OpenAI-compatible chat-completions endpoint with `input_audio` content parts. Disabled completely without an API key. Recording itself never touches the network. No Firebase / Crashlytics / Sentry / analytics.

`minSdk = 31` (Pixel 6+ launched on Android 12), `targetSdk = compileSdk = 36` (Android 16).

## How the bypass works (8 layers)

The full layer-by-layer breakdown is in the Ukrainian README. In short:

1. **Run in shell-UID (UID 2000)** — Shizuku spawns our `RecorderService` inside its `app_process`. In a regular app process, `VOICE_*` sources are blocked by AudioFlinger; shell is a system principal with `signature`-level permissions. Necessary but not sufficient — AudioFlinger checks both UID *and* package.
2. **Context attribution as `com.android.shell`** — `WrappedShellContext` wraps the system Context so identity methods return `com.android.shell`. This is a real `(uid=2000, pkg="com.android.shell")` pair in the package DB, not a fake.
3. **Patch `ActivityThread` reflectively** — AudioFlinger reaches caller identity through `AudioRecord.mAttributionSource ← Context ← Application ← ActivityThread`. We patch `mSystemThread`, `mInitialApplication`, `sCurrentActivityThread`, `mBoundApplication.appInfo`. `HiddenApiBypass` is required for reflection on Android P+.
4. **AudioRecord ctor on the main Looper** — AppOps `RECORD_AUDIO` check reads thread-local hooks empty on Binder threads. We post the ctor to the main Looper via `Handler.post {} + CountDownLatch.await(2s)`.
5. **Builder API with `.setContext(WrappedShellContext)`** — only the API 31+ Builder reads attribution from the passed Context. The legacy 5-arg ctor reads from static `ActivityThread`. This is why `minSdk=31` — not just for Material 3, it's an architectural constraint.
6. **Live-audibility verification + 5-step fallback ladder** — `RecorderController` tries strategies in order: dual `VOICE_UPLINK/DOWNLINK` → `MIC + VOICE_DOWNLINK` (Samsung-friendly) → `VOICE_CALL` stereo → `VOICE_CALL` mono → `MIC` only. Each strategy is verified against `AUDIBLE_THRESHOLD = 0.005` (~-46 dBFS) over a 5-second window. Three strikes before blacklist. Successful strategy is cached per `Build.FINGERPRINT`.
7. **FGS-from-background bypass (Android 14+)** — invisible 1×1 px overlay via `SYSTEM_ALERT_WINDOW` for ~3 seconds combined with FGS `type=specialUse` (not `microphone`, since the actual mic access is in the Shizuku UserService process).
8. **Signing-pin protection against other Shizuku-permitted apps** — `verifyCaller()` runs on every AIDL call and validates UID + SHA-256 release-cert pin. The daemon (`daemon=true`) survives our app being swiped from recents — without the pin, another Shizuku-permitted app could theoretically locate and bind to our Binder.

## Modules

- `:app` — Compose UI, foreground service, encoder, transcription, storage. Imports both `:aidl` and `:userservice`.
- `:userservice` — privileged recorder loaded into the Shizuku-spawned `app_process` (UID shell). All reflection magic (`WrappedShellContext`, `HiddenApiBootstrap`, `AudioRecorderJob`).
- `:aidl` — clean `IRecorderService` Binder contract.

## Prerequisites

- **Pixel 6+ or modern Android device** (Samsung Galaxy S22+ with One UI 5+ supported through fallback strategies)
- **JDK 21** (auto-provisioned via foojay resolver if you only have JDK 17)
- **Android SDK 36** (`compileSdk = targetSdk = 36`)
- **Shizuku** ([shizuku.rikka.app](https://shizuku.rikka.app/)) installed and activated via Wireless Debugging

## Build

```bash
./gradlew :app:assembleDebug                  # debug APK
./gradlew :app:assembleRelease                # release APK (needs keystore.properties)
./gradlew :app:installDebug                   # install to connected device
./gradlew test                                # unit tests
./gradlew :app:lintDebug                      # Android lint
./gradlew :app:connectedAndroidTest           # instrumented (needs device)
```

## Release signing

Drop `keystore.properties` in the project root (gitignored):

```properties
storeFile=/abs/path/to/release.jks
storePassword=…
keyAlias=callrec
keyPassword=…
```

Add a Gradle property with the SHA-256 of your release-cert (lowercase hex, no colons) — `RecorderService.verifyCaller()` will use it as a signing pin:

```bash
keytool -list -v -keystore release.jks -alias callrec | grep "SHA-256" | awk '{print $2}' | tr -d ':' | tr 'A-Z' 'a-z'
echo "callrec.signingSha256=<hash>" >> ~/.gradle/gradle.properties
```

In debug builds `signingSha256` stays empty → verification is skipped, so local development doesn't require pinning.

## Usage

1. Install **Shizuku** from Play Store (or from [shizuku.rikka.app](https://shizuku.rikka.app/)).
2. Activate Shizuku via **Wireless Debugging** (no USB cable needed):
   - Settings → Developer Options → Wireless Debugging → On
   - In Shizuku app → "Pair via Wireless Debugging"
3. Install the Cally APK.
4. On first launch, grant Shizuku permission (system dialog).
5. Make a test call. Recording starts on OFFHOOK and writes two M4A files (uplink + downlink) to `/storage/emulated/0/Android/data/dev.lyo.callrec/files/recordings/`.

## Roadmap (towards v1.0)

- [x] Runtime permissions flow
- [x] AAC encoder (`MediaCodec` + `MediaMuxer`, default)
- [x] Live-audibility verification + 5-step fallback ladder with per-fingerprint cache
- [x] Mix-to-stereo export (for share dialog)
- [x] Contact resolution
- [x] Waveform view (peak-amplitude reducer + Canvas, tap and drag for seek)
- [x] Cloud STT (BYOK OpenAI-compatible) with structured transcript UI
- [x] MediaSession + MediaStyle notification with transport buttons and BT-headset media keys
- [ ] **SAF integration** — `OpenDocumentTree` for external storage, mirror to `MediaStore`
- [ ] **Encrypted vault** — AES-GCM with PIN/biometric
- [ ] **i18n** — English locale (`values-en/strings.xml`)
- [ ] **Reproducible build** — for F-Droid inclusion

## Privacy

- No telemetry, analytics, crash reporting, or any third-party SDKs.
- INTERNET permission only used for **opt-in** cloud transcription. Until you enter an API key, there are zero network requests. The endpoint is user-configurable — you can point at a self-hosted vLLM-Omni server with Qwen2.5-Omni-7B or vLLM with Gemma 4 multimodal for fully local transcription.
- Recordings stay in app-private sandbox. `data_extraction_rules.xml` and `backup_rules.xml` block adb-backup and cloud-restore.
- HTTPS only (`network_security_config.xml`, `cleartextTrafficPermitted=false`).
- See [`PRIVACY.md`](PRIVACY.md) for the full data-flow description and [`docs/threat-model.md`](docs/threat-model.md) for what we defend against and what we explicitly don't.

## Legal context

This README's legal section in the Ukrainian version is specific to **Ukrainian jurisdiction** (one-party consent, Constitution Art. 31, Criminal Code Art. 163, Personal Data Protection Law Art. 25 household exemption).

For users **outside Ukraine**: jurisdictions vary widely. Some countries and US states require all-party consent (CA, FL, MD, MA, MT, NV, NH, IL, PA, WA, CT in the US; Germany under § 201 StGB; significant parts of continental Europe). EU users may also have GDPR obligations on top of criminal law. **Verify your local law before using Cally.** See `README.md` (UA) for the Ukrainian-specific guidance and the international caveats.

## License

[GPL-3.0-or-later](LICENSE). Architectural kinship with [BCR](https://github.com/chenxiaolong/BCR) (chenxiaolong).

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Most-needed contribution: device coverage reports — install on your phone (Pixel/Samsung/Xiaomi/etc.), make a test call, file a `device coverage` issue with results.

## Security

For security vulnerabilities — see [`SECURITY.md`](SECURITY.md). Use private vulnerability reporting, not public issues.

---

Questions / bugs / ideas → issues. PRs with new device matrix entries especially welcome.
