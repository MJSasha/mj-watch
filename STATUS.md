# MJWatch — состояние реализации

Текущая публикация: 0.3.0-test.14 (1014), GitHub Releases и встроенные обновления работают. Последние результаты — в конце файла. Данные 0.2.x ниже сохранены как исторический отчёт.

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

## Исправление подключения JacRed — 0.2.1 (3)

После пользовательского отчёта обнаружены и исправлены блокировка запросов при пустых индексаторах и отсутствие отдельного JacRed JSON adapter. Для `https://jac.red` он выбирается автоматически, доступны явные Auto/Torznab/JacRed режимы. Пустой ключ не отправляется; пустые индексаторы означают `all`. API сохраняется в конфигурации. HTML вместо Torznab XML теперь отклоняется, а HTTP403 больше не выдаётся за однозначную ошибку ключа. Причины local HTTP denial / DNS / connect не скрываются общей ошибкой сети.

- 21 core tests: OK, включая новые JSON/case/dedupe/empty key/all/prefix/error-policy и HTTP429 regressions.
- При HTTP429 GET-поиск/проверка повторяется не более одного раза с короткой задержкой; длинный Retry-After возвращается пользователю. POST add/upload не повторяется этим механизмом; отмена прерывает ожидание.
- Android API36 JacredFlowTest: реальные HTTP adapters, synthetic JacRed JSON + mock TorrServer + local MP4 over HTTP, проверки обоих подключений без ключей/индексаторов → поиск → выбор → stream с file ID17 → фактический first frame: OK.
- Live локальный TorrServer по-прежнему отвечает MatriX.145.2.
- Уточнена причина HTTP403: сервис отклоняет curl User-Agent. С явным `MJWatch/0.2.1 (Android)` запросы без ключа возвращают HTTP200, 12 результатов Sintel и успешную проверку с пустым Query. Идентификация добавлена в общий guarded HTTP client и покрыта тестом. Android live-проверка цепочки фиксируется ниже.
- Финальные сборки и CI этой версии фиксируются отдельно ниже.
- Итоговая API36 регрессия: 4 tests OK — HTTP JacRed fixtures, cancel/secrets, fake progress/resume и live TorrServer с доступным Sintel MP4 (magnet/upload/first frame). Оба APK debug/release + lint + 21 core tests проходят локально.
- Live Android API36: check JacRed OK, поиск Sintel дал 12 результатов без ключа. Выбранный небольшой torrent принят MatriX.145.2, но остался `Torrent getting info`, файлов 0; через 60 секунд получен metadata timeout. Первый кадр именно этой раздачи не подтверждён. [Отчёт 0.2.1](docs/testing/0.2.1/REPORT.md) отделяет этот результат от успешных fixture/MP4 проверок.
- Финальный [CI 43727812428](https://github.com/MJSasha/mj-watch/actions/runs/43727812428), `9ee2120`: build, phone API35 и TV API36 — success. Предыдущий запуск выявил гонку нового UI-теста между async save и переходом в поиск; тест теперь ждёт главную страницу, затем поле поиска. Код APK остаётся `7f3496e`.

## GitHub publication / updates — 0.3.0 testing

- Реализован отдельный updater: auto check при старте/возврате (15-minute throttle), manual check, выбор mobile/TV, прогресс/отмена, проверка SHA256/size/package/version/certificate, узкий FileProvider, native permission + installer. Секреты серверов не передаются GitHub.
- По явному подтверждению пользователя текущая совместимая тестовая подпись сохранена в Actions Secret. В source private key отсутствует; CI проверяет публичный fingerprint. Подпись production не заявляется.
- [Первый релиз test-12](https://github.com/MJSasha/mj-watch/releases/tag/test-12), versionCode1012: оба подписанных APK, update.json, SHA256SUMS. [CI #12](https://github.com/MJSasha/mj-watch/actions/runs/43729640745) — build/phone/TV/publish success.
- Android API36: real GitHub download → integrity/signature verification → permission settings → native UPDATE → code1012 installed. После обновления сохранены Room history/progress и зашифрованный sentinel. Неверная подпись отклоняется. Локальные unit tests: 27 OK.
- [Отчёт и логи](docs/testing/0.3.0/REPORT.md), [инструкция выпуска](docs/UPDATES.md). В 0.2.1 updater отсутствует: первый новый APK ставится вручную поверх; затем обновления доступны из приложения. Подтверждение Android остаётся обязательным.
- TV API36: полный update flow с D-pad/OK/Back, включая системное разрешение и native Update, завершился установкой code1012. Настройки DEMO и Continue watching сохранены. Только собственный AVD.
- Следующий [релиз test-14](https://github.com/MJSasha/mj-watch/releases/tag/test-14), code1014, вышел полностью автоматически. [CI #14](https://github.com/MJSasha/mj-watch/actions/runs/43731326631) — все 4 jobs success, 27 unit tests. Перед установщиком сохраняется и останавливается активный player. Пользовательский канал готов к тестированию.
- Установленная test-12 сама обнаружила test-14 при запуске после естественного 15-minute throttle, без сброса prefs/часов и без manual check. Обновление 1012→1014 через приложение и native installer завершилось; повторная проверка encrypted sentinel/history/progress OK. Все изменения и доказательства отправлены в репозиторий.

## 2026-11-15 — визуальная сверка 0.4.0

- Реализовано: единый hero, TV фон до краёв и горизонтальная полка, пять пунктов rail, native mobile source sheet, компактные раздачи/файлы, tablet split с отдельной карточкой, новая библиотека с undo, разделы настроек и двухшаговый mobile setup, оформленные empty/error/preparing состояния, Compose overlay Media3 с тонкой seek-полосой и landscape на телефоне.
- TV: начальный фокус основных действий/раздач/файлов, возврат к hero, отсутствие focus scale, явный контраст. Phone: закреплённые CTA и полноразмерная панель при крупном шрифте. Сохранены отмена запросов и подготовки, server file ID, системные Back/IME, шифрование и прогресс.
- Проверено на fixtures: 27 core tests; mock HTTP JacRed/TorrServer; synthetic metadata. Debug и unsigned release обоих APK, Android lint проходят.
- Проверено в Android runtime: phone API26/36 и TV API36 — первый кадр локального MP4, пауза кнопкой нового UI, seek, Back, сохранение/resume; TV D-pad/focus. Tablet 1200x800dp — две панели; узкий phone 360dp/fontScale 2.0 — полный сценарий. Системная fullscreen-подсказка Android учтена отдельно в тестах.
- Live JacRed и пользовательский TorrServer в этой визуальной итерации не проверялись. Пользовательские устройства не подключались; MJAnime не изменялся.
- Сравнения и точные результаты завершаются в `docs/ui-audit/`; финальная публикация и CI будут записаны после окончания workflow.
