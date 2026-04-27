# Contributing to Cally

Дякую за бажання допомогти. Цей документ — як долучитися без зайвих кіл.

> **Українська спільнота:** все рідною — issues, PRs, комміти. Англійська теж окей якщо вам зручніше.

## Що корисно прямо зараз

Найбільший дефіцит — **device coverage**. Ми тестували на обмеженій кількості Pixel/Samsung; обхід чутливий до vendor HAL. Якщо ви маєте телефон поза `docs/device-matrix.md` — `./gradlew installDebug`, зробіть тестовий дзвінок, і повідомте у issue:

- `Build.MANUFACTURER`, `Build.MODEL`, `Build.FINGERPRINT`
- Версія Android, патч-рівень
- Яка з 5 strategies спрацювала (видно у Settings → Debug, або в logcat `RecorderController`)
- Чи був передзвінок з гудками, BT-гарнітурою, перемиканням SIM
- Sample uplink/downlink WAV (10-20 сек, можна з тестового дзвінка самому собі)

Внеси результат у `docs/device-matrix.md` і відкрий PR.

## Інші бажані напрямки

- **i18n** — англомовна локаль (`values-en/strings.xml`). Більш екзотичні мови теж welcome.
- **SAF integration** — `OpenDocumentTree` + `MediaStore` mirror.
- **Encrypted vault** — AES-GCM шифрування записів за PIN/biometric.
- **Дизайн** — додаток зараз функціональний; UX-критика і Material 3 Expressive вилизування цінне.

## Workflow

1. Fork → branch (`feat/xyz`, `fix/xyz`, `docs/xyz`).
2. Маленькі логічні комміти (Conventional Commits заохочується: `feat(playback):`, `fix:`, `docs:`).
3. PR проти `main`. Опишіть чому, не що (диф і так видно).
4. Лінк на issue якщо є.
5. **Якщо PR змінює user-visible behavior — оновіть `## [Unreleased]` секцію `CHANGELOG.md`.** Це обов'язково. Деталі правил версіонування і релізів — у [`RELEASING.md`](RELEASING.md).
6. CI має пройти (lint + unit tests + assembleDebug).

## Релізи і версіонування

Ці правила — для maintainer'ів, але важливі і для контриб'юторів (щоб PR не блокувався при підготовці релізу):

- **Кожен release-білд → +1 до `versionCode`** (Android відмовиться upgrade'нути APK з тим самим versionCode).
- **Зміни в `IRecorderService.aidl`, `AudioRecorderJob` pump-логіці, `verifyCaller()`, `WrappedShellContext`, `HiddenApiBootstrap` → +1 до `userServiceVersion`.** Інакше Shizuku daemon з `daemon=true` не respawniться, і AIDL transactions не матчаться → silent failure у користувача.
- Повна матриця і pre-flight checklist — [`RELEASING.md`](RELEASING.md).

## Локальна збірка

```bash
./gradlew :app:assembleDebug                  # APK у app/build/outputs/apk/debug/
./gradlew :app:lintDebug                      # AGP lint
./gradlew test                                # unit тести
./gradlew :app:connectedAndroidTest           # instrumented (потрібен пристрій)
```

Підпис релізу опціональний для розробки — у `app/build.gradle.kts` блок `signingConfigs.release` активується тільки якщо є `keystore.properties` у корені проекту. Інструкція — у README.

## Code style

- Kotlin official code style (`kotlin.code.style=official` у `gradle.properties`).
- Лінт-warning'и не зупиняють збірку (`warningsAsErrors=false`), але не плодимо нові.
- Без коментарів-самоописів; коментар має пояснювати **чому**, не **що**. Якщо коментар повторює назву функції — видаліть.
- Public API → KDoc, internal → можна без.

## Поведінка у спільноті

[Code of Conduct](CODE_OF_CONDUCT.md) (Contributor Covenant 2.1) — тут.

## Безпекові репорти

Не у публічних issues — див. [SECURITY.md](SECURITY.md).

## Ще документація

- [`PRIVACY.md`](PRIVACY.md) — точний perimeter обробки даних.
- [`docs/architecture.md`](docs/architecture.md) — публічний overview архітектури.
- [`docs/threat-model.md`](docs/threat-model.md) — від чого захищаємо, від чого ні.
- [`docs/device-matrix.md`](docs/device-matrix.md) — реальні результати на конкретних пристроях.
- [`RELEASING.md`](RELEASING.md) — обов'язкові правила релізного процесу і версіонування.
- [`CLAUDE.md`](CLAUDE.md) — інструкції для AI-агентів, що працюють з кодом.

## Ліцензія

Усі контрибуції — під GPL-3.0-or-later (як решта коду). Submitting PR = згода на цю ліцензію без додаткового CLA.
