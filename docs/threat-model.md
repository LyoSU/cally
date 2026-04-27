# Threat model

Цей документ — про те, від чого Cally захищає, від чого **не захищає**, і чому. Privacy-tool без чіткого threat model = false sense of security.

## Цільова аудиторія (хто типовий користувач)

Three personas, що визначають design trade-offs:

1. **Privacy-conscious individual** — хоче зберегти свої розмови (медичні консультації, банківські переговори, договірні домовленості усно). Не довіряє Google native call recording. Не може / не хоче рутувати телефон.
2. **Журналіст / дослідник у воєнний час** — потребує evidence-grade записи інтерв'ю / комунікацій з джерелами. Загроза — RU/BY surveillance, withdraw запитів від influence-операцій, hardware compromise.
3. **Юрист / consultant** — потребує chain-of-custody-friendly записи переговорів для подальшого використання у судовій справі. Загроза — tampering, plausible deniability, корупційний тиск.

## Threat actors і їх можливості

### T1. Скорпорейтні surveillance (Google, Meta, etc.)

**Можливості:** Google контролює AOSP, Pixel firmware, Phone app, Play Services, Android update channel.

**Що ми робимо проти:**
- Не використовуємо Google services. Жодного Firebase / Crashlytics / Play Services API.
- Не на Play Store (не дамо Google можливість banning + видалення з пристроїв через Play Protect).
- HTTPS only для STT (не зливаємо bearer-token незашифровано).
- AudioRecord створюється не через Phone app — мінімізуємо шлях, де Google може hook'нутись.

**Що ми не робимо:**
- Не захищаємо від Google Play Protect (якщо встановлено) marking app як «harmful» — користувач може мусити dismiss warning.
- Не захищаємо від firmware-level capture з боку OEM (Samsung Knox, Xiaomi MIUI tracker, etc.).
- Не захищаємо від breaking change з боку AOSP — наступне Android-оновлення може closed bypass.

### T2. State-level adversary (RU, BY, ін. authoritarian regimes)

**Можливості:** legal compulsion of cloud providers, network-level traffic analysis, malware deployment targeting specific individuals.

**Що ми робимо проти:**
- Audio stays on-device by default (без введення API key — нуль network).
- Self-hosted endpoint опція — повністю усуває cloud залежність.
- Backup-rules excludes — нема cloud-replication через Google.
- `verifyCaller()` + signing pin — не дозволяє іншому Shizuku-permitted додатку тихо bind'нутись до нашого daemon.

**Що ми не робимо:**
- Не захищаємо від physical seizure з розблокованим телефоном (записи в app sandbox видимі через `pm list packages` + `/data/data/...`).
- Не захищаємо від forensic image знятого з вимкнутого rooted телефона.
- Не захищаємо від targeted malware на тому ж пристрої з root.
- Не захищаємо від traffic analysis (timing/size) при cloud-транскрипції — pattern «дзвінок → за 5хв payload ~5MB на known endpoint» легко детектується passive observer'ом на мережі.

### T3. Jealous spouse / corporate espionage / targeted opportunist

**Можливості:** physical access to unlocked phone for short period, social engineering, ADB exploitation.

**Що ми робимо проти:**
- App-private storage — нероутова shell з sideload'нутого malware не дотягнеться до записів без явного доступу.
- ADB backup disabled у `backup_rules.xml`.
- Persistent recording notification (`setOngoing(true)`, `VISIBILITY_PUBLIC`) — не приховуємо факт активного запису.

**Що ми не робимо:**
- Не лочимо додаток PIN'ом / biometric (планується у v0.x). Будь-хто з розблокованим телефоном може відкрити і прослухати.
- Не шифруємо записи на диску — vault feature на roadmap, не у v0.1.0.
- API ключ STT зберігається plaintext у DataStore (sandbox). Корінний adversary витягне.

### T4. Сам користувач, що ловиться у all-party-consent юрисдикції

**Можливості:** сам користувач випадково записує без відома співрозмовника у юрисдикції, що цього вимагає.

**Що ми робимо:**
- README → Юридичний контекст + Користувачам поза Україною — попередження про різні правові режими.
- Notification про активний запис ВИДИМА (`VISIBILITY_PUBLIC`) — не приховуємо запис від співрозмовника якщо він гляне на екран.
- Onboarding hint про legal disclaimer.

**Що ми не робимо:**
- Не emit'ємо disclosure beep автоматично — це політичне рішення (UA legal context не вимагає, embedding це у tool суперечить privacy-by-default дизайну). Користувач може ввімкнути ringtone-через-speaker самостійно якщо хоче.
- Не визначаємо локацію автоматично, щоб не накласти юрисдикційний gating.

### T5. Compromise через ланцюг постачання (supply chain)

**Можливості:** malicious dependency, malicious commit після compromise maintainer account.

**Що ми робимо проти:**
- GPL-3.0 + публічний source — хто завгодно може audit.
- `gradle/libs.versions.toml` — single source of truth для версій залежностей. Easier to audit.
- Жодних binary blobs у source (немає prebuilt .aar / .so).
- Signing key — окремий від dev-машини, signing config gitignored.
- Reproducible build — у roadmap (потрібен на v1.0 для F-Droid).

**Що ми не робимо:**
- Не verify checksum'и transitive dependencies. Compromise maven central артефакту detect'нути не вміємо.
- Не маємо CI з ізольованою signing-машиною (signing локально у автора).
- Single-maintainer проєкт — bus factor 1. Якщо автор compromised, projект compromised.

## Out-of-scope

Cally **навмисно не намагається** захистити від:

1. **Compromised телефон** (root malware, OEM backdoor, hardware-level keylogger).
2. **Compromised endpoint provider** при cloud-транскрипції — вибір endpoint це ваша відповідальність.
3. **Voice cloning / deepfake risk** — Cally робить запис, але якщо хтось використає цей запис щоб клонувати ваш голос для шахрайства, це не наша поверхня атаки.
4. **Network analysis traffic correlation** — Tor support не плануємо, бо OpenAI-compat endpoints зазвичай Tor-блокують.
5. **VoIP** — фундаментально неможливо без accessibility-tap або system app status. Telegram/WhatsApp/Signal принципово вимикаються.
6. **Recording-detection by counterparty** — деякі мобільні платформи вже додають watermark / inaudible signal детекції що йде запис. Ми не fight з цим. Якщо counterparty детектує — to bad.

## Ризики, що залишаються відкритими (відомі gaps)

- ❌ Encrypted vault — поточно нема. Записи у app-private storage, але незашифровані.
- ❌ App-lock (PIN/biometric) — нема.
- ❌ Tamper-evidence (chain-of-custody) — нема hash-chain між сегментами.
- ❌ Reproducible build — нема.
- ❌ Multiple maintainers — нема.
- ❌ Hardware-keyed encryption — нема StrongBox / Titan integration.
- ❌ TLS pinning — використовуємо системний trust store. MITM з compromised CA можливий (low likelihood).

Усі ці gaps — у roadmap. Не приховуємо.

## Що користувач може робити сам для покращення

1. **Не активуйте cloud-транскрипцію** якщо вам не комфортно з тим що audio передається на endpoint. Транскрипт можна записати руками з прослуховування.
2. **Self-host endpoint** якщо потрібна транскрипція без cloud — vLLM-Omni з Qwen2.5-Omni-7B або vLLM з Gemma 4 audio-multimodal на власному GPU-сервері (потрібен endpoint що підтримує OpenAI chat-completions з `input_audio`).
3. **Розблокуйте телефон тільки коли треба**, тримайте PIN/biometric.
4. **Регулярно експортуйте** і чистіть старі записи (cleanup-job вже це робить за політикою).
5. **Перевіряйте notification** — якщо запис активний а ви цього не очікуєте, щось не так.
6. **GitHub releases SHA-256** — verify checksum завантаженого APK перед install.
