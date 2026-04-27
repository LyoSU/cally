# Release process — обов'язкові правила

Цей документ — **load-bearing**. Порушення правил версіонування призводить до broken upgrades у користувачів (Shizuku daemon mismatch, втрата AccessibilityService activation, signing pin mismatch).

## Правила, які НЕ опційні

### 1. CHANGELOG.md оновлюється у КОЖНОМУ user-visible PR

Кожен PR, що змінює user-visible behavior (UI, фічі, фікси, безпеку, залежності з runtime impact), **обов'язково** додає рядок у `## [Unreleased]` секцію `CHANGELOG.md` під відповідну категорію:

- `### Додано` — нові фічі
- `### Змінено` — зміни поведінки існуючих фіч
- `### Виправлено` — bug fixes
- `### Безпека` — security-relevant зміни
- `### Видалено` — видалені фічі / API
- `### Застаріле (Deprecated)` — будуть видалені у наступних версіях

**Не оновлюються у CHANGELOG:**
- Чисто внутрішній рефакторинг без видимих змін
- Зміни у тестах
- CI/build infra (без впливу на APK)
- Документація (CONTRIBUTING / SECURITY / README — лише якщо це політика-зміна)

Якщо сумніваєтеся — оновіть. Залишити порожнім гірше, ніж зайвий рядок.

### 2. Version bump matrix

Дві окремі версії, що bump'ляться за різними правилами:

| Що змінилось | `versionCode` (app) | `versionName` (semver) | `userServiceVersion` |
|---|---|---|---|
| Будь-який release-білд до користувачів | **+1** обов'язково | за semver | без змін |
| Bug fix (нічого не ламає) | +1 | **patch** (0.1.0 → 0.1.1) | без змін |
| Нова user-visible фіча, backward-compatible | +1 | **minor** (0.1.0 → 0.2.0) | без змін |
| Breaking зміна публічного UI / data format | +1 | **major** (0.1.0 → 1.0.0) | без змін |
| Зміна `IRecorderService.aidl` (методи, signatures, ParcelFileDescriptor semantics) | +1 | за semver | **+1 обов'язково** |
| Зміна `AudioRecorderJob` pump semantics (sample rate handling, FD lifecycle, threading) | +1 | за semver | **+1 обов'язково** |
| Зміна `RecorderService.verifyCaller()` логіки | +1 | за semver | **+1 обов'язково** |
| Зміна `WrappedShellContext` ідентичності або `HiddenApiBootstrap` exemptions | +1 | за semver | **+1 обов'язково** |
| Internal-only refactor у `:userservice` без зміни AIDL/pump/verify/wrap | +1 | за semver | за бажанням |
| Доповнення / виправлення UI у `:app`, що не торкається `:userservice` / `:aidl` | +1 | за semver | без змін |

**Чому `userServiceVersion`**: Shizuku daemon з `daemon=true` живе після нашого APK. Старий daemon з v=10 побачить новий APK з v=11 і респавниться. Без bump'у — daemon продовжує свою stale версію, AIDL transactions не матчаться, користувач отримує silent failure.

**Чому versionCode завжди +1**: Android Package Manager відмовиться оновлювати APK з тим самим versionCode (downgrade protection). Якщо ви будуєте release без bump → користувачі не зможуть оновитись поверх попереднього.

### 3. Signing identity не змінюється між релізами без явного breaking-release

Запитайте себе: чи готові всі користувачі **перевстановити** додаток з нуля (з втратою налаштувань і записів якщо backup-rules заборонив)?

Якщо ні — ви **НЕ можете** змінити:

- `keyAlias`
- Release keystore (новий store = новий SHA-256)
- `applicationId`
- Signing config назагал

Зміна signing → новий APK не вважається upgrade'ом → треба uninstall старого. Якщо ваш use case потребує цього (наприклад rebrand `dev.lyo.callrec` → `dev.lyo.cally`) — це **окремий major-release** з 4-6 тижнями адвансом анонс і migration tooling (export → reimport).

### 4. Tag формат

```
v<MAJOR>.<MINOR>.<PATCH>          # стандартний реліз
v<MAJOR>.<MINOR>.<PATCH>-<pre>    # pre-release: -alpha.1, -beta.2, -rc.1
```

Tag створюється з `main` після того як CHANGELOG секція `[Unreleased]` перейменована у `[X.Y.Z]` з датою.

```bash
git tag -a v0.2.0 -m "Cally v0.2.0 — короткий опис"
git push origin v0.2.0
```

## Release checklist (pre-flight)

Перед `git tag` пройдіться:

- [ ] Усі user-visible зміни з останнього тегу описані у `## [Unreleased]` CHANGELOG.md
- [ ] `## [Unreleased]` перейменовано у `## [X.Y.Z] — YYYY-MM-DD`
- [ ] У CHANGELOG.md під `## [Unreleased]` створено новий порожній заголовок з підсекціями
- [ ] Compare-link унизу CHANGELOG.md оновлений
- [ ] `versionCode` у `app/build.gradle.kts` bump'нуто
- [ ] `versionName` у `app/build.gradle.kts` відповідає новому tag
- [ ] `userServiceVersion` у `userservice/build.gradle.kts` bump'нуто якщо потрібно (див. матрицю вище)
- [ ] `./gradlew test lintRelease assembleRelease` проходить без помилок
- [ ] Release APK перевірено на реальному пристрої (хоча б один Pixel + один Samsung)
- [ ] Release APK перевірено на upgrade поверх попереднього release (не fresh install)
- [ ] Якщо змінено AIDL — **обов'язковий** soak-тест: 5+ дзвінків поспіль після upgrade без рестарту device (перевіряє daemon respawn)
- [ ] Підписаний APK завантажений у GitHub Release разом з SHA-256 у release notes
- [ ] CHANGELOG entry скопійований у release notes
- [ ] Tag створений і запушений

## Rollback

Якщо реліз поламаний:

1. **НЕ робіть `git tag -d` уже запушеного тегу** — користувачі могли клонувати.
2. Випустіть **patch-release** (X.Y.Z+1) з фіксом. Ніколи не «replace» тег.
3. У CHANGELOG під X.Y.Z позначте: `> ⚠️ Цей реліз має критичну ваду — використовуйте X.Y.Z+1.`
4. У GitHub Release натисніть "Mark as pre-release" або видаліть з листка релізів якщо критично.
5. Не видаляйте signed APK з release assets — користувачі вже мають його.

## Хто може tag'ати

Тільки maintainer'и з commit-доступом до `main`. Tag — це публічна обіцянка про сумісність і безпеку. Не tag'айте з feature-branch.
