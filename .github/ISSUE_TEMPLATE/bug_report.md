---
name: Bug report
about: Щось працює не так, як описано
title: 'bug: '
labels: bug
assignees: ''

---

## Опис

<!-- Що відбувається vs. що ви очікували -->

## Reproduce

1. ...
2. ...
3. ...

## Пристрій

- Модель: <!-- e.g. Pixel 7 -->
- Android: <!-- e.g. 14, патч 2026-03 -->
- Версія Cally: <!-- з Налаштувань → Про додаток -->
- Shizuku версія:
- Чи активований AccessibilityService toggle:
- Battery optimization exemption:

## Logcat (якщо relevant)

```
adb logcat -d -s RecorderController RecorderService AudioRecorderJob CallMonitorService > log.txt
```

<!-- Прикріпіть log.txt або вставте тут уривок. ПЕРЕВІРТЕ що нема номерів телефонів / імен з Контактів. -->

## Додаткова інформація

<!-- Скріншоти, аудіо-семпли (без real-call audio — тестові), будь-що корисне -->

## Чекліст

- [ ] Я перевірив що це не дублікат існуючого issue
- [ ] Я прибрав з логів чутливу інформацію (номери, імена)
- [ ] Я прочитав [`docs/device-matrix.md`](../../docs/device-matrix.md) — мій пристрій vs. документований стан
