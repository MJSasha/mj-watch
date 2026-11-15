# MJWatch

Нативный Kotlin-клиент для телефона/планшета и Android TV, версия 0.2.0. Два APK, общие данные и Media3. Дизайн и критерии: [design/HANDOFF.md](design/HANDOFF.md). Текущее состояние: [STATUS.md](STATUS.md), доказательства и ограничения: [отчёт приёмки](docs/testing/ACCEPTANCE-REPORT.md).

## Сборка

JDK 21, Android SDK 36, Gradle wrapper 8.13. Укажите `sdk.dir` в локальном `local.properties` либо `ANDROID_HOME`.

```sh
./gradlew :core:testDebugUnitTest :app-mobile:lintDebug :app-tv:lintDebug
./gradlew :app-mobile:assembleDebug :app-tv:assembleDebug
./gradlew :app-mobile:assembleRelease :app-tv:assembleRelease
```

Debug APK: `app-mobile/build/outputs/apk/debug/app-mobile-debug.apk` и `app-tv/build/outputs/apk/debug/app-tv-debug.apk`. Release APK unsigned; production signing и публикация не настроены. Пакеты `dev.mjwatch.mobile` / `dev.mjwatch.tv` устанавливаются одновременно и не заменяют MJAnime. minSdk 26 — заявленный минимум сборки, конкретная runtime-матрица в отчёте.

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

GitHub Actions запускает unit/lint/debug/release сборки и phone/TV runtime tests. APK и отчёты сохраняются как workflow artifacts, GitHub Releases не публикуются. Секреты серверов CI не нужны.
