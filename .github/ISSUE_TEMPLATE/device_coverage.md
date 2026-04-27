---
name: Device coverage report
about: Я протестував на новому пристрої — внесу результат у device matrix
title: 'device: '
labels: device-coverage
assignees: ''

---

## Пристрій

- **Manufacturer/Model:** <!-- e.g. Samsung Galaxy S24 Ultra -->
- **Android:** <!-- e.g. 14 (One UI 6.1) -->
- **Security patch:** <!-- e.g. 2026-04 -->
- **Build.FINGERPRINT:** <!-- adb shell getprop ro.build.fingerprint -->

## Результат

- **Стратегія, що спрацювала:** <!-- DualUplinkDownlink / DualMicDownlink / SingleVoiceCallStereo / SingleVoiceCallMono / SingleMic -->
- **Шкала результату:** <!-- ✅ обидві сторони / ⚠️ дисбаланс / 🟥 MIC-only / ❌ crash -->
- **Uplink RMS (приблизно):** <!-- з Settings → Debug -->
- **Downlink RMS (приблизно):**
- **Чи стабільний на 5+ дзвінках поспіль:** <!-- так / ні / не тестував -->

## Сценарії

- [ ] Звичайний дзвінок (cellular voice)
- [ ] З BT-гарнітурою під час дзвінка
- [ ] З speakerphone toggle
- [ ] Перемикання SIM 1↔2 під час дзвінка
- [ ] Довгий дзвінок (10+ хв)

## Зразок аудіо (опційно)

<!-- 10-20 сек тестового дзвінка самому собі (другою SIM/SIP), щоб ми могли почути якість.
     НЕ викладайте справжні розмови з третіми сторонами без їх згоди. -->

## Нотатки

<!-- Особливості HAL, специфіка vendor'а, recurring quirks -->

## Чекліст

- [ ] Я готовий додати рядок у [`docs/device-matrix.md`](../../docs/device-matrix.md) через PR (або хочу щоб maintainer додав сам)
- [ ] Прибрав з логів / аудіо чутливу інформацію
