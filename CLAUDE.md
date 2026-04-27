# CLAUDE.md

Інструкції для Claude Code / іншого AI-агента, що працює з цим репо. Якщо ви людина — все одно корисно прочитати.

## Що це

**Cally** — Android call recorder для Pixel 6+ та інших сучасних Android-пристроїв через Shizuku, без root і unlocked bootloader. Обхід Google'івського блокування `VOICE_*` AudioSource через UID-impersonation `com.android.shell` всередині Shizuku UserService.

GPL-3.0-or-later. UA-проєкт.

## Архітектура (3 Gradle-модулі)

- **`:app`** — Compose UI (Material 3 Expressive), foreground service, AAC encoder, MediaSession, транскрипція. JVM toolchain 21.
- **`:userservice`** — Privileged recorder, що запускається у Shizuku-spawned `app_process` (UID 2000 = shell). Тут вся reflection-магія: `WrappedShellContext`, `HiddenApiBootstrap`, `AudioRecorderJob`.
- **`:aidl`** — Чистий контракт `IRecorderService` для Binder-IPC між app- і shell-процесом.

Залежності: `gradle/libs.versions.toml` (single source of truth, version catalog).

## Load-bearing identifiers — НЕ перейменовувати без big-bang refactor

| Ідентифікатор | Де живе | Чому load-bearing |
|---|---|---|
| `dev.lyo.callrec` | applicationId, namespace, всі Kotlin-package paths, AIDL paths | Signing pin (`callrec.signingSha256`) bound to applicationId. ShizukuProvider authority = `${applicationId}.shizuku`. FileProvider authority = `${applicationId}.fileprovider`. Кожен `.kt` файл імпортує `dev.lyo.callrec.*`. |
| `dev.lyo.callrec.userservice` | `:userservice` namespace, AIDL `Stub.asInterface` reflection | UserService daemon class FQCN — Shizuku запускає по reflection. R8 keep rule prevents minification rename. |
| `keyAlias=callrec` | release keystore | Якщо рінейм — потрібен новий keystore + новий signingSha256 hash + всі users перевстановлюють (signature changed). |
| `callrec.signingSha256` | Gradle property name | Hardcoded у `userservice/build.gradle.kts:21` як `BuildConfig.APP_SIGNING_SHA256`. Зміна імені property → CI/CD breaks. |
| `CallrecAccessibilityService` | Class name + manifest entry + accessibility_service_config.xml | Користувачі активують у системних Settings → Accessibility; рінейм після релізу = втрата активації у всіх existing users. |

**Бренд** = «Cally» (README, app_name, rootProject.name). **Внутрішні ідентифікатори** = `callrec` (legacy від першої ітерації). Це **навмисна** інконсистентність: бренд може еволюціонувати, signing identity не може.

Якщо колись треба буде повний rebrand `callrec → cally` — робити окремою feature branch, не змішувати з функціональними змінами, bump versionCode userservice (`userServiceVersion` у `userservice/build.gradle.kts`) обов'язково.

## Critical files (не ламайте)

- `userservice/.../WrappedShellContext.kt` — серце обходу. Кожен метод має конкретну причину існування. Зміни тут — лише з повним розумінням chain'а `AudioRecord.mAttributionSource ← Context ← Application ← ActivityThread`.
- `userservice/.../AudioRecorderJob.kt` — pump на main Looper (AppOps thread-locals). Не переносити на Binder thread.
- `userservice/.../RecorderService.kt::verifyCaller()` — runs on **every** AIDL call. UID + SHA-256 release-cert pin. Debug builds skip verification (signingSha256 порожній).
- `app/.../recorder/RecorderController.kt` — 5-step fallback ladder. Порядок strategies — load-bearing для compatibility matrix; зміни → device-matrix регресія.
- `aidl/src/main/aidl/.../IRecorderService.aidl` — будь-яка зміна = bump `userServiceVersion` у `userservice/build.gradle.kts`. Daemon з `daemon=true` живе після upgrade APK і respawn'ить себе якщо version mismatch.

## Disclosure hygiene (важливо)

Цей проєкт — clean-room implementation. Деякі файли і нотатки що лежать **локально** у автора не належать публічному репо. Правила:

- **`IMPLEMENTATION_GUIDE.md` — gitignored, приватний.** Внутрішній engineering brief. Може бути адаптований під technical write-up / paid material пізніше. **Ніколи не commit'ити випадково**, не цитувати у публічних артефактах (коміт-меседжах, PR descriptions, issues, код-коментарях).
- **У публічному коді / коментарях / README / commit-меседжах / PR / issues — описуйте лише власну архітектуру.** Назагал не згадуйте конкретні комерційні / закриті додатки, їх обфусковані class names, package IDs, internal symbol names або імплементаційні деталі. Generic порівняння («the leading proprietary call recorder», «closed-source competitor», «commercial Android voice-recording apps») — okay. Конкретні product references з internal деталями — ні.
- Технічна частина обходу (`WrappedShellContext`, `HiddenApiBootstrap`, fallback ladder) — наша власна інженерія, публічна, описана у README. Атрибуція scrcpy/yume-chan за перші 5 layers — обов'язкова (вона у README є).
- Якщо у задачі агентом потрібно посилатись на закритий продукт — задайте автору питання, **не** робіть це самостійно.

## Заборонені залежності і фічі

- ❌ Firebase / Crashlytics / Sentry / Mixpanel / Amplitude / будь-яке analytics
- ❌ AccessibilityService для core-функціональності (порожній stub-сервіс ОК — він тільки для FGS-from-background exemption)
- ❌ INTERNET для чогось окрім user-opt-in транскрипції
- ❌ Read від CallLog / Contacts окрім matadata резолвинг для UI
- ❌ Будь-який «phone home» — version checks, telemetry, error reporting

User-configurable cloud STT — окей (вони самі вирішили, ввели API ключ, вибрали endpoint). Все інше через мережу — ні.

## Build & test commands

```bash
./gradlew :app:assembleDebug              # Debug APK
./gradlew :app:assembleRelease            # Release APK (потребує keystore.properties)
./gradlew :app:installDebug               # Install to connected device
./gradlew test                            # Unit tests
./gradlew :app:lintDebug                  # Android lint
./gradlew :app:connectedAndroidTest       # Instrumented (потрібен device)
adb logcat -s RecorderController RecorderService AudioRecorderJob  # bypass debugging
```

JDK 21 toolchain (auto-provisioned через foojay resolver). Gradle 8.11 + AGP 8.8 + Kotlin 2.1.20 (K2). KSP не kapt.

## Code style

- Kotlin official style (`kotlin.code.style=official`)
- Conventional Commits заохочуються: `feat(playback):`, `fix:`, `docs:`, `chore:`
- **Без коментарів-самоописів** — коментар має пояснювати **чому**, не **що**. Якщо коментар повторює назву функції чи описує зрозумілу з коду логіку — видалити.
- KDoc на public API в `:userservice` (там reflection-магія, треба пояснення).
- Без emojis у коді.
- Файли мають `// SPDX-License-Identifier: GPL-3.0-or-later` зверху (всі 65 .kt файлів вже мають).

## Що НЕ робити (типові помилки агента)

- НЕ створюйте файли документації (CHANGELOG-додаткові, ARCHITECTURE.md, ROADMAP.md тощо) без явного запиту. Поточний набір достатній.
- НЕ міняйте `material3` версію без перевірки release-notes alpha → alpha (1.5.0-alpha14 — Material 3 Expressive surface вже public, до цього було internal).
- НЕ додавайте Hilt, Dagger, Koin — DI у проєкті ручний (`AppContainer`), і це навмисно.
- НЕ переходьте на Compose Navigation typed routes без consideration — поточний string-based безкоштовний для розміру проекту.
- НЕ копіюйте код з інших call recorder'ів (open- чи closed-source) дослівно — clean-room implementation. Якщо потрібен ідейний орієнтир — описуйте власну імплементацію поверх ідей, не копіюйте символи / структури класів.
- НЕ додавайте `pm grant CAPTURE_AUDIO_OUTPUT` до debug-flow — не спрацює (signature|privileged|role permission), даремна спроба.

## Memory та контекст

Файл `IMPLEMENTATION_GUIDE.md` (gitignored, локальний) — глибокий внутрішній технічний brief. Якщо присутній локально — можна читати для контексту, але **ніколи не цитувати у публічних артефактах** (commit messages, PR descriptions, issue replies, код-коментарях). Окремі секції цього файлу є sensitive і призначені для приватного використання.

## Releases і версіонування

**Повні правила — у [`RELEASING.md`](RELEASING.md). Не імпровізуйте.**

Поточна версія: `versionCode = 1`, `versionName = "0.1.0"`, `userServiceVersion = 10`.

Стислі обов'язкові правила:

- Кожен release-білд → **+1 до `versionCode`** (Android refuses upgrade with same code).
- Зміна `IRecorderService.aidl` / `AudioRecorderJob` pump / `verifyCaller()` / `WrappedShellContext` / `HiddenApiBootstrap` → **+1 до `userServiceVersion`** (інакше Shizuku daemon не respawniться).
- Кожен user-visible PR → **обов'язкове оновлення `## [Unreleased]` секції `CHANGELOG.md`** (Keep a Changelog format).
- Tag формат: `v<MAJOR>.<MINOR>.<PATCH>`. GitHub Release з підписаним APK + SHA-256 у release notes.

Pre-flight checklist перед `git tag` — у [`RELEASING.md`](RELEASING.md). Виконати ВСЕ.
