# Architecture

Це публічний overview архітектури Cally — без internal-bypass cookbooks (детальні engineering notes — приватні).

## High-level

```
┌─────────────────────────────────────────────────────────────────┐
│   App process (UID = u0_aXXX)                                   │
│   dev.lyo.callrec                                               │
│                                                                 │
│   ┌──────────────┐  ┌───────────────────┐  ┌────────────────┐   │
│   │ Compose UI   │  │ CallStateReceiver │  │ AAC Encoder    │   │
│   │ (4 screens)  │  │ + CallMonitorSrvc │  │ (MediaCodec +  │   │
│   │              │  │ (PHONE_STATE →    │  │  MediaMuxer)   │   │
│   │ Onboarding   │  │  bind UserSrv)    │  │                │   │
│   │ Primary      │  │                   │  │ AudioMixer     │   │
│   │ Playback     │  │ FGS               │  │ (mix-to-stereo │   │
│   │ Settings     │  │ type=specialUse   │  │  for share)    │   │
│   └──────────────┘  └───────────────────┘  └────────────────┘   │
│        │                    │                       │           │
│        │                    │  Shizuku.bindUserSrv  │           │
│        │                    ▼  (daemon=true)        │           │
│        │            ┌───────────────────┐           │           │
│        │            │ ShizukuClient     │           │           │
│        │            └───────┬───────────┘           │           │
│        │                    │                       │           │
│        ▼                    │                       ▼           │
│   ┌──────────┐               │              ┌──────────────┐    │
│   │ Room DB  │               │              │ FileProvider │    │
│   │ (call    │               │              │ (share with  │    │
│   │  meta)   │               │              │  external)   │    │
│   └──────────┘               │              └──────────────┘    │
│   ┌──────────┐               │                                  │
│   │DataStore │               │                                  │
│   │(settings)│               │                                  │
│   └──────────┘               │                                  │
└─────────────────────────────┼──────────────────────────────────┘
                               │ AIDL (1-way Binder)
                               │ IRecorderService
                               │
┌─────────────────────────────┼──────────────────────────────────┐
│   Shizuku UserService process (UID = 2000 = shell)              │
│   (spawned from app_process by Shizuku)                         │
│                                                                 │
│   ┌──────────────────┐    ┌─────────────────────────────────┐   │
│   │ RecorderService  │    │ AudioRecorderJob × 2 (parallel) │   │
│   │ (AIDL Stub)      │───▶│  - uplink pump                  │   │
│   │ verifyCaller()   │    │  - downlink pump                │   │
│   │  on every call   │    │ AudioRecord with attribution    │   │
│   └──────────────────┘    │ as com.android.shell             │   │
│                           │ (UID 2000 + valid pkg DB entry) │   │
│                           └─────────────────────────────────┘   │
│                                       │                         │
│                                       ▼                         │
│                           ParcelFileDescriptor (PCM frames)     │
└────────────────────────────────────────┼────────────────────────┘
                                         │
                                         ▼
                                  back to app process
                                  AAC encoder writes to
                                  Android/data/.../recordings/
```

## Чому 3 модулі

- **`:aidl`** — окремий Android-library модуль, що містить лише AIDL-контракт. Окремий, бо його імпортують і `:app` (caller-side) і `:userservice` (callee-side); жоден з них не повинен імпортувати другий.
- **`:userservice`** — окремий Android-library модуль для коду, що буде завантажений і виконаний у Shizuku-spawned `app_process` (UID shell). Окремий R8/ProGuard preset (`consumer-rules.pro`) тримає `RecorderService` і `AudioRecorderJob` від minify-rename'у, бо Shizuku їх знаходить через FQCN reflection.
- **`:app`** — звичайний Android application модуль, UI + foreground service + transcription + storage.

Така ізоляція дозволяє:
1. R8 робити агресивний minify на `:app` коді (UI, transcription, storage), не торкаючись load-bearing classes у `:userservice`.
2. Контракт `:aidl` змінювати атомарно — оновлення обох sides одним bump'ом `userServiceVersion`.
3. Аудит security-критичного коду фокусувати на ~6 файлах у `:userservice` замість усього додатку.

## Чому Shizuku, а не root / accessibility / system app

| Підхід | Чому ні |
|---|---|
| **Magisk root + system app** | Зриває Verified Boot. Play Integrity провалиться → банк-апи лажуть. |
| **Magisk + LSPosed hooks** | Аналогічно. + tail risk: LSPosed module breaks на Android update. |
| **AccessibilityService для core recording** | Заборонено політикою Play Store з 2022. Bad faith. |
| **InCallService** | Доступний тільки app, що is default dialer. Користувач має мінятись Phone app — UX killer. |
| **VOICE_RECOGNITION + post-process** | На Pixel Tensor majority calls — uplink-only, downlink dropped. На Samsung — gain mismatch. |
| **`pm grant CAPTURE_AUDIO_OUTPUT`** | Permission protection level `signature\|privileged\|role`. `pm grant` не вміє. |
| **Shizuku** | Користувач опт-ін'ить через Wireless Debugging без USB. Реалізує "privileged-execution-as-needed" без постійного elevated state. |

## Recording strategies (чому fallback ladder)

Ми не покладаємось на одну стратегію тому що різні vendor HAL поводяться по-різному з shell-UID + telephony sources. `RecorderController` пробує по черзі:

1. `VOICE_UPLINK` + `VOICE_DOWNLINK` паралельно — ідеал, два mono треки для незалежного balance у плеєрі.
2. `MIC` + `VOICE_DOWNLINK` — Samsung-friendly: модем часто блокує UPLINK, MIC-uplink через speakerphone path обходить.
3. `VOICE_CALL` stereo — L=uplink, R=downlink якщо HAL розрізняє.
4. `VOICE_CALL` mono — mix-down від HAL.
5. `MIC` only — last resort, через гучномовець.

Кожна спроба перевіряється **live-audibility verification**: 5-секундне вікно з RMS metering обох доріжок проти `AUDIBLE_THRESHOLD` (-46 dBFS). Якщо тиша — strategy «провалилась», переходимо до наступної. «3 страйки» перед blacklist — Samsung модем іноді відкриває path не зразу.

Успішна стратегія кешується по `Build.FINGERPRINT` — наступний дзвінок стартує одразу з неї.

## FGS architecture nuance

Foreground service `CallMonitorService` має **`type=specialUse`**, а не `type=microphone`. Чому:

- Реальний `AudioRecord` живе у Shizuku UserService (окремий процес, UID 2000).
- Наш FGS не відкриває mic безпосередньо — він координує lifecycle, notification, encoding/storage.
- Type=microphone викликав би Android'івське mic-attribution enforcement проти процесу, що не відкриває mic — architecturally неправильно.
- Type=specialUse не subject до Android 14+ "FGS-from-background" рестрикцій.

`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` повний rationale задекларований у маніфесті — Play Store-ready disclosure (хоч ми і не на Play).

Для запуску FGS з фону (з `CallStateReceiver` коли телефон у Doze) використовуємо invisible 1×1 px overlay через `SYSTEM_ALERT_WINDOW` — стандартна exemption Android'а: апи що можуть малювати overlay допускаються до FGS-from-background.

## Daemon lifecycle (Shizuku UserService з `daemon=true`)

UserService спавниться один раз і **продовжує жити після того як наш `:app` процес помирає** або користувач свайпає Cally з recents. Наслідки:

- ✅ Швидкий start запису на наступному дзвінку (0 spawn overhead).
- ✅ Залишається binding'нутим до AudioFlinger session, не треба re-init.
- ⚠️ Daemon — security surface. Будь-який Shizuku-permitted додаток може теоретично binder transact з нашим Stub'ом. Звідси `verifyCaller()` на кожен AIDL-метод: перевірка `Binder.getCallingUid()` + SHA-256 release-cert pin.
- ⚠️ При upgrade APK — daemon перевіряє `BuildConfig.VERSION_CODE_USERSERVICE` проти своєї in-memory копії і респавниться при mismatch. Це чому `userServiceVersion` bump'ається на КОЖНУ зміну AIDL/pump/verify (інакше старий daemon після upgrade тихо breaks).

## Storage layout

```
/storage/emulated/0/Android/data/dev.lyo.callrec/files/
├── recordings/
│   ├── 2026-04-15T14-22-31_+380501234567_uplink.m4a
│   ├── 2026-04-15T14-22-31_+380501234567_downlink.m4a
│   └── ...
└── transcripts/
    └── 2026-04-15T14-22-31_+380501234567.json   (якщо запитано)

cache/
└── export/
    └── <uuid>_stereo.m4a   (тимчасові stereo-mix файли для Share)
```

Метадані (контактне ім'я, тривалість, успіх strategy) — у Room DB у app-private storage (`/data/data/dev.lyo.callrec/databases/`).

## Подальше читання

- [`PRIVACY.md`](../PRIVACY.md) — точний perimeter обробки даних.
- [`docs/threat-model.md`](threat-model.md) — від чого захищаємо, від чого ні.
- [`docs/device-matrix.md`](device-matrix.md) — реальні результати на конкретних пристроях.
- README → «Як працює обхід» — 8-layer stack описаний крок за кроком.
- [`SECURITY.md`](../SECURITY.md) — як репортити security-вразливості.
