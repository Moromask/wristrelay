# WatchBridge watchOS — сборка и тест

## Сборка в облаке (GitHub Actions) — бесплатно, без Mac

1. Заведите GitHub-репозиторий и залейте проект (папка `watchbridge/`).
2. Откройте репозиторий → Actions → workflow **watchos-build** → Run workflow.
3. В артефактах появится `WatchBridgeWatchOS-simulator.app` — это сборка для
   watchOS Simulator, подпись отключена (`CODE_SIGNING_ALLOWED=NO`).

Это проверяет, что Swift-код компилируется, **до** покупки Apple Developer.

> Примечание: сборка происходит на macOS-раннере GitHub (не на вашей Windows).
> watchOS Simulator в CI не запускается — только компиляция. Для запуска на
> симуляторе нужен Mac.

## Сборка локально на Mac

```bash
cd watchos
brew install xcodegen        # один раз
xcodegen generate            # создаст WatchBridgeWatchOS.xcodeproj
open WatchBridgeWatchOS.xcodeproj
```
Затем в Xcode выберите симулятор Apple Watch и Run.

## Установка на реальные часы (нужен Apple Developer, $99/год)

1. Xcode → Signing: выберите свою команду (Personal Team не подходит для
   watchOS — нужен платный аккаунт).
2. Часы спарены с iPhone. На iPhone и часах включите Developer Mode.
3. Подключите iPhone к Mac по USB (или по сети), выберите Apple Watch как
   устройство запуска в Xcode, нажмите Run.

## Публикация

- Для App Store: Product → Archive → Organizer → Distribute App.
- Или ad-hoc/TestFlight для личного использования (без публичного App Review).

## Структура

- `project.yml` — XcodeGen-спека (источник истины для проекта)
- `WatchBridgeWatchOS/` — исходники Swift
- `.github/workflows/watchos-build.yml` — CI-сборка
