# WristRelay BLE-протокол (v1)

Единый источник истины для Android и watchOS. Изменения — только здесь, затем генерация кода.

## 1. Службы и характеристики

Базовый UUID namespace: `0000XXXX-C4D1-4A7E-9B3A-9A8E1F2A3B4C`

### Служба Main `00000001-...`
| Характеристика | UUID | Свойства | Безопасность | Направление |
|---|---|---|---|---|
| Status | `00000101-...` | READ, NOTIFY | ENCRYPTED_MITM | Phone → Watch |
| Command | `00000102-...` | WRITE | ENCRYPTED_MITM | Phone → Watch |
| Notification | `00000103-...` | READ, NOTIFY | ENCRYPTED_MITM | Phone → Watch |
| Health | `00000104-...` | WRITE, NOTIFY | ENCRYPTED_MITM | двунаправленный |
| Pairing | `00000105-...` | WRITE, NOTIFY | ENCRYPTED_MITM | двунаправленный |

Каждая notify-характеристика имеет Client Characteristic Configuration Descriptor (CCCD) с ENCRYPTED_MITM.

## 2. Формат сообщений

Все сообщения — Protobuf, обёрнутые в `Envelope`:

```proto
message Envelope {
  uint64 sequence = 1;      // монотонный счётчик отправителя
  MessageType type = 2;
  bytes payload = 3;        // сериализованное вложенное сообщение
}

enum MessageType {
  UNKNOWN = 0;
  NOTIFICATION = 1;
  NOTIFICATION_REMOVED = 2;
  NOTIFICATION_ACTION = 3;   // Watch → Phone (reply/open)
  HEALTH_SAMPLE = 4;         // Watch → Phone и Phone → Watch
  PAIRING = 5;               // двунаправленный
  COMMAND = 6;               // Phone → Watch
  PING = 7;
  PONG = 8;
}
```

Правила:
- Максимальный размер одного notify: 512 байт (MTU 517). Сообщения > 512 байт **фрагментируются**: первый байт payload = флаг `0x00` (полное), `0x01` (продолжение), `0x02` (последний), затем 2 байта порядкового номера фрагмента, затем данные. Сборка по `sequence`.
- Sequence: сбрасывается при каждом новом соединении, стартует с 1.

## 3. Уведомления

```proto
message Notification {
  string package_name = 1;    // пакет приложения-источника
  string key = 2;             // уникальный ключ уведомления (сгенерён на телефоне)
  string title = 3;
  string text = 4;
  string app_name = 5;        // человекочитаемое имя приложения
  int64 posted_at_ms = 6;
  int64 when_ms = 7;
  string category = 8;        // notification.category (msg, alarm, ...)
  bool ongoing = 9;
  repeated Action actions = 10;
  string large_icon_id = 11;  // ссылка на кэшированную иконку (base64 PNG-превью)
}

message Action {
  string id = 1;              // стабильный id действия в рамках уведомления
  string title = 2;
  bool has_reply = 3;         // поддерживает RemoteInput
  string reply_result_key = 4;
  bool is_inline_reply = 5;
  bool opens_app = 6;
}

message NotificationRemoved {
  string key = 1;
  int32 reason = 2;           // NotificationListenerService.onNotificationRemoved reason
}
```

## 4. Команды и действия

```proto
message Command {
  oneof command {
    OpenApp open_app = 1;
    ShowMessage show_message = 2;   // «X хочет открыть приложение на телефоне»
    VibrationPattern vibration = 3;
    RequestState request_state = 4;
    StateSnapshot state_snapshot = 5;  // Watch → Phone: battery, connectivity
  }
}

message OpenApp { string target_screen = 1; }
message ShowMessage { string text = 1; }
message VibrationPattern { repeated int32 pattern_ms = 1; }

message RequestState {}
message StateSnapshot {
  int32 battery_percent = 1;
  bool charging = 2;
}
```

`NotificationAction` (Watch → Phone, ответ/открытие):
```proto
message NotificationAction {
  string notification_key = 1;  // ключ из Notification.key
  string action_id = 2;
  string reply_text = 3;        // заполняется при has_reply
}
```

## 5. Здоровье

```proto
message HealthSample {
  HealthMetric metric = 1;
  double value = 2;             // шаги: int, ЧСС: уд/мин, и т.п.
  int64 time_ms = 3;
  string source = 4;            // "watch" | "android"
  int64 duration_ms = 5;        // для сна/тренировок
  double min = 6;
  double max = 7;
}

enum HealthMetric {
  UNKNOWN_METRIC = 0;
  STEPS = 1;
  HEART_RATE = 2;
  HEART_RATE_RESTING = 3;
  BLOOD_OXYGEN = 4;
  SLEEP = 5;
  ACTIVE_CALORIES = 6;
  DISTANCE_METERS = 7;
  WORKOUT = 8;
}
```

Пакет HealthSample может содержать **массив** значений (батч): для этого используются фрагменты или многократные сообщения. Рекомендуется батч через несколько HealthSample с одним временным окном.

## 6. Пэйринг

```proto
message PairingMessage {
  PairingStep step = 1;
  bytes nonce = 2;          // 32 байта
  bytes pin_hash = 3;       // SHA-256(pin + nonce)
  bytes session_key = 4;    // после подтверждения
  bool success = 5;
  string error = 6;
}

enum PairingStep {
  PAIRING_UNKNOWN = 0;
  WATCH_HELLO = 1;       // Watch → Phone: nonce
  PHONE_PIN = 2;         // Phone → Watch: «покажи PIN» (без секрета)
  WATCH_PIN = 3;         // Watch → Phone: pin_hash
  PHONE_VERIFIED = 4;    // Phone → Watch: success=true
  WATCH_VERIFIED = 5;    // Watch → Phone: подтверждение (успех end-to-end)
  RESET = 6;
}
```

Поток:
1. Watch → Phone `WATCH_HELLO` c `nonce` (32 случайных байта).
2. Phone показывает 6-значный PIN (выводится в UI телефона).
3. Phone → Watch `PHONE_PIN` (сообщение-«уведомление о том, что PIN сгенерирован»).
4. Watch: пользователь вводит PIN.
5. Watch → Phone `WATCH_PIN` с `pin_hash = SHA-256(PIN + nonce)`.
6. Phone сверяет, отвечает `PHONE_VERIFIED(success=true)`.
7. Watch отвечает `WATCH_VERIFIED`. Оба сохраняют session_key (произвольные 32 байта телефона, передаются в PHONE_VERIFIED).

**Безопасность:**
- Никогда не передаётся открытый PIN.
- При сбое 3 раза — сброс (RESET), новое устройство считается непроверенным.
- Bonding (OS-level) обязателен до старта пэйринга.

### 6.1 QR-пэйринг (без ввода PIN)

У Apple Watch нет камеры, но есть экран. Поэтому QR-пэйринг работает так:
**часы показывают QR, Android-телефон сканирует его камерой.**

Формат содержимого QR (UTF-8, не protobuf — чтобы можно было прочитать глазами):

```
WRISTRELAY:1:<nonce>
```

где `<nonce>` — 32 случайных байта в hex (64 hex-символа).

Поток:
1. На часах: экран «Пэйринг» → часы генерируют `nonce` (32 байта) и рисуют QR
   `WRISTRELAY:1:<hex(nonce)>`.
2. Android: экран «Сканировать QR» → камера распознаёт QR → телефон сохраняет
   `nonce` как ожидаемый для нового устройства.
3. Watch → Phone (BLE) `WATCH_HELLO` c тем же `nonce` (из QR).
4. Phone сверяет `nonce` из BLE с `nonce` из QR. Совпало → генерирует
   `session_key`, отвечает `PHONE_VERIFIED(success=true, session_key)`.
5. Watch отвечает `WATCH_VERIFIED`. Пэйринг завершён.

Свойства:
- PIN не нужен — «физическое владение» доказывается тем, что пользователь
  навёл камеру телефона на экран часов.
- `nonce` одноразовый: после успешного или неудачного пэйринга — сброс.
- Валиден 2 минуты (время на сканирование), затем — новый nonce.
- Bonding (OS-level) обязателен, как и в PIN-потоке.
- PIN-поток остаётся как запасной вариант.

## 7. Регламенты соединения

- Watch отправляет PING каждые 10 секунд при подключении, Phone отвечает PONG.
- При отсутствии ответа > 30 секунд — разрыв и переподключение.
- Phone проверяет «дедупликацию» уведомлений по `key` (HashSet по времени).
