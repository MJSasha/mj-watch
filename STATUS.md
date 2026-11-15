# MJWatch — состояние реализации

## 2026-11-15
- Прочитаны HANDOFF и пять спецификаций, HTML и обзор/экраны подключения и раздач.
- Исходного Android-проекта не было. Доступны JBR 21, SDK 35/36/37, AVD phone API 37; пользовательские устройства не используются.
- Удалённый репозиторий MJSasha/mj-watch пустой (ls-remote без refs).
- Создана структура двух APK и общих core/playback/design-system. Сборка пока не проверена.

## Уровни доказательств
Реализация, fixture tests, Android runtime и live servers учитываются отдельно. Live credentials и серверы не предоставлены.

## Этап 1–3: исходники и сборка
- Созданы app-mobile (dev.mjwatch.mobile), app-tv (dev.mjwatch.tv), core, playback, design-system.
- Clean debug build двух APK и 8 контрактных unit-тестов прошёл локально (JBR21, SDK36).
- Реализован fake flow с локальным MP4: настройки → поиск → detail → releases → metadata → files → Media3 → Room progress.
- Подключены Keystore AES-GCM, DataStore, Room, MediaSession, PiP entry, track selector, ±10s, скорость через штатный Media3 player settings.
- Android runtime ещё в проверке; полная UX/codec/device матрица не пройдена.

## Live TorrServer
- По разрешению пользователя: /echo сообщает MatriX.145.2.
- Multipart upload Sintel CC BY 3.0 без save: ответ singleton, video file ID=6.
- HTTP range stream index=6: 206 Partial Content, Content-Type video/mp4, 65536 bytes. Это подтверждает транспорт, не Android decoding.
- get после upload однажды вернул 404; stream самостоятельно разрешил hash. Не выполнялись rem/wipe/drop.
- Live Jackett и TMDB credentials отсутствуют.

## Подтверждённый Android runtime (2026-11-15)
- Phone: отдельный AOSP API36 ARM64 AVD (720×1280, 320dpi). Полный fake flow: Compose test OK, реальный onRenderedFirstFrame, 6 секунд playback, Back и сохранённый Room progress.
- TV: отдельный Android TV API36 ARM64 AVD. Полный fake flow с D-pad/OK на TV controls: OK, first frame и сохранение позиции.
- Live phone: MatriX.145.2, Sintel CC BY 3.0; настоящие Kotlin magnet/add, download/upload, file ID=6, stream и Media3 first frame: OK.
- mDNS kompic.local из AVD не разрешился; использован IP того же хоста. На host macOS .local доступен. Никаких TV/проекторов пользователя не подключалось.
- Preview API37: Espresso несовместим с удалённым InputManager.getInstance; также наблюдался system UI ANR. Основная проверка перенесена на stable API36.
- 9 core contract tests проходят. Debug/release build и lint выполняются; финальная перепроверка новых изменений ещё идёт.

## Пока не подтверждено
- Live Jackett и TMDB (нет адреса/ключа), реальные устройства и API26, расширенная codec/network/PiP/TalkBack/fontScale/performance матрица.
- Полное соответствие всех 24 макетов и production-ready статус не объявлены: реализованы основные экраны и честный direct-search fallback, дальнейшая полировка и расширенные сценарии проверяются отдельно.
