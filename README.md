# MJWatch

Нативный Kotlin-клиент для телефона/планшета и Android TV, ветка 0.3.0. Два APK, общие данные и Media3. Дизайн и критерии: [design/HANDOFF.md](design/HANDOFF.md). Текущее состояние: [STATUS.md](STATUS.md), доказательства и ограничения: [отчёт приёмки](docs/testing/ACCEPTANCE-REPORT.md).

## Сборка

JDK 21, Android SDK 36, Gradle wrapper 8.13. Укажите `sdk.dir` в локальном `local.properties` либо `ANDROID_HOME`.

```sh
./gradlew :core:testDebugUnitTest :app-mobile:lintDebug :app-tv:lintDebug
./gradlew :app-mobile:assembleDebug :app-tv:assembleDebug
./gradlew :app-mobile:assembleRelease :app-tv:assembleRelease
```

Debug APK: `app-mobile/build/outputs/apk/debug/app-mobile-debug.apk` и `app-tv/build/outputs/apk/debug/app-tv-debug.apk`. Локальный release APK unsigned. Подписанные тестовые APK публикуются автоматически в [GitHub Releases](https://github.com/MJSasha/mj-watch/releases); production signing не настроен. Пакеты `dev.mjwatch.mobile` / `dev.mjwatch.tv` устанавливаются одновременно и не заменяют MJAnime. minSdk 26 — заявленный минимум сборки, конкретная runtime-матрица в отчёте.

## Первый запуск

В «Подключениях» включите «Демонстрация с локальным видео», сохраните. Тестовые раздачи → detail → выбор раздачи → файл → Media3. Видео включено в APK, сеть не нужна. Технические данные fixture не являются измерениями серверов. Прогресс сохраняется отдельно от истории открытий.

Для реального поиска выключите демо, введите URL Jackett, API-ключ и ID выбранных индексаторов через запятую, URL TorrServer, optional Basic Auth. Каждый сервер проверяется отдельно; сохранение offline разрешено. Локальный HTTP требует отдельного разрешения, публичный HTTP блокируется общим guard. Для reverse proxy задавайте base URL с префиксом. Redirect запрещён: указывайте конечный адрес.

TMDB — отдельная необязательная настройка. Включите адаптер и введите Read Access Token; доступны каталог, карточки и поиск, кеш на 24 часа и offline fallback. Прямой поиск доступен без него. Без метаданных нет чужих обложек или выдуманных описаний.

«Продолжить» заново получает список файлов и выбирает прежний путь по актуальному server file ID. Если файл исчез, требуется ручной выбор. Обновление базы с версии 0.1 сохраняет историю и прогресс.

Ключи, Basic Auth и ссылки раздач шифруются Android Keystore AES-GCM. Backup отключён. Экспорт настроек содержит только адреса и предпочтения, импорт сначала показывает preview. После смены URL ключи вводятся заново. Конфиги с секретами в query/userinfo запрещены. Нет внешней аналитики и автоматического удаления торрентов на общем сервере.

## Проверки

Контрактные тесты: XML/CDATA/Unicode, XXE, лимиты, ошибки, partial results, cancellation, file ID, encoding, proxy prefixes и redirect credential isolation. Инструментальные тесты проверяют полный fake flow, callback первого кадра и сохранение прогресса. Live тест выполняется только вручную с явным аргументом `server`; в CI он пропускается.

```sh
# Только на выбранном локальном AVD:
adb -s emulator-PORT shell am instrument -w -e class dev.mjwatch.mobile.LiveTorrServerTest -e server http://TEST-HOST:PORT dev.mjwatch.mobile.test/androidx.test.runner.AndroidJUnitRunner
```

GitHub Actions запускает unit/lint/debug/release сборки и phone/TV runtime tests. APK и отчёты сохраняются как workflow artifacts; после зелёных проверок main подписанные APK публикуются в GitHub Releases. Секреты серверов CI не нужны.

## JacRed (начиная с 0.2.1)

Для публичного сервиса: адрес `https://jac.red`, режим «Авто» или «JacRed JSON», API-ключ и индексаторы можно оставить пустыми, если сервис не требует авторизации. Для своего JacRed выберите «JacRed JSON» явно. В режиме Torznab пустые индексаторы также означают `all`.

Для локального TorrServer введите его LAN URL с портом и включите «Разрешить HTTP». Если Android не разрешает имя `.local`, используйте LAN IP. HTTP403 означает отказ сервера: приложение показывает код; доступность jac.red зависит от самого сервиса/сети и не гарантируется тестами на fixtures.

## Обновления

Один раз установите APK своей платформы из [Releases](https://github.com/MJSasha/mj-watch/releases) поверх 0.2.1. Дальше: Настройки → Обновления. Проверка при запуске автоматическая (не чаще раза в 15 минут), скачивание по кнопке, установка с подтверждением Android. Подпись совместима с ранее выданными локальными APK. Подробности и процесс выпуска: [docs/UPDATES.md](docs/UPDATES.md).
