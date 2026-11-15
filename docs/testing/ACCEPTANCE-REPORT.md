# Проверки MJWatch 0.2.0

Дата: 2026-11-15. Application versionName `0.2.0`, versionCode `2`.
Исходники приложения: `97a43a4c26b5417fc14567d341af4b562e6eca9e`.
Это работающая первая реализация; полное закрытие всей матрицы ACCEPTANCE.md не заявляется.

## Поставка и воспроизводимость

- JBR 21.0.11, Gradle 8.13, AGP 8.13.2, Kotlin 2.3.0, compile/target SDK36, minSdk26.
- `:core:testDebugUnitTest :app-mobile:lintDebug :app-tv:lintDebug :app-mobile:assembleDebug :app-tv:assembleDebug :app-mobile:assembleRelease :app-tv:assembleRelease` — BUILD SUCCESSFUL.
- 17 unit/contract tests, 0 failures/errors: [JUnit XML](logs/contracts.xml).
- Lint завершён без errors; warnings сохранены в [mobile XML](logs/lint-mobile.xml) и [TV XML](logs/lint-tv.xml). Основные предупреждения: доступны новые версии зависимостей/target SDK, vector banner и предпочтение version catalog. Проверенные версии здесь зафиксированы, массовое обновление не выполнялось.
- Mobile `dev.mjwatch.mobile`, TV `dev.mjwatch.tv`. Co-install проверен на API26. Разные launcher/Leanback manifests; MJAnime не изменяется.
- Debug APK подписаны локальным debug key. Release APK unsigned, production signing не настроен. [SHA256 локальных APK](APK-SHA256.txt); CI пересобирает свои artifacts, поэтому его debug key/checksum отличается.
- Основная runtime-регрессия относится к `a9e5f34`; итоговый `97a43a4` добавляет только явные backup/device-transfer exclusions Android 12+ в manifest/XML. Финальная API26 smoke-проверка выполнена и после этого изменения.
- [GitHub Actions для этих исходников](https://github.com/MJSasha/mj-watch/actions/runs/43725158513): все три jobs завершены success — unit/lint/build, phone API35 x86_64 и TV API36 x86_64. APK и отчёты сохранены как workflow artifacts.

## Android runtime

Только собственные headless AVD. Ни одного пользовательского телевизора/проектора.

| Среда | Результат и границы |
|---|---|
| Phone API26 AOSP ARM64, 720×1280 / 320dpi (360dp) | SessionTest + VerticalFlowTest: 2 tests OK. Keystore, cancel late prepare, origin block, fake path, first-frame callback, pause, seek, короткое проигрывание, Back, немедленный прогресс, resume с прежнего пути и позиции. [Лог](logs/phone-api26.txt) |
| Phone API36 AOSP ARM64, 720×1280 / 320dpi | MetadataFlowTest + SessionTest + VerticalFlowTest + PipTest: 4 tests OK. Metadata синтетические, из Room cache; PiP включён явно аргументом теста. [Лог](logs/phone-api36.txt) |
| Android TV API36 ARM64, 1280×720 / 213dpi | VerticalFlowTest: 1 test OK. Native TV cards/buttons активируются D-pad/OK, first frame, progress, Back и focus restore. Первоначальный setup switch тест включает через Compose semantics; весь TV playback flow использует D-pad. [Лог](logs/tv-api36.txt) |
| Tablet API36, 1920×1200 / 240dpi (1280dp) | TabletTest: поиск и detail одновременно видимы в разных панелях; проверка bounds, скриншот |
| Phone fontScale 2.0 | Отдельный smoke run полного fake flow прошёл; не заменяет ручной TalkBack и проверку всех экранов |

API37 preview не считается проверенным: несовместимая Espresso/InputManager API и system UI ANR. Тесты перенесены на stable API36.

## Fixtures и контракты

- Jackett source pin: v0.24.3067 (`1dcaecd45170ff28769a2bd0f1af4979f1328abb`); TorrServer MatriX.150 reference, MatriX.145.2 live. [Источники контрактов](../contracts/README.md).
- Torznab: Unicode/CDATA/namespace/optional vs zero, XML error 200, malformed XML, XXE denial, response/item caps; partial timeout сохраняет готовые источники, cancellation; caps cache/paging и reverse proxy prefix.
- Download: bencode validation; Jackett 302 magnet обрабатывается как данные без follow; секреты Jackett не передаются TorrServer. Auth failure не вызывает повторный add. Redirect и локальные origins проверены mock HTTP.
- Files: fake server IDs 17/3/9, сортировка и отображение не превращают ID в row index; спецсимволы кодируются один раз.
- Storage: history/bookmark/progress независимо и атомарно; unknown duration не завершает просмотр; миграция Room 1→2 сохраняет данные; TMDB parse/cache/offline fallback.
- Cancel generation и отмена coroutine защищают поздний переход. Приложение не вызывает rem/wipe/drop.

## Реальный TorrServer

Разрешённый пользователем `http://kompic.local:8090`, MatriX.145.2. Из AVD запросы шли к `192.168.8.11:8090` — IP того же хоста, поскольку mDNS внутри AVD недоступен.

Fixture: Sintel, Blender Foundation, CC BY 3.0, публичный torrent `https://webtorrent.io/torrents/sintel.torrent`, infohash `08ada5a7a6183aae1e09d831df6748d566095a10`. Magnet/add и download/multipart upload выполнены отдельно настоящим Kotlin adapter. `save_to_db=false`; multipart параметр save отсутствует. Ответ upload singleton; выбран video server file ID `6`. Range request: 206 Partial Content, video/mp4.

Media3 на API26 декодирует `video/avc` + `audio/mp4a-latm`, подтверждён callback первого кадра. После дополнительного ожидания 30 секунд player продолжал играть, position росла, playerError отсутствует, retries=0. Долгий media request не обрывается control-request deadline. Это проверка функционального сеанса, не throughput/FPS benchmark. [Лог запуска](logs/live-torrserver-api26.txt), [безопасная диагностическая строка](logs/live-player-state.txt).

Ни настройки сервера, ни чужие торренты не изменялись/удалялись. Live Jackett/TMDB не выполнялись: адреса и credentials отсутствуют.

## Визуальные доказательства

[Phone](screenshots/phone/), [TV](screenshots/tv/), [tablet split](screenshots/tablet-search-detail.png). Снимки подтверждают конкретные состояния и layout; не доказывают плавность, сетевую совместимость или всю матрицу 24 макетов. Демонстрационные title/poster помечены как fixtures; при выключенном metadata adapter показывается нейтральный fallback.

## Незакрытые критерии

- Реальные Jackett и TMDB; reverse proxy/TLS/auth deployment реального окружения.
- Физические устройства, TV API26/30. Все сочетания пультов, Long OK, IME, focus/modal и process death требуют дополнительной проверки.
- MKV multiple audio/SRT, HEVC/HDR на capable/incapable hardware, unsupported codecs, unknown-duration live stream, bandwidth loss/restart/uncached seek, audio focus с другими приложениями.
- Ручной TalkBack, все 24 состояния на dark/light/system и 200% font, resize; performance profiling/FPS.
- Неизвестные эпизоды выбираются вручную. Автоматическое сопоставление и перенос позиции на другой монтаж не выполняются. Next-episode automation не заявляется.
- Production signing, магазин и публикация APK не выполнялись.
