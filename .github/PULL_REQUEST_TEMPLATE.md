<!--
Дякуємо за PR. Чекліст нижче — щоб ми не питали базові речі коментарями.
Якщо щось не релевантне — просто закресли або видали.
-->

## Що змінилось

<!-- Короткий опис: чому, не що (диф і так видно) -->

## Тип зміни

- [ ] Bug fix
- [ ] Нова фіча
- [ ] Breaking change (зміна публічного UI / behavior / data format)
- [ ] Зміна `IRecorderService.aidl` / `AudioRecorderJob` / `verifyCaller()` / `WrappedShellContext` (потребує bump `userServiceVersion`)
- [ ] Документація / тести / CI / build infra
- [ ] Refactor без зміни поведінки

## Чекліст

- [ ] Я прочитав [`CONTRIBUTING.md`](../CONTRIBUTING.md)
- [ ] Тести проходять локально (`./gradlew test`)
- [ ] Lint проходить (`./gradlew lintDebug`)
- [ ] Я оновив `## [Unreleased]` секцію у [`CHANGELOG.md`](../CHANGELOG.md) (якщо зміна user-visible)
- [ ] Якщо змінено AIDL / pump / verify / WrappedShellContext — bump `userServiceVersion` у `userservice/build.gradle.kts` (див. [`RELEASING.md`](../RELEASING.md))
- [ ] Якщо це зміна device coverage — оновлено `docs/device-matrix.md`
- [ ] Без emoji у коді, без коментарів-самоописів (див. CONTRIBUTING)

## Тестував на

<!-- Pixel 7 / Samsung S22 / etc. — обов'язково для змін у `:userservice` -->

## Пов'язані issue / discussions

<!-- Closes #123 / Refs #456 -->
