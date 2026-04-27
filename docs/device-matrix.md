# Device compatibility matrix

Реальні результати тестів запису на конкретних пристроях. Заповнюйте через PR (див. [CONTRIBUTING.md](../CONTRIBUTING.md) → «Що корисно прямо зараз»).

## Шкала результату

- ✅ — обидві сторони чути на достатньому рівні (uplink RMS > -40 dBFS, downlink RMS > -40 dBFS), без crash
- ⚠️ — пишеться, але одна зі сторін тиха або з артефактами
- 🟥 — fallback пройшов до MIC-only, downlink відсутній
- ❌ — `STATE_UNINITIALIZED` / SecurityException / падіння

## Pixel

| Пристрій | Android | Patch | Стратегія | Результат | Нотатки |
|---|---|---|---|---|---|
| _додайте свій_ | | | | | |

## Samsung

| Пристрій | Android / One UI | Patch | Стратегія | Результат | Нотатки |
|---|---|---|---|---|---|
| _додайте свій_ | | | | | |

## Інші (Xiaomi, Nothing, OnePlus, Honor, ...)

| Пристрій | Android | Patch | Стратегія | Результат | Нотатки |
|---|---|---|---|---|---|
| _додайте свій_ | | | | | |

## Як заповнювати

1. Установіть debug-білд: `./gradlew :app:installDebug`.
2. Активуйте Shizuku, дайте дозвіл.
3. Зробіть тестовий дзвінок самому собі (на іншу SIM/SIP-номер).
4. У додатку → Settings → Debug подивіться яка стратегія застосувалася (або в logcat: `adb logcat -s RecorderController`).
5. Прослухайте обидві доріжки у плеєрі — оцініть рівень.
6. Внесіть рядок у відповідну таблицю + опціонально приведіть `Build.FINGERPRINT` у нотатках.

Повна репродукування формату: `Build.MANUFACTURER` / `Build.MODEL` (Android `Build.VERSION.RELEASE`, патч `Build.VERSION.SECURITY_PATCH`), стратегія з `RecorderController` (наприклад `DualUplinkDownlink`), результат за шкалою вище.
