# MJWatch — состояние реализации

## Кандидат 0.9.0 — телефон → ТВ (#26)

Managed-просмотр и системное Android-управление реализованы; выпуск ещё не опубликован. Сервер 0.1.5, один выбранный ТВ, несколько независимых приёмников, общий список и переименование своих устройств. Direct remote pairing и автоматические Cast-уведомления не реализованы. [Проверки, ограничения и матрица приёмки](docs/testing/phone-tv/README.md). Пользователь утвердил выпуск 0.9.0 с managed-просмотром; direct в объём этого релиза не входит. Проверки исходного полного №26, включая Cast, не объявляются завершёнными.

## 0.5.5 — простой вход по приглашению и сохранение CA

У владельца вместо сырого кода — понятное описание приглашения, кнопка копирования с подтверждением и отпечаток сервера для сверки. Скопированное приглашение помечено sensitive. У нового пользователя «Вставить приглашение» заполняет адрес/порт/код/CA и отдельно запрашивает подтверждение сертификата; далее нужны только новый пароль и «Создать аккаунт и войти». Буфер читается по нажатию; технические поля скрыты в ручных параметрах. Поддерживаются прежние JSON-приглашения.

Убрана потеря CA при неверном адресе: больше нет проглатывания ошибки с одновременной очисткой поля. Нормализуются настоящие и экранированные переносы PEM; CA проверяется до записи и хранится для точного origin. Импорт очищает прежний пароль, не выполняет сетевых запросов и не меняет доверие до отдельного подтверждения. TLS-проверки имени и цепочки не ослаблены.

Проверено: 40 core unit tests, Android API36 runtime fixture копирования/вставки, явное подтверждение CA, изоляция по порту, готовность формы создания аккаунта; lint и сборки mobile/TV. [Отчёт и скриншот](docs/testing/0.5.5/README.md). Реальный TLS-сбой пользователя ещё не воспроизведён и не объявляется подтверждённо исправленным.

## 0.5.4 — исправление unsupported_field в настройках TorrServer

Форма отправляла изменения всех ключей снимка; клонированный JSONObject сравнивался по ссылке и попадал в patch как неподдерживаемое поле. Теперь patch ограничен рекламируемыми сервером редактируемыми полями; одинаковые числовые значения разных JVM-типов не считаются правкой. Служебные и неизвестные поля остаются в серверном снимке. Подтверждённый отказ операции освобождает локальную запись ожидания; неизвестный результат сохраняет возможность повторной проверки. Сообщение синхронизации аккаунта скрыто в форме TorrServer, чтобы оно не выглядело подтверждением применения настроек.

Проверено: 38 core unit tests, lint и сборки обоих APK. Есть регрессионные fixtures вложенных объектов/массивов, запрета служебных полей и числовых преобразований. [Отчёт](docs/testing/0.5.4/README.md). Реальное применение к пользовательскому серверу не выполнялось.

## 0.5.3 — минимальная RAM 500 МБ

По решению пользователя порог общей физической RAM снижен до 500 000 000 байт. Это минимум допуска, верхний лимит потребления памяти не задаётся. Свободное место по-прежнему проверяется отдельно: минимум 2 ГиБ на файловой системе /var/lib. Обновлены сообщение об ошибке, серверная документация и release notes. Граничный fixture проверяет отказ на 499 999 999 байт, допуск на 500 000 000 байт, 512 МиБ и 1 ГБ, а также отказ при нехватке диска. Семь Python-тестов, 36 core unit tests, lint и сборки обоих APK прошли; фактическая установка/воспроизведение на сервере с 500 МБ не проверены.

## 0.5.2 — допуск Ubuntu/Debian по возможностям

Список конкретных VERSION_ID удалён: Ubuntu 26.04 и другие версии Ubuntu/Debian допускаются при наличии Python 3.9+, systemd, apt-get и системных утилит, архитектуры amd64/arm64 и прежнего минимума памяти/диска. Другие семейства и производные ID не объявляются совместимыми автоматически. Ошибки Python и отсутствующих утилит показаны отдельно. [Проверки и ограничения](docs/testing/0.5.2/README.md). Проверены шесть Python-тестов с матрицей preflight-fixtures, Go race/vet, 36 core unit tests, lint и сборка обоих APK. Реальная установка на пользовательском Ubuntu 26.04 пока не проверена.

## 0.5.1 — этапы автоматической установки

Реализован отдельный нативный экран по панели «Автоматическая настройка» макета 01-connection-and-install-v2: пять этапов, подтверждённые шаги, текущий индикатор, остановка/разрыв связи, подробности и копирование безопасной диагностики, повторная проверка, повтор установки и возврат к SSH-параметрам. Экран успеха оформлен по панели «Готово». Используется общий UI телефона/планшета/TV.

Устранена потеря причины ошибки: terminal status больше не превращается в IllegalArgumentException с сообщением о локальном HTTP. Сервер сохраняет настоящий этап при ошибке и отмене; системные ошибки различаются по имени команды без её аргументов. SSH-параметры и подпись поставки имеют отдельные сообщения. Причина сбоя на пользовательском сервере пока не подтверждена.

Проверено: 36 core unit tests, Go race tests/vet, 3 Python-проверки обработчика статуса, lint обоих APK и сборка mobile/TV. Android API36: новый UI-тест на локальном отрицательном fixture прошёл (этапы, ошибка, подробности без секретов и возврат к параметрам), в том числе при шрифте 200%. [Отчёт](docs/testing/0.5.1/README.md). Пользовательский сервер не подключался, новая live-установка пока не проверена. Опубликован [0.5.1-test.20 (1020)](https://github.com/MJSasha/mj-watch/releases/tag/test-20), исходники `1567a85`. [CI №20](https://github.com/MJSasha/mj-watch/actions/runs/43763064500) полностью зелёный: build, phone API35, TV API36 и publish. Оба скачанных APK проверены по applicationId, версии, SHA256 и прежнему сертификату; update.json совпадает с файлами. Подписанные APK: `dist/published-test-20/MJWatch-mobile.apk` и `dist/published-test-20/MJWatch-tv.apk`.


Managed v1 опубликован в 0.5.0. Сервер 0.1.0 имеет отдельную подписанную ZIP-поставку и обновляется из приложения независимо от APK. Изменения direct/managed и пределы приёмки: [отчёт](docs/testing/SERVER-MANAGEMENT-V1.md). Исторические отчёты ниже описывают прежний direct с локальной историей; новый direct её не записывает.

Предыдущая публикация: [0.5.0-test.19 (1019), Latest](https://github.com/MJSasha/mj-watch/releases/tag/test-19), исходники `0a82997`. [CI №19](https://github.com/MJSasha/mj-watch/actions/runs/43760217990) полностью зелёный: build, phone API35, TV API36 и publish. Реально скачанные APK проверены по versionCode, applicationId, SHA256 и прежнему сертификату; подпись и файлы серверного ZIP проверены отдельно. Серверный ключ сохранён в защищённом Actions Secret для следующих совместимых поставок. Данные 0.2.x ниже сохранены как исторический отчёт.

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
- [Сравнительная галерея 24 экранов](docs/ui-audit/index.html), [отчёт и ограничения](docs/ui-audit/README.md), исходные PNG и логи сохранены в репозитории. Боковая панель «После» подтверждена пользователем; панель «До» остаётся только baseline.
- [CI #16](https://github.com/MJSasha/mj-watch/actions/runs/43738788214), исходник `5770049`: build, phone API35, TV API36 и publish-testing — success. [Релиз 0.4.0-test.16](https://github.com/MJSasha/mj-watch/releases/tag/test-16), code1016: оба APK, update.json, SHA256SUMS. Скачанные APK проверены по SHA256/size/package/version и закреплённой совместимой тестовой подписи. Локальные копии: `dist/test-16/MJWatch-mobile.apk`, `dist/test-16/MJWatch-tv.apk`.

## 2026-11-15 — 0.4.1: настройки, Back и оформление MJ

- Разделены страницы Jackett/JacRed, TorrServer и каталога; исправлены возврат после сохранения, предупреждение о несохранённых изменениях и сохранность других секретов.
- Унифицированы строки качества/аудио/субтитров и rail TV/планшета; filled-иконки, скругления, разделитель и выделение текущего раздела соответствуют макетам.
- У длинных raw-названий отдельная зона управления, компактный заголовок и полное имя ниже; CTA учитывает фактическую высоту при крупном шрифте.
- Обновления вынесены на отдельную страницу по образцу MJReader/MJAnime. README, баннер, badges и скриншоты оформлены в стиле семейства MJ.
- Проверено: 27 unit tests, debug/release и lint; phone API36 стандартный suite 14 tests (6 passed / 8 skipped), новые UI-регрессии при 200%, tablet split и TV D-pad/playback/focus. [Отчёт](docs/testing/0.4.1/REPORT.md).
- По решению пользователя TMDB оставлен без изменений: личный ключ и прямой API; общий Actions Secret не используется. Серверная часть отложена.
- [CI #17](https://github.com/MJSasha/mj-watch/actions/runs/43742632904), исходник `57b567c`: build, phone API35, TV API36 и publish-testing — success. [0.4.1-test.17](https://github.com/MJSasha/mj-watch/releases/tag/test-17), code1017, опубликована. Оба APK скачаны и проверены по SHA256/size, package/version и совместимой подписи; копии в `dist/test-17/`.
