# WristRelay — Apple Watch ↔ Android

Мост уведомлений и данных здоровья между Android-телефоном и Apple Watch по Bluetooth LE.

```
Android (GATT server)  <====BLE====>  Apple Watch (GATT client)
```

- Телефон читает уведомления Android и отправляет их на часы.
- Часы могут отвечать на уведомления (reply) и слать данные здоровья.
- Пэйринг: bonding BLE + PIN-подтверждение (6 цифр).

## Структура проекта

```
wristrelay/
├── docs/
│   ├── PLAN.md        — полный план разработки
│   └── PROTOCOL.md    — спецификация BLE-протокола (единый источник истины)
├── proto/
│   └── bridge.proto   — protobuf-схема сообщений (для обеих платформ)
├── android/           — Android-приложение (Kotlin, Compose)
│   └── app/src/main/java/com/wristrelay/
│       ├── ble/             — GATT-сервер, реклама, фрагментация, протокол
│       ├── notifications/   — NotificationListenerService, извлечение контента
│       ├── health/          — Health Connect чтение/запись
│       ├── pairing/         — PIN-пэйринг
│       ├── storage/         — SecureStorage (Keystore+AES/GCM), DataStore
│       ├── service/         — BridgeService (foreground), BootReceiver
│       └── MainActivity.kt  — Compose UI
└── watchos/           — watchOS-приложение (Swift/SwiftUI, собирается на Mac)
```

## Сборка Android

```bash
cd android
# нужны: JDK 17+, Android SDK (compileSdk 35), Gradle 8.13 (wrapper уже есть)
./gradlew :app:assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

На Windows имя пользователя с кириллицей может ломать кэш Gradle:
```bash
set GRADLE_USER_HOME=C:\Temp\gradle-home
```

### Установка и запуск

1. Установите APK на телефон (Android 8.0+, API 26+).
2. Дайте разрешения: Nearby Devices (Bluetooth), уведомления.
3. Включите доступ к уведомлениям для WristRelay в системных настройках.
4. Откройте приложение → «Запустить синхронизацию».
5. На часах запустите WristRelay и найдите телефон.

### Health Connect

На Android 14+ разрешение Health Connect даётся отдельно (Health Connect app):
в настройках приложения выберите типы данных. Приложение запрашивает шаги,
ЧСС (чтение) и ЧСС (запись).

## Сборка watchOS (только на Mac)

См. `watchos/README_WATCHOS.md`. Требуется Xcode 15+, watchOS 9+,
SwiftProtobuf через SPM.

## Протокол

Полная спецификация в `docs/PROTOCOL.md`. Ключевое:
- UUID сервисов: `00000001-...`, `00000002-...` (см. `BridgeUuids`/`BridgeUuids.kt`).
- Все сообщения — protobuf `Envelope { sequence, type, payload }`.
- Фрагментация: заголовок 11 байт (marker/sequence/index), MTU-безопасно.

## Статус

- [x] Протокол и proto
- [x] Android: каркас, BLE-сервер, уведомления, health, pairing, storage
- [x] watchOS: каркас SwiftUI + BLE-клиент + протокол
- [ ] Интеграция protobuf-кодогенерации на watchOS (SwiftProtobuf)
- [ ] Полный reply-флоу и HealthKit на часах
- [ ] Тесты на реальных устройствах

## Ограничения этой машины

На текущей Windows-машине:
- **Эмулятор**: работал с ошибкой `could not load PC BIOS` из-за кириллического
  имени пользователя (путь SDK содержит не-ASCII символы, qemu не находит BIOS).
  **Решение (проверено):** создать Windows-джанкшн с ASCII-путём к SDK и запускать
  эмулятор из него:
  ```powershell
  cmd /c "mklink /J C:\AndroidSdk <путь к SDK>"
  $env:ANDROID_AVD_HOME="C:\Temp\avd"; $env:ANDROID_SDK_ROOT="C:\AndroidSdk"
  C:\AndroidSdk\emulator\emulator.exe -avd Pixel35 -gpu swiftshader_indirect -no-window
  ```
  Важно: не запускать два эмулятора одновременно (конфликт за AVD-файлы).
- **BLE на эмуляторе не работает** (Android-эмулятор не эмулирует Bluetooth-радио).
  Для теста связи телефон↔часы нужен реальный телефон.
- **watchOS не собирается** (нет Xcode/Mac). Код готов, сборка — на Mac.

## Проверено на эмуляторе

- APK устанавливается, MainActivity запускается без крашей.
- BridgeService стартует как foreground-service, создаёт канал уведомлений.
- Bluetooth-адаптер эмулятора включён (BLE-реклама стартует при запуске сервиса).

## CI-сборка watchOS (бесплатно, без Mac)

Проект уже содержит `.github/workflows/watchos-build.yml`. Чтобы запустить:

1. Создайте репозиторий на GitHub (например `wristrelay`).
2. Запушьте проект:
   ```bash
   git remote add origin https://github.com/ВАШ_ЛОГИН/wristrelay.git
   git push -u origin master
   ```
3. Откройте репозиторий → вкладка **Actions** → workflow **watchos-build** →
   **Run workflow**.
4. Через ~3–5 минут в артефактах появится `WristRelayWatchOS-simulator.app`.

Если Swift-код не компилируется — workflow упадёт с ошибками, и мы их исправим
(до покупки Apple Developer и до Mac).
