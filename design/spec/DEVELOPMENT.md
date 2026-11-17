# MJWatch — техническое задание v1.0

Историческая спецификация базового приложения. Для нового сценария телефон → ТВ приоритет имеет [phone-tv-v1](../phone-tv-v1/README.md).

Дата: 15.11.2026. Статус: дизайн и спецификация для реализации, не готовое приложение.

## 1. Цель и зафиксированные решения

Создать нативное Android-приложение для просмотра фильмов, аниме и мультфильмов через настроенные пользователем Jackett/Torznab и TorrServer. Пользователь находит произведение или раздачу, выбирает релиз и файл; приложение передаёт торрент TorrServer и воспроизводит HTTP-поток в Media3.

Поставки: **MJWatch mobile APK** (телефон и планшет) и **MJWatch TV APK** (Android TV / Google TV). Kotlin, Jetpack Compose Material 3 для mobile, Compose for TV для TV, общий data/domain/player. Разные оболочки и applicationId; совместная установка возможна. Предлагаемые ID: `dev.mjwatch.mobile` и `dev.mjwatch.tv`; закрепить до первого релиза. Не использовать package/signing identity MJAnime.

MJAnime — источник визуального языка и UX-инвариантов, не обязательная кодовая база. Исходный Flutter-проект не изменять. Переписывание допустимо пользователем; Android-native выбран сознательно. Авторизация аккаунта MJAnime, Cast, апскейлер, его серверные API не переносятся автоматически.

**Приоритеты:** сначала надёжный путь до первого кадра, затем история/серии, затем визуальная полировка. Не выдавать статичные макеты за воспроизводящий APK.

## 2. Комплект дизайна и правило разрешения расхождений

- `../archive/initial-v1/index.html`: альбом 24 статичных редактируемых макетов; навигация альбома работает, UI внутри кадров демонстрационный.
- `../archive/initial-v1/screens/*.jpg`: 10 TV, 13 mobile, 1 tablet и обзорный борд.
- [DESIGN-SYSTEM.md](DESIGN-SYSTEM.md): размеры, компоненты, фокус, движение, доступность.
- [FLOWS.md](FLOWS.md): экраны, переходы, Back, ошибки, состояния.
- [INTEGRATIONS.md](INTEGRATIONS.md): контракты Jackett/TorrServer/метаданных, отмена и безопасность.
- [ACCEPTANCE.md](ACCEPTANCE.md): проверяемые условия готовности.
- [config.example.json](config.example.json): предлагаемый конфиг MJWatch, не конфиг Jackett/TorrServer.
- [../HANDOFF.md](../HANDOFF.md): стартовая инструкция следующему агенту.

Приоритет: последнее указание пользователя → ТЗ/контракты → дизайн-система → иллюстративный кадр. Числа сидов, скорость, фильмы, статусы и адреса в макетах не являются измерениями. «Тихая орбита» — вымышленный контент. Статус «Подходит устройству» в реализации допустим только при проверенных свойствах; неизвестные свойства показывать как неизвестные.

## 3. Объём v1

| Область | Обязательно |
|---|---|
| Настройка | Jackett URL, API key, один или несколько индексаторов/Torznab URL; TorrServer URL, optional Basic Auth; независимая проверка; импорт/экспорт конфига |
| Каталог | Фильмы/аниме/мультфильмы, поиск, карточка, категории, загрузка/пусто/ошибка; полноценные обложки при подключённых метаданных |
| Раздачи | Поиск Torznab; пагинация по capabilities; partial results; фильтры разрешение/язык/сиды/источник; явный выбор |
| Файлы | Получение списка после metadata; выбор video-file; сезоны/серии при уверенном сопоставлении, ручной выбор иначе |
| Поток | Add/resolve → metadata → file → Media3 prepare → first frame; отмена на любом этапе; восстановление ошибок |
| Плеер | Play/pause, ±10 с, seek, аудио, субтитры, масштаб, скорость, смена раздачи; сохранение прогресса |
| Библиотека | Продолжить, история открытия, «Буду смотреть»; локальное хранение; удаление с undo |
| Платформы | Remote-only TV; touch mobile; адаптивный планшет; system Back/IME; mobile PiP; MediaSession |
| Оформление | Dark/light/system, reduced motion, TalkBack, большие шрифты, контраст |

Не входит в v1: встроенный торрент-движок, установка/администрирование Jackett/TorrServer, облачный аккаунт и синхронизация между APK/устройствами, загрузка для офлайн-просмотра, Cast/DLNA, апскейлер, универсальное транскодирование, автоматическое распознавание опенингов. Кнопку «Пропустить вступление» не показывать без подтверждённых таймкодов. Нет обещания, что любое MKV/4K/HDR/аудио заработает на любом устройстве.

## 4. Важная граница: каталог и парсер

Jackett/Torznab — источник **раздач**, не полноценный каталог произведений. Постеры, описания, персональные подборки, корректный список сезонов и связанные произведения нельзя гарантировать его ответом.

Предлагаемый основной metadata adapter — TMDB (movie + tv, язык `ru-RU` с fallback к original title). Это проектное решение, не ранее выбранный пользователем сервис. Подключать отдельной настройкой, проверить правила использования/атрибуцию и зафиксировать версию API перед реализацией. Ключ не вшивать в публичный APK. Экран «О приложении» должен содержать необходимую атрибуцию выбранного провайдера. Дополнительный anime adapter допустим позже; не добавлять его в v1 без необходимости.

Обязательный режим без metadata provider: поиск Jackett работает напрямую; карточки используют исходный release title, доступные технические свойства и нейтральный Material-значок. Hero, рейтинги, описания, «похожие» скрываются. Сохраняются ручное воспроизведение, история и избранное. В первый запуск подключение каталога необязательное; UI честно объясняет отсутствие обложек. Пользователь может выбрать корректное произведение при неоднозначном сопоставлении. Раздачи разных лет/сезонов не объединять только по похожему названию.

## 5. Архитектура без лишней инфраструктуры

Один Gradle-проект, два application-модуля:

```text
:app-mobile              Compose Material 3, mobile/tablet navigation
:app-tv                  Compose for TV, rail, D-pad focus map
:core                   models, repositories, Torznab, TorrServer,
                        metadata adapter, Room, DataStore, security
:playback               Media3, session/lifecycle, progress, track selection
:design-system          colors, typography, shared primitives/tokens
```

Не создавать отдельный модуль на каждый экран заранее. UI → ViewModel → repository; immutable UiState/StateFlow; coroutine cancellation; DI может быть простым constructor injection. Общие domain-модели не содержат Compose/Activity. Внутри `:core` разделить пакеты по ответственности; выделять новые модули только при доказанной пользе.

Предлагаемый минимум Android 8/API 26 для обеих поставок. `compileSdk`/`targetSdk`, Kotlin, AGP, Compose BOM, TV и Media3 — совместимые стабильные версии на момент реализации, закреплённые в version catalog. Не объявлять API 26 поддержанным до реальной сборки и прогона. TV manifest: Leanback launcher/banner, touchscreen not required. Mobile manifest: launcher, PiP-capable playback activity. Архитектуры/ABI определять по зависимостям, не копировать blindly MJAnime.

Playback-session держать вне composable recomposition; один активный ExoPlayer. MediaSessionService — если нужен переход в PiP/системное управление. `onStop` не должен останавливать активный PiP. Закрытие PiP/экран блокировки/аудиофокус должны иметь явно проверенные правила. Без PiP при уходе в фон видео ставится на паузу; не продолжать неожиданно звук. Отказ аудиофокуса → пауза; краткая потеря → восстановление лишь при прежнем намерении пользователя играть.

## 6. Данные и локальное состояние

```text
MediaItem: id(provider:id), kind(movie|series), title, originalTitle?, year?,
           genres[], poster?, backdrop?, overview?, externalIds{}, source
Episode: mediaId, seasonNumber?, episodeNumber?, title?, externalId?
Release: id(indexer:guid), rawTitle, indexerId, infoHash?, magnet?, downloadUrl?,
         sizeBytes?, seeders?, peers?, publishedAt?, resolution?, videoCodec?,
         hdr?, audioHints[], metadataConfidence, sourceProvenance
TorrentFile: serverFileId, originalPath, sizeBytes, isVideo, isSample,
             episodeMatch?, matchConfidence
PlaybackKey: mediaId OR releaseId + season/episode OR normalizedPath + infoHash
Progress: key, positionMs, durationMs?, updatedAt, completed, audioPreference,
          subtitlePreference, releaseId?, infoHash?, fileId?, normalizedPath?
LibraryEntry: mediaId/releaseId, bookmarkedAt?, lastOpenedAt, progressKey?
Connection: id, baseUrl, credentialRef?, cleartextPolicy, capabilities?, checkedAt?
```

`fileId` стабилен только в рамках конкретного торрента/контракта сервера. Нельзя переносить индекс файла на другую раздачу. `lastOpenedAt` и `positionMs` — разные поля; открытие карточки не перезаписывает playback position. Длительности в domain всегда миллисекунды, UI форматирует отдельно. Null duration не равен нулю.

Room: история, прогресс, избранное, cache metadata. DataStore: non-secret preferences/config schema version. Secrets: зашифрованное хранилище с ключом Android Keystore; без plaintext backup. Не обещать перенос секретов при экспорте. Запись прогресса каждые 5 с воспроизведения, на pause/seek/exit и при lifecycle transitions; атомарный upsert. Завершённым считать ≥95% известной длительности или событие ended. Не удалять историю завершённого просмотра.

Продолжение: восстановить release/file, проверить доступность, затем seek после timeline-ready. Прогресс не сбрасывать при ошибке. Если прежняя раздача исчезла, открыть выбор новой; после выбора предложить перенести позицию с предупреждением о возможной иной версии монтажа. При неизвестном соответствии серии автоматического переноса нет. На смене раздачи аудио/субтитры подбирать по language/role, не по числовому индексу дорожки.

## 7. Нативность и производительность

- Никакого WebView для UI приложения. Макеты HTML — только формат дизайн-документа.
- System IME, voice recognizer при наличии; microphone permission только по явному действию.
- Mobile: edge-to-edge с корректными insets, predictive Back, Material sheet/dialog, ripple, light haptic на выбор/подтверждение при системном разрешении.
- TV: Compose for TV и явная карта фокуса; без touch-only контролов и рисованной клавиатуры.
- Изображения — Coil, memory/disk cache, размер по viewport; при отсутствии изображения — fallback выбранного произведения, не чужой постер.
- Сеть/парсинг/Room не в main thread. XML streaming parser, bounded responses, lazy lists, стабильные keys; hero загружать после 180 мс стабильного TV-фокуса и отменять устаревший запрос.
- Цель: отклик фокуса/нажатия ≤100 мс, плавность на 60 Hz. Macrobenchmark/profile release на выбранном тестовом устройстве, p95 UI frame ≤16,7 мс как целевая метрика, отдельно учитывать медиа/GPU. Не заявлять достижение по макетам.
- Время первого кадра зависит от сети/пиров/раздачи: KPI измерять с фиксированными тестовыми файлами и условиями, не обещать универсальные 2 секунды.

## 8. Разработка по этапам

1. **Каркас и контракты:** два собирающихся APK, общие модели, темы, navigation, fake repositories; проверить launchers и одновременную установку.
2. **Подключения и поиск:** конфиг, секреты, caps, XML, результаты/partial/empty/error; offline fixture tests.
3. **Первый кадр:** адаптер закреплённой версии TorrServer, magnet и torrent-upload, выбор файла, отмена, Media3, progress. Сначала контролируемые MP4/MKV и разрешённый test torrent.
4. **Карточки и библиотека:** metadata adapter + честный fallback, истории, сезоны, ручной mapping, resume.
5. **TV/mobile полировка:** все макеты, focus return, sheets, IME, PiP, tracks, motion, large text.
6. **Приёмка:** матрица устройств/кодеков/сети, regression, release builds, версии/подписи, release notes. Публикацию и production credentials не делать по этому дизайн-заданию.

Этап закрывается только проверяемыми результатами из [ACCEPTANCE.md](ACCEPTANCE.md). Если реальных адресов/версий серверов ещё нет, этапы 1–2 и fake integration не блокируются. Live compatibility и готовность к выпуску остаются неподтверждёнными.

## 9. Что уточнить перед live integration

Реальные URL и версии Jackett/TorrServer, список выбранных индексаторов и их caps; наличие HTTPS/Basic Auth/reverse-proxy subpath; выбранный metadata provider/credential; тестовые устройства и codec capabilities. Пароли и ключи вводятся в приложении или локальном secret store, не в репозитории и не в ТЗ. Дизайн не требует этих данных для начала разработки.
