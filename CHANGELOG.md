# Changelog

Усі помітні зміни — тут. Формат: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), версіонування — [SemVer](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Додано
-

### Змінено
-

### Виправлено
-

### Безпека
-

### Видалено
-

### Застаріле (Deprecated)
-

## [0.5.1] — 2026-08-15

### Виправлено
- **Нотифікація «cally: permission needed» більше не з'являється після кожного дзвінка** ([#13](https://github.com/LyoSU/cally/issues/13), фікс — [@elphamale](https://github.com/elphamale) у [#26](https://github.com/LyoSU/cally/pull/26)). `CallMonitorService.onDestroy` відв'язує наш `ServiceConnection` наприкінці кожного дзвінка, а `ShizukuClient.unbind()` виводив із цього `DaemonHealth.NoPermission` — просто тому, що бінлер самого Shizuku ще живий. Дозвіл при цьому ніколи не відкликався. Тепер `unbind()` перечитує `checkSelfPermission()` і зупиняється на новому стані `DaemonHealth.Idle`, який означає «Shizuku здоровий, ми просто не прив'язані».
- **Головний екран, онбординг і `SetupChecks` більше не показують «Shizuku недоступний» у паузі між дзвінками.** Усі три місця питали `!is DaemonHealth.Bound`, вважаючи будь-який інший стан несправністю. Перевірки переїхали на `DaemonHealth.isFault` — один центр істини для питання «чи є що показувати юзеру». Окремо це помітно в `SetupStatus.probe()`: `refresh()` прив'язується асинхронно, тож читання одразу після нього бачило `Idle` і рапортувало зламане налаштування.

## [0.5.0] — 2026-05-01

### Змінено
- **STT-мікс перероблено на RMS-нормалізоване моно** (`AudioMixer.mixNormalizedMonoForStt`). Раніше слали soft-pan стерео в розрахунку на канальну diarization, але офіційна дока Gemini ([ai.google.dev/gemini-api/docs/audio](https://ai.google.dev/gemini-api/docs/audio)) прямо каже: *«multi-channel audio is automatically combined into a single channel»* — pan ігнорується, а stereo додатково створює timestamp drift ±10%. Реальна причина чому модель чула лише користувача — uplink (mic-direct) на ~12 dB голосніший за downlink (post-codec) у сумі. Тепер кожен бік скейлиться до ~−18 dBFS RMS перед сумою → обидва спікери присутні з порівнянною гучністю в моно-міксі. Промпт оновлено: викинуто інструкцію про лівий/правий канал (брехня для моделі), посилено diarization за voice characteristics. Soft-pan стерео-мікс лишається для sharing/playback де він робить свою роботу для людських вух.

### Виправлено
- **Транскрипція дзвінка більше не зливає весь діалог на «Я»** — наслідок зміни вище. Симптом: на dual-stream записах `Transcriber` повертав сегменти лише з `speaker_id="ME"`, голос співрозмовника або взагалі ігнорувався, або потрапляв у репліки користувача.

### Безпека
-

### Видалено
-

### Застаріле (Deprecated)
-

## [0.4.1] — 2026-04-28

### Виправлено
- **`RecorderController.tryDual` / `trySingle`** — внутрішній `catch (DeadObjectException)` був вужчий ніж зовнішній `catch (RemoteException)` у `openStrategy`, тому всі інші підкласи `RemoteException` (TransactionTooLargeException тощо) ковтались наступним `catch (Throwable) { 0 }` і класифікувались як `InitFailure` → отруювали capability cache. Тепер ловимо весь `RemoteException` ієрархічно, dead-object теж летить тим же шляхом → коректний `Transient` без cache poisoning.
- **`DaemonHealth.NotInstalled` тепер реально досяжний** — `ShizukuClient.recompute()` додатково пробує `PackageManager.getPackageInfo("moe.shizuku.privileged.api", 0)` щоб відрізнити «не встановлений» від «встановлений але не запущений». Раніше обидва випадки сходились у `NotRunning`, нотифікація «cally: Shizuku not installed» і відповідна StepCard в onboarding були мертвим кодом.
- **Onboarding step indices** — на Android 13+ POST_NOTIFICATIONS і «Дозволи системи» обидва мали `index = 4`, користувач бачив «1, 2, 3, 4, 4, 5, 6». Замінено на лічильник `var stepIdx = 4; index = stepIdx++`, що дає консистентне 4/5/6/7 на Tiramisu+ і 4/5/6 на старіших.
- **`AudioLevelMeter` warmup тепер 500 мс реального аудіо** — раніше калібровка тривала 50 викликів `update()`, що при 8KB pump-чанках = ~25 секунд. Pixel ringback (~1 с) попадав у медіану → calibratedFloor завищувався → реальна розмова реєструвалась як тиша → strategy зайво деградувала по драбині. Тепер гейт за `totalFrames >= sampleRate / 2`, що гарантовано 500 мс незалежно від розміру буфера.

### Безпека
- **`SeedDataActivity` (debug-only) тепер перевіряє caller** — раніше `exported=true` без runtime-guard'а дозволяв будь-якому додатку на пристрої з debug-інсталяцією знести БД записів через простий Intent. Додано перевірку `Activity.referrer.host == "com.android.shell" || == packageName` — `am start` з ADB проходить, інші on-device виклики блокуються із Toast'ом «untrusted caller blocked». Спробували `exported=false` спершу, але Samsung One UI 16 рефюзить shell-старти non-exported activities навіть на debuggable-білдах.
- **`CompletedRecordingNotification.visibility = VISIBILITY_PRIVATE`** — раніше було `VISIBILITY_PUBLIC` і lockscreen показував повне ім'я контакту («Дзвінок записано: Джерело — Харків · 3:42»). Реальна OPSEC проблема для T2 (журналіст). Активна нотифікація запису лишається `PUBLIC` за threat-model рішенням «не приховуємо факт запису».

### Змінено
- Прибрано дубльований KDoc-блок над `RecorderController.stop()`.

## [0.4.0] — 2026-04-28

### Додано
- **Voice-memo mode у мануальному записі** — `RecorderController.start(callId, voiceMemo = true)` пропускає всю dual/voicecall fallback-драбину і одразу відкриває `Strategy.SingleMic`. Без call audio path інші стратегії все одно деградували б до MIC, але через 5 сек тиші, з помилковим показом "downlink silent" у банері. Сесії маркуються в БД як `mode = "VoiceMemo"`, CallLog post-mortem пропускається щоб не привʼязати випадковий контакт до диктофонного запису.
- **Multi-speaker діаризація + smart-title в транскрипції** — промпт `Transcriber` оновлено щоб модель повертала `speakers: [{id, label}]` замість фіксованих me/them, плюс поле `title` (короткий опис до 60 символів). Парсер `TranscriptCodec` обробляє нову схему з backward-compat для legacy записів (старе `speaker: "me"|"them"` транслюється у synthetic speakers). У списку записів voice-memo нарешті отримує осмислену назву (наприклад «Список покупок і плани на вихідні») замість дати, як тільки користувач транскрибує.
- **Telegram-style chat layout для транскрипції** — `TranscriptView` рендериться як месенджер: основний спікер (id=ME або перший у списку) праворуч у `primaryContainer`-bubble, інші ліворуч у нейтральному `surfaceContainerHigh`, з ініціал-аватаром (тільки на першій репліці групи), асиметричний `RoundedCornerShape` для bubble-силуету. Час і тон — у нижньому правому кутку bubble.
- **Куратовна 8-кольорова палітра спікерів — лише на аватарах** — кожен унікальний голос отримує власний відтінок (azure / amber / teal / rose / indigo / olive / terracotta / cyan) з окремими варіантами під light і dark теми. Колір живе тільки на ініціал-аватарі і header-лейблі над першою реплікою групи; bubbles нейтральні (TG-pattern: «painted avatar, grey cloud»). Палітра локальна у `PlaybackScreen.chatAccents()` бо M3 `colorScheme` дає лише ~3 container ролі — недостатньо для категоричних кольорів зустрічей з 4+ спікерами.

### Змінено
- **Кнопка зупинення тепер у банері статусу** — раніше була у bottom-right floating toolbar, що погано звʼязувалось зі статусом запису у верхньому банері. Тепер `FilledIconButton` зі Stop іконкою живе праворуч у самому банері, поряд із текстом «Запис увімкнено». FAB прибрано (без дублювання).
- **Список записів і live-meter ховають другу доріжку для voice-memo** — `Levels.voiceMemo: Boolean` пробивається з `RecorderController` у UI; `StatusBanner` рендерить лише uplink meter, RecordingRow показує «Голосовий запис · 28 квіт, 15:42» замість прочерка.
- **Транскрипція рендериться flush, без обгортання у Card** — раніше `TranscriptionSection` була загорнута у `surfaceContainerLow` Card, що зʼїдало ~32 dp padding'у і дублювало візуальну ієрархію (Card-у-Card). Тепер chat-bubbles лежать прямо на playback screen surface, як стрічка повідомлень у Telegram.

### Виправлено
- Імʼя для записів без контакту більше не показується як «—» — для voice-memo коректно показується «Голосовий запис» + дата/час, або AI-title якщо запис уже транскрибовано.

## [0.3.0] — 2026-04-28

### Додано
- **Bypass-health AIDL signal** — `WrappedShellContext` тепер відстежує покриття reflection-патчів (sCurrentActivityThread / mSystemThread / mInitialApplication / mBoundApplication) як `BypassHealth` enum (Failed / Degraded / Full), доступний через новий AIDL-метод `getBypassHealth() = 4`. Calibration ladder в `RecorderController` більше не отруює `Capabilities` cache strategy-failure'ами коли реальна причина — bypass деградація.
- **`DaemonHealth` sealed state machine** — заміняє legacy `ShizukuState` enum + окремий `service: StateFlow<IRecorderService?>`. Шість станів: `NotInstalled / NotRunning / NoPermission / Stale / Bound(service) / Unhealthy(reason)`. Single source of truth для всіх UI-та-сервіс споживачів стану Shizuku/UserService.
- **System notification про daemon health** — нове сповіщення на каналі `callrec.status` з'являється коли `DaemonHealth != Bound` і авто-зникає при відновленні. Tap відкриває setup screen з deep-link state-aware action (`EXTRA_FROM_HEALTH_NOTIF`). VISIBILITY_PRIVATE для privacy.
- **Adaptive noise floor у `AudioLevelMeter`** — `calibratedFloor` навчається як медіана RMS перших ~500 ms семплів; `isAudible` повертає `lastRms > calibratedFloor + AUDIBLE_DELTA (0.008f)`. Замість фіксованого `AUDIBLE_THRESHOLD = 0.005` — поріг adaptive per-stream.
- **`OpenResult` sealed type для класифікації failures** в `RecorderController.openStrategy`: `Success(outcome) / InitFailure(reason) / Transient(reason)`. Transient (DeadObjectException / RemoteException / SecurityException) bail'ять без cache mutation.
- **POST_NOTIFICATIONS opt-in step** в onboarding (Android 13+) — гарантія що daemon health notifications дійсно з'являться користувачу.
- **Onboarding tip card** з рекомендацією community Shizuku build з auto-restart watchdog (`thedjchi/Shizuku`).
- **Debug-only `DaemonHealthDebugActivity`** для триаджу device-matrix issues — показує BypassHealth, DaemonHealth, Capabilities cache. У release не входить (`app/src/debug/`).
- **One-shot health verification на `Lifecycle.State.RESUMED`** через `ProcessLifecycleOwner` — детектить zombie daemon коли користувач відкриває app. Нуль ідл-полінгу (event-driven отбита решта кейсів).

### Змінено
- `CallMonitorService.kickoff()` тепер чекає `health.filterIsInstance<DaemonHealth.Bound>().first().service` замість роздільних `bind()` + `service.filterNotNull()` — single composite signal who subsumes "bound + version match + permission OK".
- `recordingStarted` тепер скидається у `try/finally` блоці `kickoff()` при будь-якій помилці (закриває гонку де STICKY restart бачив `recordingStarted=true` після failed kickoff).
- `OverlayTrick.briefly()` тепер strict-asserts `canShow=true` precondition; перевірка переїхала вище у `CallStateReceiver`, який postить user-visible notification якщо overlay permission відсутній.
- `RecorderController` calibration writes тепер gated на `mutateCache: Boolean = (bypassHealth == Full)` — на Degraded bypass cache не отруюється.
- `MainActivity` має `launchMode="singleTop"` + `onNewIntent` обробляє `EXTRA_FROM_HEALTH_NOTIF` тригерячи health re-check.

### Видалено
- AIDL метод `probeSource = 20` (ніколи не викликався з app — dead surface). Transaction code 20 зарезервовано коментарем.
- AIDL метод `grantPermission = 30` (ніколи не викликався з app — dead surface). Transaction code 30 зарезервовано коментарем.
- `ALLOWED_GRANT_PERMS` companion field у `RecorderService`.
- `READ_LOGS` permission з manifest (тільки `grantPermission` його використовував — обидва видалені).
- `ShizukuState` enum file (заміщений `DaemonHealth`).
- Silent no-op branch в `OverlayTrick.briefly` (тепер strict).
- `bailedOnDeadDaemon: Boolean` flag в `RecorderController` (заміщений семантикою `OpenResult.Transient`).

### Виправлено
-

### Безпека
-

### Застаріле (Deprecated)
-

## [0.2.0] — 2026-04-28

### Додано
- First-run legal disclaimer (ModalBottomSheet) з 3-tier таксономією юрисдикцій: (1) one-party consent — без сповіщення, (2) all-party + implied consent — достатньо попередити, (3) explicit consent — потрібна явна згода (DE/AT/BE). Кожен tier розгортається тапом, відкриваючи legal references (§ 201 StGB, 18 U.S.C. § 2511, CA Penal Code § 632 тощо). Завжди видимий tech-блок про неможливість beep-сповіщення через uplink.
- Settings → Про додаток → "Юридичне попередження" — повторне відкриття того самого sheet'а як read-only.
- Версійований DataStore-флаг `disclaimer_accepted_v1` для майбутнього re-prompt при істотних змінах тексту.
- WAV encoder перезаписує RIFF-хедер кожен ~1 МБ — hard-killed запис залишається відтворюваним.
- DB↔FS reconciliation pass позначає записи, чий аудіофайл був видалений поза додатком.
- Cloud STT API ключ зашифрований at rest через Android Keystore-backed AES/GCM.

### Змінено
- README: секцію «Користувачам поза Україною» переписано в 3-tier структуру (без сповіщення / достатньо попередити / explicit consent) з матрицею-резюме «що дозволено в кожному рівні».
- Номери телефонів і контактні імена більше не з'являються у release logcat на рівні INFO (тільки DEBUG).
- AAC encoder ретраїть EOS input slot до 5 разів під час close — moov atom MP4-контейнера тепер завжди записується.
- Recorder pump joins подовжено до 5 с; якщо pump усе ще заблокований — pipe FD force-close.
- Strategy ladder чесно завершується, коли Shizuku daemon binder вмирає посеред спроби, замість рапортувати "all silent".

### Виправлено
- Release-білди тепер активують verifyCaller signing-cert pin (signingSha256 раніше був порожній).
- Cleanup-запити пропускають записи, що ще тривають (ended_at IS NULL).
- WRITE_SECURE_SETTINGS прибрано з UserService grant allow-list — у v0.2.0 жоден шлях його не використовує.
- Strategy ladder більше не поміщає робочу стратегію у `knownFailedInit`, коли Shizuku daemon binder вмер посеред спроби — кеш вибору залишається валідним.

### UserService
- `userServiceVersion` 10 → 11. AudioRecorderJob.stop() тепер force-close pipe FD при таймауті join, і RecorderService прибрав `WRITE_SECURE_SETTINGS` з allow-list. Daemon з `daemon=true` після оновлення APK respawn'иться автоматично через version mismatch у `ShizukuClient.onServiceConnected`.

### Заплановано
- SAF integration (`OpenDocumentTree` + MediaStore mirror)
- Англомовна локаль (`values-en/strings.xml`)
- Encrypted vault (AES-GCM, PIN/biometric)
- GitHub Actions CI (lint + test + assembleDebug)
- Material 3 ButtonGroup overload migration (5 callsites — `overflowIndicator` parameter)

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

[Unreleased]: https://github.com/LyoSU/cally/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/LyoSU/cally/compare/v0.4.1...v0.5.0
[0.4.1]: https://github.com/LyoSU/cally/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/LyoSU/cally/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/LyoSU/cally/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/LyoSU/cally/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/LyoSU/cally/releases/tag/v0.1.0
