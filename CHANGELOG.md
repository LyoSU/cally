# Changelog

Усі помітні зміни — тут. Формат: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), версіонування — [SemVer](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Заплановано
- SAF integration (`OpenDocumentTree` + MediaStore mirror)
- Англомовна локаль (`values-en/strings.xml`)
- Encrypted vault (AES-GCM, PIN/biometric)
- GitHub Actions CI (lint + test + assembleDebug)
- Скріншоти у README

## [0.1.0] — 2026-04-27

Перший публічний реліз. MVP scaffold.

### Додано
- AIDL-міст `:aidl/IRecorderService` між app-процесом і Shizuku UserService.
- Shizuku UserService з `daemon=true`: процес живе після свопу додатку з recents.
- Privileged recorder у shell-процесі (UID 2000) через `WrappedShellContext` (impersonation `com.android.shell` для проходження AudioFlinger gate).
- 5-step fallback ladder з live-audibility verification, кеш по `Build.FINGERPRINT`:
  1. `VOICE_UPLINK` + `VOICE_DOWNLINK` (dual paralel)
  2. `MIC` + `VOICE_DOWNLINK` (Samsung-friendly)
  3. `VOICE_CALL` stereo (L=uplink/R=downlink)
  4. `VOICE_CALL` mono
  5. `MIC` only (last resort)
- AAC-в-MP4 encoder за замовчуванням (`MediaCodec` + `MediaMuxer`), WAV опційно.
- Foreground service `type=specialUse` + invisible 1×1 overlay для FGS-from-background bypass на Android 14+.
- Material 3 Expressive UI (theme, motion, shape morphing, wavy LoadingIndicator, FloatingToolbar).
- 4 екрани: Onboarding, Primary (recordings list), Playback, Settings.
- Auto-start recording на `OFFHOOK` через `CallStateReceiver`.
- Контакти і call log resolution для metadata записів.
- Mix-to-stereo export з кастомним balance (для share-діалогу плеєра).
- Waveform view (peak-amplitude reducer + Canvas, тап і драг для seek).
- Транскрипція через user-configured OpenAI-compatible endpoint (default OpenRouter+Gemini Flash); opt-in, вимкнена без API ключа.
- Tap any transcript bubble для seek+play цього сегменту.
- MediaSession + MediaStyle notification з transport buttons і BT-headset media keys.
- Notification про збережений запис після кожного дзвінка.
- `verifyCaller()` на КОЖНИЙ AIDL-виклик: UID + SHA-256 release-cert pin.
- Per-app мовний конфіг (Android 13+) — наразі лише uk-UA.

### Безпека
- `cleartextTrafficPermitted=false` у `network_security_config.xml`.
- `data_extraction_rules.xml` + `backup_rules.xml` блокують adb-backup і cloud-restore.
- Hidden API exemptions scoped до 5 framework prefixes (defence-in-depth, не `""`).
- Жодного Firebase / Crashlytics / Sentry / analytics.

### Відомі обмеження
- Тільки uk-UA локаль.
- VoIP (WhatsApp/Telegram/Viber/Signal) принципово не підтримується — telephony audio path не задіяний.
- Bluetooth-гарнітура під час дзвінка може зламати запис на деяких HAL.
- Samsung One UI 5.1+ потребує fallback на MIC-only стратегії — VOICE_* з shell UID повертає тишу.

[Unreleased]: https://github.com/lyo-/cally/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/lyo-/cally/releases/tag/v0.1.0
