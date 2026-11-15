# MJWatch — состояние реализации

Обновлено 2026-11-15. Версия 0.2.0 (2), исходники приложения: `97a43a4`.

## Выполнено

- Прочитаны HANDOFF, DEVELOPMENT, INTEGRATIONS, FLOWS, DESIGN-SYSTEM, ACCEPTANCE; HTML и экраны использованы как визуальные ориентиры. MJAnime не изменялся.
- Создан нативный проект: отдельные mobile/tablet и TV APK, общие core, playback, design-system. Kotlin, Compose Material 3 / Compose for TV, Media3. Никакого WebView.
- Работает вертикальный сценарий с локальным видео: подключения → поиск → раздача → server file ID → воспроизведение → сохранение → возобновление с прежнего пути файла.
- Реализованы Jackett/Torznab и TorrServer, отдельный optional TMDB, кеш метаданных и привязка к истории; без TMDB остаётся прямой поиск.
- История, избранное, undo удаления, темы, tablet split, TV focus restore, системная IME/Back, PiP, track controls, ограниченные повторы и отмена устаревших операций.
- Секреты и полные ссылки раздач зашифрованы Android Keystore AES-GCM, backup выключен; redirect запрещён, local HTTP ограничен явно разрешёнными origins. Долгий media stream не ограничен общим control-request call timeout.
- Room 1→2 мигрирует историю, избранное и прогресс без destructive reset.
- Исходники отправлены в MJSasha/mj-watch. GitHub Actions собирает APK и запускает phone/TV AVD tests; публикации Releases нет.
- Финальный CI `97a43a4`: [run 43725158513](https://github.com/MJSasha/mj-watch/actions/runs/43725158513) — build, phone API35 и TV API36 завершены success. APK и отчёты доступны в artifacts этого запуска.

## Проверено на fixtures / mock HTTP

17 core tests: XML/Unicode/CDATA, XXE и лимиты, XML error, partial results/deadlines/cancellation, caps/pagination/cache, магнитные ссылки и bencode, server file IDs/encoding/prefixes, redirect и auth isolation, без повторного add при auth failure, конфигурация, атомарное хранение, metadata offline cache и миграция базы.

## Проверено в Android runtime

Собственные headless AVD; пользовательские TV/проекторы не подключались.

- Phone API26 и API36 ARM64: fake flow, реальный Media3 first frame, pause/seek, короткий просмотр, Back, немедленное сохранение и повторный запуск с позиции. Проверка секретов и отмены подготовки.
- Phone API36: synthetic cached metadata → detail → bookmark → связанная раздача; PiP enter/continue/close.
- TV API36 ARM64: native TV cards/buttons через D-pad/OK, файлы, первый кадр, прогресс, Back и восстановление фокуса.
- Tablet API36 ≥840dp: две панели поиска/detail; phone fontScale 2.0: основной fake flow. Это отдельные smoke checks, не полная accessibility/device матрица.
- Оба APK устанавливаются одновременно на API26. Debug и unsigned release сборки, lint и 17 unit tests проходят локально.
- API37 preview исключён из доказательств: несовместимость Espresso с InputManager и system UI ANR.

## Подтверждено с реальным сервером

- Разрешённый `kompic.local:8090`, `/echo`: MatriX.145.2. Из AVD использован IP того же хоста из-за отсутствия mDNS.
- Sintel (Blender, CC BY 3.0): Kotlin magnet add и отдельный multipart torrent upload; singleton response, video server file ID=6; HTTP range 206; Media3 first frame.
- API26: live MP4 H.264 (`video/avc`) / AAC (`audio/mp4a-latm`), дополнительный 30-секундный сеанс продолжает играть, ошибок и deadline reconnect нет. Это функциональная проверка, не benchmark пропускной способности.
- `save_to_db=false`; rem/wipe/drop и изменения настроек сервера не выполнялись.

## Осталось вне подтверждённой приёмки

- Live Jackett и TMDB: адрес/ключи не предоставлены. Их контракты и offline fallback проверены fixtures, live-совместимость не заявлена.
- Полная матрица MKV/multiple audio/SRT, HEVC/HDR, unsupported codecs, потери сети и restart сервера; физические устройства и TV API26/30.
- Ручной TalkBack, все переходы фокуса/Long OK/IME на разных пультах, все 24 визуальных состояния, window resize и performance profiling.
- Автоматическое сопоставление неизвестных эпизодов отсутствует: нужен ручной выбор. Позиция между разными раздачами автоматически не переносится.
- Production signing и публикация не входят в выполненную поставку.

Подробности: [docs/testing/ACCEPTANCE-REPORT.md](docs/testing/ACCEPTANCE-REPORT.md).
