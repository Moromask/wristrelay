# План работ: WatchBridge — Apple Watch ↔ Android

Проект из двух приложений, общающихся напрямую по Bluetooth LE (BLE).

```
┌─────────────────────┐        BLE (GATT)        ┌─────────────────────┐
│  Android-приложение │◄────────────────────────►│  watchOS-приложение │
│  (GATT-СЕРВЕР)      │  реклама + соединение    │  (GATT-КЛИЕНТ)      │
└─────────────────────┘                          └─────────────────────┘
   • читает уведомления                              • получает уведомления
   • Health Connect (здоровье)                       • отправляет команды
   • шифрованное хранилище                            • шлёт данные здоровья
```

Роли:
- **Android-телефон** = BLE-периферия (GATT-сервер), рекламирует сервис, принимает подключения часов.
- **Apple Watch** = BLE-центральный (GATT-клиент), ищет телефон, подключается, читает/пишет характеристики.
- iPhone нужен **только один раз** для первичной настройки часов; в работе не участвует.
- Оба приложения публикуются в сторах как обычные BLE-приложения (Apple не запрещает watch-приложениям работать с не-Apple устройствами).

---

## 1. Архитектура BLE (общая для двух платформ)

### 1.1. Службы и характеристики

Используем фиксированные 128-битные UUID (v5). Пример базового префикса:
`0000xxxx-c4d1-4a7e-9b3a-9a8e1f2a3b4c`

| UUID (короткий) | Служба/характеристика | Свойства | Пар. безопасность | Назначение |
|---|---|---|---|---|
| `0001` | Служба `Main` | — | — | Группа сервисов устройства |
| `0101` | `Status` | READ, NOTIFY | ENCRYPTED_MITM | Состояние, версия протокола |
| `0102` | `Command` (Android→Watch) | WRITE | ENCRYPTED_MITM | Команды от телефона (открыть приложение и т.п.) |
| `0103` | `Notification` | READ, NOTIFY | ENCRYPTED_MITM | Поток уведомлений |
| `0104` | `Health` | WRITE, NOTIFY | ENCRYPTED_MITM | Данные здоровья (обе стороны) |
| `0105` | `Pairing` | WRITE | ENCRYPTED_MITM | Пэйринг-обмен |
| `0002` | Служба `Pairing` | — | — | Первичная настройка |
| `0201` | `PairingRequest` | WRITE, NOTIFY | ENCRYPTED_MITM | Запрос пэйринга / вызов |
| `0202` | `PairingResponse` | READ, NOTIFY | ENCRYPTED_MITM | Ответ/подтверждение |

> Решение: весь трафик идёт через характеристики с `ENCRYPTED_MITM` → требуется спаривание на уровне ОС (pairing/bonding), что даёт шифрование канала и защиту от MITM из коробки.

### 1.2. Протокол сообщений (Protobuf)

Общие `.proto` (копия лежит в `proto/` и генерирует классы и для Android — protobuf-lite, и для watchOS — SwiftProtobuf):

- `Envelope` — обёртка: тип сообщения + payload + sequence.
- `Notification` — пакет уведомления (пакет, приложение, тайтл, текст, категория, действия, метки времени, иконка-место).
- `NotificationAction` — действие (reply с RemoteInput, открыть приложение).
- `HealthSample` — запись здоровья (тип, значение, время, источник).
- `PairingMessage` — пэйринг (nonce, сертификат/ключ, подтверждение).
- `Command` — управляющие команды.

### 1.3. Пэйринг и безопасность

1. На телефоне включается реклама; часы видят сервис `0001`.
2. Часы запрашивают **OS-level bond** (Android `createBond`, watchOS пару). После bond характеристики с `ENCRYPTED_MITM` становятся доступны.
3. Поверх bond — прикладной пэйринг: телефон показывает 6-значный PIN, часы вводят его (`PairingMessage`), сверка по hash, обмен session key.
4. Все последующие сообщения — protobuf поверх зашифрованного BLE-канала.

---

## 2. Android-приложение

Стек: **Kotlin, Jetpack Compose, Coroutines, Hilt (опционально — не используем, чтобы упростить), Room (для кэша уведомлений), Health Connect API, protobuf-lite, DataStore (настройки), Android Keystore (секреты).**

minSdk 26, targetSdk 35, language: Kotlin.

### 2.1. Модули/пакеты

```
com.watchbridge
├── MainActivity.kt            — Compose-экран (статус, устройства, настройки)
├── MergeApplication.kt        — Application, инициализация
├── ble/
│   ├── GattServer.kt          — GATT-сервер, характеристики, callbacks
│   ├── Advertiser.kt          — BLE-реклама
│   ├── BondHelper.kt          — инициирование/проверка bond
│   └── Protocol/
│       ├── Envelope.kt        — сериализация сообщений
│       └── MessageTypes.kt
├── notifications/
│   ├── NotificationListener.kt — NotificationListenerService
│   ├── ContentExtractor.kt     — извлечение тайтла/текста (без рефлексии!)
│   └── NotificationRepository.kt — фильтры, дедупликация, кэш
├── health/
│   └── HealthSync.kt           — чтение/запись Health Connect
├── storage/
│   ├── SecureStorage.kt        — Keystore + AES/GCM
│   └── SettingsStore.kt        — DataStore
├── service/
│   └── BridgeService.kt        — foreground service, связывает всё
└── pairing/
    └── PairingManager.kt       — PIN-обмен, session key
```

### 2.2. Ключевые компоненты

| Компонент | Обязанности | Важные детали |
|---|---|---|
| `BridgeService` | foreground service (`connectedDevice`), держит BLE-стек живым, обрабатывает сообщения, шлёт уведомления | START_STICKY, валлидация уведомления до `startForeground` (Android 13+) |
| `GattServer` | openGattServer, addService, sendResponse/notify | Потокобезопасность, очередь notify |
| `NotificationListener` | onNotificationPosted/Removed | без рефлексии, фильтры по пакетам, дедуп |
| `HealthSync` | Health Connect read/write | права только на нужные типы |
| `SecureStorage` | шифрование секретов | Keystore key, AES/GCM/NoPadding, случайный IV |
| `PairingManager` | PIN + session key | SPAKE2 или простой HMAC |

### 2.3. Права (манифест)

- BLUETOOTH_CONNECT / BLUETOOTH_SCAN / BLUETOOTH_ADVERTISE
- POST_NOTIFICATIONS (runtime)
- FOREGROUND_SERVICE / FOREGROUND_SERVICE_CONNECTED_DEVICE
- health.* WRITE/READ нужных типов (runtime через Health Connect)
- RECEIVE_BOOT_COMPLETED (перезапуск сервиса)

### 2.4. UI (MainActivity, Compose)

- Экран статуса (служба вкл/выкл, список устройств/часов, battery)
- Кнопка «Включить синхронизацию»
- Настройки: какие приложения синкать, Health Connect toggle
- Экран пэйринга (PIN)

### 2.5. Этапы Android

1. Каркас Gradle (Kotlin DSL, AGP 8.x, Compose BOM) + пустое приложение собирается.
2. BLE GATT-сервер + реклама + bond (демо «подключился/отключился»).
3. Protobuf-протокол + Envelope.
4. NotificationListenerService + извлечение контента + отправка.
5. Health Connect чтение/запись.
6. SecureStorage + PairingManager.
7. UI Compose.
8. Сборка debug APK, тест на устройстве/эмуляторе.

---

## 3. watchOS-приложение

Стек: **Swift 5, SwiftUI, CoreBluetooth, SwiftProtobuf, HealthKit.**

Deployment: watchOS 9.0+, Xcode 15+, один target.

### 3.1. Модули/пакеты

```
WatchBridgeWatchOS/
├── WatchBridgeWatchOSApp.swift   — @main
├── Views/
│   ├── StatusView.swift          — статус подключения
│   ├── NotificationListView.swift — лента уведомлений
│   ├── PairingView.swift         — ввод PIN
│   └── HealthView.swift          — метрики (шаги/ЧСС)
├── BLE/
│   ├── CentralManager.swift      — CoreBluetooth центральный
│   ├── Protocol/                 — те же .proto (SwiftProtobuf)
│   └── Session.swift             — управление соединением, сериализация
├── Notifications/
│   └── NotificationStore.swift   — хранение/обновление ленты
└── Health/
    └── HealthReader.swift        — HealthKit чтение/запись
```

### 3.2. Ключевые компоненты

| Компонент | Обязанности |
|---|---|
| `CentralManager` | scanForPeripherals, connect, discoverServices/Characteristics |
| `Session` | Envelope-обмен, sequence, retry, reconnection |
| `NotificationStore` | лента с обновлениями/удалениями |
| `HealthReader` | чтение шагов/ЧСС, запись в Android |

### 3.3. Этапы watchOS

1. Каркас SwiftUI + CoreBluetooth-сканирование.
2. Подключение + discovery характеристик.
3. Protobuf (SwiftProtobuf) + Session.
4. Лента уведомлений.
5. PairingView (PIN).
6. HealthKit чтение/запись.
7. Полировка UI, watch face complications (опционально).

> ⚠️ Сборка watchOS возможна только на Mac с Xcode. На этой машине (Windows) пишем код, пользователь собирает на Mac.

---

## 4. Общие proto-файлы (единый источник правды)

`proto/` содержит:
- `bridge.proto` — Envelope, Notification, Action, HealthSample, PairingMessage, Command.
Android: Gradle protobuf-lite → Kotlin.
watchOS: SwiftProtobuf (сгенерировать на Mac через `protoc --swift_out`).

---

## 5. Порядок выполнения (спринты)

| # | Спринт | Результат | Проверка |
|---|---|---|---|
| 1 | **Архитектура + протокол** | PLAN.md, PROTOCOL.md, proto-файлы | ревью протокола |
| 2 | **Android каркас** | собирается debug APK | `./gradlew assembleDebug` |
| 3 | **Android BLE-сервер** | реклама + bond + соединение | лог/UI статуса |
| 4 | **Android уведомления** | уведомления Android уходят в протокол | лог |
| 5 | **Android health + storage + pairing** | Health Connect, Keystore, PIN | тесты |
| 6 | **Android UI** | Compose-экраны | ручной тест |
| 7 | **watchOS каркас** | собирается на Mac | Xcode |
| 8 | **watchOS BLE-клиент** | сканирование/подключение к Android | реальные часы/симулятор |
| 9 | **watchOS уведомления** | лента уведомлений | реальные часы |
| 10 | **watchOS health + pairing** | HealthKit + PIN | реальные часы |
| 11 | **Интеграция end-to-end** | полный цикл уведомлений + здоровья | реальные устройства |
| 12 | **Защита и релиз** | прошивка, права, публикация | сторы |

---

## 6. Риски и решения

| Риск | Решение |
|---|---|
| Без Mac нельзя собрать watchOS | Код пишем полностью; сборка у пользователя на Mac |
| BLE на Android: реклама + GATT-сервер капризны | minSdk 26, тесты на реальном устройстве |
| Health Connect требует API 34+ и runtime-прав | Запрос прав в UI; эмулятор с Health Connect не полный — тест на устройстве |
| Apple не любит «обход» экосистемы | Позиционируем как BLE-мост, app-review обычно проходит |
| Квартирный вопрос: обфускация не нужна на старте | R8 в release, ProGuard-правила для protobuf |
