# Privacy Policy

**Останнє оновлення: 2026-04-27**

Цей документ — правда про те, як Cally поводиться з даними. Не legalese-боковий-purpose template; намагалися написати по-людськи. Якщо знайдете розбіжність між цим документом і реальною поведінкою додатку — це bug, репортуйте у [SECURITY.md](SECURITY.md).

## Коротко

- Cally **не відсилає телеметрію, аналітику, crash-репорти, чи будь-які діагностичні дані**.
- Cally **не використовує Firebase, Crashlytics, Sentry, Mixpanel, Amplitude, Google Analytics, Facebook SDK** чи будь-які SDK третіх сторін.
- Cally **не має акаунту**, не реєструє вас ніде, не вимагає email чи телефон, нічого не «прив'язує».
- Записи дзвінків **зберігаються локально на вашому пристрої**. Cally сам нікуди їх не відправляє.

## Які дані обробляються

### На пристрої (всередині sandbox додатку)

- **Аудіо телефонних розмов** — coли ви маєте активний дзвінок. Зберігається у `Android/data/dev.lyo.callrec/files/recordings/`. AAC або WAV формат.
- **Метадані запису** — номер, ім'я (з ваших Контактів), час дзвінка, тривалість, який AudioSource спрацював, успіх/невдача. Зберігається у Room базі даних додатку.
- **Транскрипти** — якщо ви активували cloud-транскрипцію. JSON-структура з сегментами, спікерами, тоном. Зберігається разом з метаданими запису.
- **Налаштування** — формат запису, sample rate, теми, API ключ STT (якщо введено), URL endpoint STT, модель STT. Android DataStore Preferences.
- **Cache експортів** — коли ви натискаєте Share, mix-down stereo створюється у `cacheDir/export/`. Android автоматично чистить cacheDir під тиском пам'яті.

### Які системні дозволи запитує Cally і навіщо

| Дозвіл | Навіщо |
|---|---|
| `RECORD_AUDIO` | API-вимога для AudioRecord constructor (фактичний запис відбувається у Shizuku-процесі). |
| `READ_PHONE_STATE` | Detect OFFHOOK / IDLE — щоб автоматично починати/зупиняти запис. |
| `READ_CALL_LOG`, `READ_CONTACTS` | Резолвити ім'я співрозмовника з номера для UI. Контакти не виходять з пристрою. |
| `POST_NOTIFICATIONS` | Notification про активний запис (обов'язкова, оскільки FGS) і про збережений запис. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Тримати recorder live під час дзвінка. |
| `SYSTEM_ALERT_WINDOW` | 1×1 px overlay на ~3 секунди — bypass для Android 14+ "FGS-from-background" блокування. Не показуємо нічого над екраном. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Без exemption Doze killить FGS під час довгих дзвінків. |
| `INTERNET` | **Тільки** для опціональної cloud-транскрипції. До введення API ключа — нуль мережевих запитів. |
| `READ_LOGS`, `WRITE_SECURE_SETTINGS` | Декларовані (для майбутніх фіч), фактично не задіяні у v0.1.0. |
| `moe.shizuku.manager.permission.API_V23` | Bind UserService через Shizuku. |
| `BIND_ACCESSIBILITY_SERVICE` | AccessibilityService — порожній stub. Декларована для exemption на FG service start з фону. **Жодних accessibility events не зчитуємо.** |

## Cloud-транскрипція (опціональна)

Це **опт-ін фіча**. За замовчуванням вимкнена. Активується коли:

1. Ви відкриваєте Налаштування → Транскрипція.
2. Вводите API ключ.
3. Опціонально вибираєте інший endpoint URL (default: OpenRouter), іншу модель (default: Gemini 3.1 Flash).
4. У плеєрі натискаєте «Розпізнати мову» на конкретному записі.

**Що відбувається:**

- Cally відправляє **аудіо файл цього конкретного запису** (як base64 у chat-completions JSON payload з `input_audio` content part) на endpoint URL, який ви вказали.
- HTTPS-only. `network_security_config.xml` забороняє cleartext, навіть якщо ви помилково введете `http://`.
- Bearer-токен (ваш API ключ) у Authorization header.
- Endpoint обробляє, повертає JSON з транскриптом.
- Cally зберігає результат локально, до запису.

**Куди йде аудіо:** туди, куди вказує ваш endpoint URL. За замовчуванням — `https://openrouter.ai/api/v1`. Можна замінити на:

- Інший cloud провайдер (OpenAI, Groq, Together AI тощо)
- Self-hosted сервер (vLLM-Omni з Qwen2.5-Omni-7B або vLLM з Gemma 4 multimodal — у такому випадку аудіо не виходить з вашої мережі взагалі)

**Що Cally **не** робить з аудіо при cloud-транскрипції:**

- Не відсилає копію розробникам Cally
- Не зберігає у нашій базі
- Не відсилає метадані про вас (ваш номер, ваші контакти, час дзвінка)
- Не reuse'ить аудіо для тренування моделей (це залежить від політики endpoint провайдера, не нашої)

**Що залежить від провайдера endpoint:**

Коли ви відсилаєте аудіо на endpoint, политика конфіденційності endpoint-провайдера ВЖЕ застосовується. Ми не контролюємо як OpenRouter / OpenAI / Google / Anthropic обробляють ваш запит. Якщо це для вас critical — використовуйте self-hosted endpoint.

## Хто є data controller (GDPR-термінологія)

- **Cally як software**: не controller і не processor. Software не зберігає дані поза вашим пристроєм.
- **Ви як користувач**: data controller щодо записів власних розмов. Ваша відповідальність — compliance з локальним правом (див. README → Юридичний контекст).
- **Ваш STT endpoint провайдер** (якщо активували cloud-транскрипцію): processor щодо аудіо у момент обробки. Ви — controller.

## Зберігання даних / видалення

- **Видалити одиничний запис**: довге утримання → Видалити у списку записів.
- **Видалити всі дані додатку**: Android Settings → Apps → Cally → Storage → Clear data. Видалить базу, налаштування, всі WAV/M4A файли.
- **Backup**: ми **відключили** Android cloud-backup і device-transfer у `data_extraction_rules.xml` і `backup_rules.xml`. Записи не реплікуються на Google account.

## Дитячі дані (COPPA / GDPR Art. 8)

Cally не призначений для користувачів молодше 16 років і не збирає свідомо їхніх даних. Якщо ви дізналися, що неповнолітній використовує Cally — додаток поводитиметься з його записами так само як з будь-якими іншими (зберігає локально, нічого не відсилає).

## Як ми будемо повідомляти про зміни policy

Зміни цієї privacy policy — у `git log` (репозиторій публічний) і у `CHANGELOG.md` під секцією «Безпека». Material зміни (нові data flows, нові permissions) також оголошуватимуться у release notes відповідного релізу.

## Контакт

Питання щодо приватності — `ua.lyo.su@gmail.com` (тема: `[cally-privacy] ...`). Vulnerability disclosure — окремо у [SECURITY.md](SECURITY.md).
