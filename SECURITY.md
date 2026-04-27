# Security Policy

## Звітування вразливостей

**Не відкривайте публічний issue для security-вразливостей.**

Cally працює з sensitive surface (telephony audio, privileged Shizuku-binder, AIDL з shell-UID процесом). Помилка в `verifyCaller()`, race у lifecycle або бекдор у транскрипції можуть мати реальні наслідки для користувачів.

### Як повідомити

- **Email:** `ua.lyo.su@gmail.com` (рядок теми: `[cally-security] стислий опис`)
- **GitHub Private Vulnerability Reporting:** Security tab репозиторію → Report a vulnerability
- Encrypted (PGP) — за запитом, key fingerprint надішлю у відповідь

### Що описати

- Версія додатку (`versionName` + `versionCode`)
- Android version, device, чи активний Shizuku
- Кроки відтворення
- Очікувана vs реальна поведінка
- Impact: confidentiality / integrity / availability
- За можливості — PoC або crash-stacktrace

### SLA

- **Підтвердження отримання:** 72 години
- **Triage + класифікація severity:** 7 днів
- **Fix:** Critical — 14 днів, High — 30 днів, Medium — 90 днів, Low — без жорсткого терміну
- **Координоване розкриття:** 90 днів від звіту АБО день фіксу (що раніше). Можу домовитися про довший embargo якщо exploit активно in-the-wild.

### Hall of Fame

Researcher'и, які звітують у відповідальний спосіб, додаються у `docs/security-credits.md` (з вашого дозволу). Bug bounty грошима поки немає — проєкт не комерційний; за активний research можу запросити на co-author грантових заявок.

## Підтримуваний scope

В межах scope:

- AIDL contract `:aidl/IRecorderService` — будь-який caller-bypass `verifyCaller()`
- `WrappedShellContext` / `HiddenApiBootstrap` — privilege escalation поза наміром «AudioRecord з shell ідентичністю»
- `RecorderService` lifecycle — race conditions, leak'и FD/PID, witness-confused-deputy
- Транскрипція — leak API ключа, MITM на user endpoint, log-injection через transcript text
- DataStore / `AppSettings` — несанкціонований доступ до ключа поза app sandbox

Поза scope:

- Soak-тестові надмірні battery drain (це performance bug, не security)
- Атаки на Shizuku-сервер (звітуйте у [shizuku/issues](https://github.com/RikkaApps/Shizuku))
- Атаки на user-configured STT endpoint (це їх відповідальність)
- DoS через локальне переповнення сховища (користувач контролює)
- `apk_extracted/` або інші локальні artifacts — це наш research локально, не shipped

## Що **не** є вразливістю

- INTERNET permission присутній — це задокументовано (cloud STT opt-in). Не вразливість.
- API ключ зберігається у DataStore plaintext — задокументовано в README → Безпека. На non-rooted пристрої це app-private storage. Якщо ви знайшли спосіб витягти його з іншого app sandbox без root — **це вразливість**.
- Endpoint URL налаштовується користувачем — навіть HTTP буде відхилений `network_security_config`. Не вразливість якщо користувач сам ввів `http://...` і отримав refused — це by design.
