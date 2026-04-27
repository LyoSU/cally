# Cally — Pixel Call Recorder

Запис телефонних дзвінків на стоковому **Pixel 6+** (Tensor) і інших сучасних
Android-пристроях, **без root і без розблокованого bootloader**, через Shizuku.

> **Status: MVP scaffold (v0.1.0).** Усе ядро — AIDL-міст, Shizuku
> UserService, dual-track recorder з fallback chain, WAV writer, foreground
> service, Material 3 Expressive UI — реалізовано. Перед публічним релізом
> треба пройти manual test matrix з [«Тестування на реальному пристрої»](#тестування-на-реальному-пристрої)
> на реальному пристрої.

## Чому Cally

Усі звичайні Call Recorder на стокових Pixel **не пишуть голос
співрозмовника** — Google заблокував `VOICE_CALL/UPLINK/DOWNLINK` для
non-privileged додатків з Android 10. Native call recording, який Google
розкотив у Phone app з листопада 2025 року, **в Україні недоступний**:
станом на квітень 2026 підтверджені регіони включають США, Індію,
Німеччину, Італію, Іспанію, Румунію, Францію, Австралію, Канаду,
Ірландію — Google анонсував "all markets" by end of February 2026 перед
запуском Pixel 10a, але Україна не у списку підтримуваних регіонів. Сам
Google каже що feature йде до країн "де call recording не заборонене
законом", з відповідністю disclosure-tone політики країни. Region-switch на
Pixel не допомагає — gate комбінований (SIM/MCC, Wi-Fi BSSID, GPS, IP),
на відміну від Samsung (де CSC-перепрошивка обходить за п'ять хвилин).
Існуючі обхідні шляхи мають суттєві компроміси (root/Magisk з втратою
Verified Boot і Play Integrity; закриті бінарники; моно-мікс із
дисбалансом гучності).

**Cally обходить блокування через context attribution всередині Shizuku
UserService**: наш приватний сервіс запускається у shell-процесі (UID 2000),
і AudioRecord створюється з контекстом, чия attribution співпадає з
реальним системним пакетом `com.android.shell` — пакетом, який існує у
package DB з тим самим UID 2000 і несе signature-level `RECORD_AUDIO`,
`CAPTURE_AUDIO_OUTPUT` та `MODIFY_AUDIO_ROUTING`. AudioFlinger валідує
комбінацію `(uid=2000, pkg="com.android.shell")` проти package DB — пара
справжня — і відкриває `VOICE_*` джерела як для системного компонента.
Деталі — нижче в [«Як працює обхід»](#як-працює-обхід).

### Юридичний контекст для українських користувачів

Україна — **one-party consent** юрисдикція. Запис власних телефонних
розмов учасником цієї ж розмови для особистого використання — легальний
і не вимагає disclosure beep:

- **Конституція України, Стаття 31** гарантує таємницю комунікацій від
  втручання третіх осіб; учасник розмови не є "третьою особою" в сенсі
  цього захисту.
- **КК України, Стаття 163** ("порушення таємниці... телефонних розмов")
  таргетує несанкціоновану інтерсепцію зі сторони, не запис учасником
  власної розмови.
- **ЗУ "Про захист персональних даних" (№ 2297-VI), Стаття 25**
  ("Обмеження дії цього Закону") виключає з-під регулювання обробку даних,
  що здійснюється фізичною особою виключно для особистих чи побутових
  потреб (household exemption, аналог GDPR Art. 2(2)(c)).

Тобто **відсутність beep у Cally — це відповідність українському
законодавству, а не обхід regulatory вимоги** (на відміну від США/EU,
де обов'язковий disclosure beep у нативному рекордері Google зашитий у
Phone app саме для compliance з all-party consent юрисдикціями).

> **Caveat:** запис ≠ розповсюдження. Публікація запису без згоди
> співрозмовника обмежена **ЦК Стаття 306 ч. 2** (право на таємницю
> кореспонденції — листи, телефонні розмови та інша кореспонденція можуть
> використовуватись, зокрема шляхом опублікування, лише за згодою сторін),
> **ЦК Стаття 301** (право на особисте життя), і можливими defamation-позовами.
> Інструмент для **особистого використання**; за публікацію відповідає
> користувач.
>
> Це загальний огляд, не юридична консультація. Для критичних ситуацій
> (журналістика, корпоративні розслідування, докази у складних справах) —
> до українського адвоката, що спеціалізується на information law.

### Користувачам поза Україною

Цей юридичний огляд стосується **виключно української юрисдикції**.
Юрисдикції суттєво різняться:

- **One-party consent** (як Україна): більшість штатів США (federal +
  ~38 штатів), Велика Британія (RIPA 2000), Польща (для учасника),
  Канада, Австралія federal-рівень.
- **All-party consent / two-party consent**: ~11 штатів США (Каліфорнія,
  Флорида, Меріленд, Массачусетс, Монтана, Невада, Нью-Гемпшир, Іллінойс,
  Пенсильванія, Вашингтон, Коннектікут), Німеччина (§ 201 StGB —
  Vertraulichkeit des Wortes), значна частина континентальної Європи
  (DACH-регіон, Італія в певних режимах).
- **GDPR-додатково**: у EU поверх кримінального права діє data-protection
  framework — навіть participant-recording для не-household потреб може
  потребувати legal basis.

**Перш ніж використовувати Cally за межами України — перевірте свій
локальний закон.** Не покладайтесь на українські правила за замовчуванням.
Особливо обережно якщо: ви розмовляєте з кимось у all-party consent
юрисдикції; ви плануєте використовувати запис у корпоративному /
журналістському / судовому контексті, не виключно особистому; ви на
території EU і обробляєте дані повторно (транскрипція, share, архів).

## Стек

| Шар | Технологія |
|---|---|
| UI | Jetpack Compose · Material 3 Expressive (`material3 1.4.0-beta01`) · Material You dynamic colors |
| Build | AGP 8.8 · Kotlin 2.1.20 (K2) · KSP · Gradle 8.11 · JDK 21 toolchain |
| Persistence | Room 2.7 (call metadata) · DataStore Preferences (settings) |
| IPC | AIDL 1-way Binder · Shizuku 13.1.5 (UserService daemon, `daemon=true`) |
| Recording | shell-UID `app_process` через Shizuku · `WrappedShellContext` (impersonate `com.android.shell`) · `AudioRecord.Builder().setContext(...)` на main Looper · 5-step fallback ladder з live-audibility verification |
| Encoder | AAC у MP4 (`MediaCodec` + `MediaMuxer`, default) або WAV (RIFF, опційно) · per-track |
| FGS | `type=specialUse` (mic access живе у shell-процесі) + invisible 1×1 overlay як bypass для Android 14+ "FGS from background" |
| Network | INTERNET permission лише для **опціональної** cloud-транскрипції через user-configured OpenAI-compatible chat-completions endpoint з підтримкою `input_audio` content parts (default: OpenRouter+Gemini Flash; self-hosted — детальніше у [Безпека і приватність](#безпека-і-приватність)). Транскрипція вимикається повністю якщо не введено API ключ. Сам запис аудіо ніколи не торкається мережі. Жодного Firebase/Crashlytics/Sentry/analytics. |

`minSdk = 31` (Pixel 6+ запускався на Android 12), `targetSdk = compileSdk = 36` (Android 16).

## Як працює обхід

Стек із восьми шарів, кожен знімає один конкретний gate
AudioFlinger / framework. Перші п'ять — це **публічна техніка з
[scrcpy 2.0](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/Workarounds.java)**
(yume-chan, березень 2023), де вона використовується для дзеркалення
аудіо-output (`REMOTE_SUBMIX`). Cally **переносить її на telephony
audio sources** (`VOICE_UPLINK/DOWNLINK/CALL`) — публічного опису саме
такого застосування ми не знайшли. Шари 6-8 (live-audibility ladder,
FGS bypass комбінація, signing pin) — інженерія цього проекту,
відповідь на конкретні проблеми telephony-recording (Samsung
uplink-silence, mic preamp drift на Pixel 10, daemon attack-surface).

### 1. Виконуємось у shell-UID (UID 2000)

Shizuku спавнить наш `RecorderService` всередині свого `app_process`
(UID 2000 = `shell`). У звичайному додатковому процесі `VOICE_UPLINK` /
`VOICE_DOWNLINK` / `VOICE_CALL` блокуються AudioFlinger, але shell — це
системний principal з `signature`-level дозволами. Це необхідна, але
**недостатня** умова: просто запуск у UID 2000 не дає аудіо, бо AudioFlinger
дивиться не лише на UID, а й на пакет.

### 2. Атрибуція контексту як `com.android.shell`

`userservice/WrappedShellContext.kt` обгортає системний Context так, що
identity-методи (`getOpPackageName()`, `getPackageName()`,
`getAttributionSource()`) повертають `com.android.shell`. Цей пакет реально
існує у package DB з UID 2000 — тим самим UID, у якому виконується наш
shell-процес. AudioFlinger gate `createFromTrustedUidNoPackage` валідує
комбінацію `(uid=2000, pkg="com.android.shell")` проти package DB і
пропускає, оскільки ця пара — справжня `(uid, pkg)` комбінація для
shell-процесу. Це не fake credentials — це валідна attribution для
процесу, який реально виконується під UID shell.

> Раніше пробували "trusted-UID без пакета" — `AttributionSource.Builder(2000)`
> без `setPackageName(...)`. Не працює: gate валідує пакет проти DB,
> а порожній пакет fail-сить.

### 3. Патчимо ActivityThread рефлексією

Просто override identity-методів Context недостатньо — AudioFlinger
доходить до identity caller-а через ланцюг
`AudioRecord.mAttributionSource ← Context.getAttributionSource() ←
Application.getAttributionSource() ← ActivityThread.currentApplication() ←
ActivityThread.mInitialApplication ← AppBindData.appInfo`.
Якщо хоч одна ланка повертає реальний UID/пакет, gate провалиться. Тому
патчимо приватні поля `android.app.ActivityThread`:

| Поле | Що даємо | Навіщо |
| --- | --- | --- |
| `mSystemThread` | `true` | AudioFlinger та AppOps трактують system-process як pre-authorised, пропускають per-package перевірку |
| `mInitialApplication` | fake `Application`, прив'язаний до `WrappedShellContext` | `currentApplication()` повертає нашу Application |
| `sCurrentActivityThread` | поточний AT | деякі шляхи дістаються AT напряму, оминаючи `currentApplication()` |
| `mBoundApplication.appInfo` | `ApplicationInfo{ packageName="com.android.shell", uid=2000 }` | альтернативний шлях, яким AudioFlinger іноді резолвить пакет |

`org.lsposed.hiddenapibypass.HiddenApiBypass` знімає Android-P+ hidden-API
обмеження, без чого reflection на ActivityThread кидає
`NoSuchFieldException`.

### 4. AudioRecord ctor — обов'язково на main Looper

AudioFlinger AppOps `RECORD_AUDIO` check читає **thread-local hooks**, які
у Binder-потоці shell-процесу порожні. Тому `AudioRecord.Builder().build()`
ми кидаємо на головний Looper процесу через `Handler.post {...}` +
`CountDownLatch.await(2s)`. На головному потоці ActivityThread setup-ить
правильні thread-locals, gate бачить нашу wrapped identity і ctor
повертає `STATE_INITIALIZED`. Реалізація — `AudioRecorderJob.kt:83`.

### 5. Builder API з `.setContext(WrappedShellContext)`

Legacy 5-аргументний AudioRecord ctor читає AttributionSource зі статиків
ActivityThread напряму — наш Context там не бачать. **Тільки**
`AudioRecord.Builder().setContext(wrappedContext).build()` (API 31+) тягне
attribution з переданого Context. Тому `minSdk=31` — це не лише через
Material 3, це **архітектурне обмеження обхіду**.

### 6. Live-audibility verification + 5-step fallback ladder

Ідентифікація — необхідна, але не достатня: на різних HAL модем іноді
повертає тишу, навіть коли AudioRecord ОК. `RecorderController.kt` пробує
стратегії в такому порядку:

1. **DualUplinkDownlink** — `VOICE_UPLINK` + `VOICE_DOWNLINK` паралельно (дві mono доріжки, ідеал для playback з незалежним balance).
2. **DualMicDownlink** — `MIC` + `VOICE_DOWNLINK` (Samsung-friendly: модем часто блокує UPLINK, а MIC bypass).
3. **SingleVoiceCallStereo** — `VOICE_CALL` stereo (L=uplink, R=downlink).
4. **SingleVoiceCallMono** — `VOICE_CALL` mono (mix-down від HAL).
5. **SingleMic** — `MIC` only (last resort, через гучномовець, гарантовано працює).

Кожна спроба:

- ctor → якщо `STATE_UNINITIALIZED`, в `knownFailedInit` назавжди для цього `Build.FINGERPRINT`.
- запис → 5-секундне вікно, RMS metering обох доріжок. Поріг `AUDIBLE_THRESHOLD = 0.005` (≈ -46 dBFS) — нижче відкидаємо як тишу (mic preamp drift на Pixel 10 ≈ -50 dBFS).
- "3 страйки" перед blacklist (Samsung модем іноді відкриває path не зразу — одна мовчазна спроба ≠ перманентний gap).
- успіх → кешуємо в `preferredStrategy`, наступний дзвінок стартує одразу з неї.

При завершенні дзвінка `downgradeIfHalfSilent()` дивиться на `maxRms`
кожної доріжки і скидає тиху сторону (на Samsung часто UPLINK = тиша,
DOWNLINK через sidetone містить обидва голоси — нема сенсу зберігати
2 хв нулів).

Кеш ключований на `Build.FINGERPRINT` → OS update auto-invalidate.

### 7. FGS-from-background bypass (Android 14+)

`telephony.CallStateReceiver` (manifest broadcast) не може стартанути FGS
напряму — Android 14+ блокує це з `PROCESS_STATE_RECEIVER`. Замість
`type=microphone` (який був би жорсткіше gated) ми оголошуємо
`type=specialUse` — реальний mic access живе в Shizuku shell-процесі, не
в нас. Перед `startForegroundService(...)` короткочасно (3 с) додаємо
1×1 px невидимий overlay (`telephony/OverlayTrick.kt`) через
`SYSTEM_ALERT_WINDOW` — система тимчасово піднімає процес у
foreground-state, чого вистачає, щоб FGS легально стартанув. Після цього
overlay прибираємо; FGS вже живий своїм правом.

`accessibility/CallrecAccessibilityService.kt` — порожній stub, оголошений
у маніфесті як **опційний** fallback для агресивних OEM (Xiaomi/MIUI).
BIND_ACCESSIBILITY_SERVICE — теж exemption з FGS-обмеження. Користувач
вмикає його тільки якщо overlay-trick не спрацьовує.

### 8. Захист від чужих Shizuku-permitted додатків

`daemon=true` означає, що privileged-процес живе після нашого app. Будь-який
інший пакет з дозволом Shizuku теоретично може enumerate Binder-и і
покликати наш `IRecorderService`. `RecorderService.verifyCaller()` на
**кожному** AIDL виклику перевіряє:

- `Binder.getCallingUid()` → пакети → має містити `dev.lyo.callrec[.<suffix>]`.
- SHA-256 release-cert підпису → проти константи, запеченої в userservice
  модуль на build-time через Gradle property `callrec.signingSha256`.

У debug-білді signing pin порожній — перевірка пропускається.

## Стійкість і майбутнє

Цей обхід — **не вічний і вже під загрозою**. Чесна оцінка:

**Історичний контекст.** Google систематично закриває non-root шляхи
запису дзвінків кожні 2-3 роки: Android 6 → 9 (VOICE_CALL з public API),
Android 10 (signature|privileged для VOICE_*), Android 11/12 (заборона
accessibility-based recording — вбило ACR Phone), Android 15 (нові
обмеження `MediaRecorder` під час дзвінка). Тренд лінійний.

**Свіжий сигнал тривоги.** У травні 2025 на Android 16 QPR1 Beta зламався
аудіо-захоплення в [scrcpy #6113](https://github.com/Genymobile/scrcpy/issues/6113)
— той самий механізм (`FakePackageNameContext` + `AudioRecord.Builder`),
що використовує Cally. Це ще не цілеспрямована атака на call-recorders,
але збоковий ефект змін в audio framework, який нас зачіпає в лоб. На
stable Android 16 (розкотиться на Pixel восени 2025) Cally може
перестати працювати без оновлення.

**Політичний контекст (двоїстий).** У листопаді 2025 Google глобально
розкотив **native call recording** через Phone by Google для Pixel 6+ на
Android 14+. З обов'язковим disclosure beep, який *"cannot be bypassed"*.
Це дає Google регуляторну позицію проти неофіційних рекордерів у
all-party consent юрисдикціях (США/EU): "у нас є compliant рішення;
обходи без consent — навмисне порушення".

**Для України ця позиція не працює** — native рішення в UA недоступне,
beep юридично не вимагається (one-party consent), Pixel неможливо
region-switch-ити. Тобто Google не має ground для регуляторного тиску
саме на UA-targeted інструмент. Технічний ризик (collateral breakage
типу scrcpy #6113) лишається таким самим, але регуляторна/політична
loud-loud-вилка проти Cally у нашому контексті значно слабша, ніж
проти аналогів у США/EU.

**Найдешевша технічна фікса, на яку очікуємо:** AudioPolicyManager додає
signature check для VOICE_UPLINK/DOWNLINK/CALL — gate валідуватиме не
тільки (uid, package), а й що APK підписаний framework cert. Це ламає
тільки запис дзвінків через impersonation, не зачіпаючи scrcpy чи інші
legitimate debug-tools — тобто Google може зробити це без community
backlash. Один рядок коду; саме цього вектора варто очікувати найбільше.

**Реалістичний горизонт стабільності:** 12-18 місяців на стоковому Pixel
з оновленнями, плюс-мінус регуляторний цикл. Після кожного major Android
release вмикається 5-step fallback ladder — якщо одна стратегія
"погасла", автоматично пробуються інші. Якщо всі п'ять погасли — додаток
чесно показує помилку, не записує тишу мовчки.

**Ранній warning sign:** scrcpy 3.x release notes. Якщо yume-chan напише
"FakePackageNameContext no longer works on Android X, audio capture
removed" — це ваш 6-місячний попереджувальний дзвінок. Підпишіться на
[scrcpy releases](https://github.com/Genymobile/scrcpy/releases).

**Що НЕ обіцяємо:** "вічно працюючий запис дзвінків на Pixel". Якщо
бачите такі обіцянки — або це маркетинг, або небезпечні техніки (root,
custom recovery, фіктивні system apps).

## Структура

```text
callrec/
├── settings.gradle.kts                 # Gradle multi-module config
├── build.gradle.kts                    # root, plugin aliases only
├── gradle/libs.versions.toml           # version catalog (single source of truth)
├── aidl/                               # IRecorderService AIDL contract (no code)
├── userservice/                        # код, що виконується в Shizuku shell-process (UID 2000)
│   ├── RecorderService.kt              #   IRecorderService.Stub — точка входу
│   ├── AudioRecorderJob.kt             #   один пumper на AudioRecord
│   ├── HiddenApiBootstrap.kt           #   обхід hidden-API restrictions через HiddenApiBypass
│   └── ServiceContext.kt               #   reflective system Context для verifyCaller
└── app/                                # звичайний користувацький процес
    ├── recorder/                       #   ShizukuClient + RecorderController (fallback chain)
    ├── codec/                          #   PcmEncoder · WavEncoder
    ├── telephony/                      #   CallStateMonitor · CallMonitorService (foreground)
    ├── storage/                        #   Room (CallRecord) · RecordingStorage
    ├── settings/                       #   DataStore preferences
    ├── notify/                         #   notification channels + recording notif
    ├── di/                             #   manual DI (AppContainer)
    └── ui/                             #   Compose: Onboarding · Home · Library · Playback
```

## Передумови

- **Android Studio Ladybug Feature Drop (2024.3.x) або новіший** — для AGP 8.8 / Kotlin 2.1.
- **JDK 21** на хост-машині. Toolchain в Gradle підтягне Eclipse Temurin 21
  автоматично, якщо в Studio увімкнений Foojay resolver (default з 8.7+).
- **Android SDK 36** + Build Tools.
- **Pixel-пристрій з активованим Shizuku** — для запуску.

## Збірка

1. Клонувати репозиторій.
2. Згенерувати Gradle wrapper jar (один раз):

    ```bash
    # Якщо `gradle` (8.11+) встановлений системно:
    gradle wrapper --gradle-version 8.11.1 --distribution-type bin

    # Або скопіювати з Android Studio:
    cp -r "$HOME/Library/Application Support/Google/AndroidStudio*/distributions/.../gradle-8.11/bin/gradle-wrapper.jar" gradle/wrapper/
    ```

3. Білд:

    ```bash
    ./gradlew :app:assembleDebug                  # debug APK у app/build/outputs/apk/debug/
    ./gradlew :app:assembleRelease                # release (потрібен keystore.properties — див. нижче)
    ./gradlew :app:test :app:lint                 # unit tests + lint
    ./gradlew :app:connectedAndroidTest           # instrumented (потрібен пристрій)
    ```

## Підпис релізу

Створи `keystore.properties` у корені (gitignored):

```properties
storeFile=/абсолютний/шлях/до/release.jks
storePassword=…
keyAlias=callrec
keyPassword=…
```

І додай Gradle property з SHA-256 свого release-cert (lowercase hex, без двокрапок) —
`UserService.verifyCaller()` використає її як signing pin:

```bash
# отримай SHA-256:
keytool -list -v -keystore release.jks -alias callrec | grep "SHA-256" | awk '{print $2}' | tr -d ':' | tr 'A-Z' 'a-z'

# збережи як Gradle property (~/.gradle/gradle.properties):
echo "callrec.signingSha256=<hash>" >> ~/.gradle/gradle.properties
```

У debug-білді `signingSha256` лишається порожнім → перевірка пропускається,
щоб локальна розробка не вимагала pinning.

## Використання

1. Встанови **Shizuku** з Play Store (або з [shizuku.rikka.app](https://shizuku.rikka.app/)).
2. Активуй Shizuku через **Wireless Debugging** (без USB-кабеля):
   - Settings → Developer Options → Wireless Debugging → On
   - У Shizuku app → "Pair via Wireless Debugging"
3. Встанови Cally APK.
4. На першому запуску дай Shizuku-permission (system dialog).
5. Зроби тестовий дзвінок. Запис стартує на OFFHOOK і пише два WAV у
   `/storage/emulated/0/Android/data/dev.lyo.callrec/files/recordings/`.

## Тестування на реальному пристрої

Перед першим виданням — пройди **обов'язкові перевірки**:

- [ ] Shizuku не активний → graceful UI з install кнопкою
- [ ] BT-гарнітура під час запису → recording продовжується або gracefully зупиняється
- [ ] 5+ послідовних дзвінків → daemon стабільно перевикористовується
- [ ] Перезавантаження → daemon треба пересоздавати (Shizuku-сервер теж рестартується)
- [ ] Samsung S22+ → fallback chain доходить до MIC, без падінь

Веди реальну таблицю: `docs/device-matrix.md` (поки порожня — поповнюй на кожному
тестовому пристрої).

## Що залишилось зробити (TODO для повноцінного v1.0)

- [x] **Runtime permissions flow** — `permissions/AppPermissions.kt` + `SetupChecks.kt`.
- [x] **AAC encoder** — `codec/AacEncoder.kt` (MediaCodec + MediaMuxer, default). WAV — опційно.
- [x] **Live-audibility verification** — `RecorderController` + 5-step ladder з кешем по `Build.FINGERPRINT` (замінило first-call calibration).
- [x] **Mix-to-stereo export** — `codec/AudioMixer.kt` + share-діалог у плеєрі (`ui/playback/Sharing.kt`, кеш у `cacheDir/export/`).
- [x] **Contact resolution** — `contacts/ContactResolver.kt` + `CallLogResolver.kt`.
- [ ] **SAF integration** — `OpenDocumentTree` для зовнішнього сховища, дзеркало в `MediaStore`
      щоб записи були видимі у будь-якому файл-менеджері.
- [x] **WaveformView** — `codec/Waveform.kt` (peak-amplitude reducer) + `ui/playback/WaveformView.kt` (Canvas, тап + драг для seek; для dual — дві дзеркальні доріжки).
- [ ] **i18n** — англомовна локаль (зараз тільки uk-UA).

## Безпека і приватність

- **Жодного Firebase / Crashlytics / Sentry / analytics / telemetry.** Перевірити можна grep'ом — нуль cloud-SDK у залежностях.
- **INTERNET permission присутній**, але задіяний винятково для опціональної cloud-транскрипції. Поки користувач не ввів API ключ у Налаштуваннях, мережевих запитів немає взагалі. Endpoint user-configurable — можна вказати self-hosted сервер. Технічна вимога: endpoint має підтримувати OpenAI chat-completions API з `input_audio` content parts (формат gpt-4o-audio / Gemini multimodal, **не** Whisper transcription API). Робочі self-hosted варіанти станом на 2026: **vLLM-Omni з Qwen2.5-Omni-7B** ([docs.vllm.ai/projects/vllm-omni](https://docs.vllm.ai/projects/vllm-omni/)), **vLLM з Gemma 4 E2B/E4B** (audio multimodal, mid-2025+). Standard vLLM serve для Qwen2.5-Omni наразі тільки text output (thinker mode); для audio-input через chat-completions потрібен саме vLLM-Omni fork. Whisper-only сервери (whisper.cpp, faster-whisper) поточним кодом не підтримуються — у них інший endpoint format. У self-hosted режимі аудіо не покидає вашу мережу.
- **Сам запис аудіо ніколи не торкається мережі.** Файли пишуться у `Android/data/dev.lyo.callrec/files/recordings/` і залишаються там до експорту самим користувачем.
- `RecorderService.verifyCaller()` перевіряє UID + SHA-256 release-cert на КОЖНИЙ AIDL-виклик. З `daemon=true` сервіс живе після нашого app — це захист від іншого Shizuku-permitted додатку, який міг би теоретично знайти наш Binder.
- `data_extraction_rules.xml` + `backup_rules.xml` забороняють adb-backup та cloud-restore.
- `network_security_config.xml` забороняє cleartext traffic — bearer-токен користувацького API ключа не може просочитися навіть якщо typo / редірект downgrade'не схему.
- Notification про активний запис — `setOngoing(true)` + `VISIBILITY_PUBLIC`. Не приховуємо.

> **Caveat про API ключ:** ключ для STT-транскрипції зберігається у Android DataStore (app-private sandbox storage) у відкритому вигляді. На розблокованому пристрої без root доступу його прочитати може лише сам додаток; на рутованому або forensic-витягнутому образі — будь-хто з shell. Якщо ваша модель загроз цього не толерує — не використовуйте cloud-транскрипцію або вкажіть локальний self-hosted endpoint.

## Ліцензія

GPL-3.0-or-later — повний текст у [`LICENSE`](LICENSE). Спорідненість архітектури з BCR (chenxiaolong).

---

Питання, баги, ідеї — issues. Pull requests з нових device матриць — особливо вітаються.
