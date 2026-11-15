# Интеграции: Jackett/Torznab → TorrServer → Media3

Это контракт MJWatch и план адаптеров. Проверка реальных серверов в дизайн-задаче не проводилась. Изучен публичный исходный код на 15.11.2026; `master` изменяем. Следующий агент обязан закрепить конкретные release/commit обоих серверов и сохранить очищенные fixtures ответов для них.

## 1. Конфигурация

[config.example.json](config.example.json) — non-secret экспорт MJWatch. Версия схемы обязательна. Секреты живут в credential store; `credentialRef` — ссылка, не значение. На другом устройстве ссылки неразрешены до повторного ввода. При импорте сначала preview адресов/источников, затем явное «Применить»; не выполнять сетевые запросы до применения. Сохранение возможно без соединения. Не читать реальные настройки MJAnime.

Конфиг содержит:

- Jackett baseUrl, API credentialRef, массив разрешённых indexer IDs и относительных Torznab paths; credentials связываются с origin.
- TorrServer baseUrl с optional subpath, optional credentialRef, тип auth none/basic. Не предполагать стандартный порт: он берётся из введённого URL.
- metadata provider none/tmdb и отдельный credentialRef.
- preferred resolution/audio/subtitle, theme, localHttpOptIn и allowed local hosts.

Валидация: http/https для server URL; абсолютный URL; никакого userinfo/query/fragment в baseUrl. Path префикс reverse proxy сохранять. Reject javascript/file/content/intent для сетевых endpoint. API path не может менять origin через `//host`. HTTP выключен по умолчанию; локальный HTTP разрешается явным opt-in только выбранным local endpoints. Нельзя отключать TLS validation ради self-signed. Private/local transport — отдельная политика, не причина блокировать ожидаемый локальный TorrServer.

Не допускать произвольный публичный cleartext через широкую Android network policy: если dynamic HTTP hosts требуют manifest-level разрешения, центральный URL-policy guard обязателен во всех HTTP клиентах, включая Coil, Media3 и redirect handlers. Он разрешает cleartext только заданным local origins. Проверить также redirect/downgrade и DNS resolution. Credentials переносить только внутри исходного origin; редирект на новый origin не наследует Authorization/API key. Link с tracker passkey — секрет, не отправлять в лог/аналитику/metadata provider.

## 2. Jackett / Torznab adapter

Базовый путь Jackett: `/api/v2.0/indexers/{indexer}/results/torznab/api`. Capabilities: `t=caps`; generic search: `t=search&q=...`; `apikey` добавляется клиентом из secret store. Не делать запрос `all` без необходимости: отдельные выбранные индексаторы позволяют показать частичный результат и ограничить медленный источник. Возможности специализированного movie/tv search и категории читать из `caps`, не предполагать одинаковыми у всех. [Официальный README Jackett](https://github.com/Jackett/Jackett#torznab-query-syntax).

Проектная политика MJWatch:

1. Проверить caps каждого настроенного indexer. Кэшировать 24 ч, сбросить при смене конфига; кнопка ручной перепроверки.
2. Поиск после IME submit или debounce 400 мс для ≥2 значимых символов. Новый query отменяет старый; query-id защищает от запоздалого ответа.
3. Максимум 3 параллельных запроса. Connect 5 с, response 20 с, общий deadline поиска 25 с. Истёкший источник пометить, не уничтожать результаты других.
4. `limit` до 50, offset только если поддерживается. Merge stable; дедуп по нормализованному infoHash, иначе indexer+guid (не по title). Отдельные release versions сохраняются.
5. Категории — capabilities mapping, не магические одинаковые числовые константы. «Аниме» и «Мультфильмы» не выводить только из слова в release name, если есть metadata classification. В direct mode unknown category остаётся unknown.
6. Raw response читать как XML RSS/Torznab; namespaces, CDATA, HTML entities; отключить external entity/DTD. XML `<error>` обрабатывать даже при HTTP 200. Limits: 10 МБ response и 500 items за один ответ; malformed data = per-source error.
7. Нормализовать item: title, guid, link/enclosure, pubDate, size/seeders/peers/infohash/magneturl, category, source. Поля optional. 0 seeders и отсутствующие seeders различны. `enclosure` не всегда прямой .torrent; проверить response Content-Type/body/redirect.
8. Разрешение/кодек/язык, распарсенные из имени, — hints с provenance `release_name`; не гарантированные media tracks. Отображать original name в подробностях.

Никаких встроенных пользовательских tracker cookies/индексаторных правил. Jackett настраивается отдельно. Приложение подключается к выбранным настроенным feeds.

## 3. Ranking раздач

По умолчанию автоматического запуска первой раздачи нет. «Смотреть» открывает список; подтверждённый прошлый выбор допускает resume.

Порядок: уверенность соответствия произведению/эпизоду → отсутствие известных несовместимостей → preferred resolution/audio → seeders >0 → меньший достаточный размер. Unknown compatibility не равна compatible. Сиды — snapshot, не прогноз скорости. В UI recommendation может называться «Рекомендуем»; «Подходит устройству» допустимо лишь при достаточных codec/device facts. 4K/HDR не повышать автоматически. Нулевые/unknown сиды доступны, но с объяснением. Фильтры не удаляют неизвестные значения молча: иметь «Не указано» и clear filters.

## 4. TorrServer adapter

Публичные источники, проверенные при подготовке:

- [`torrents.go`](https://github.com/YouROK/TorrServer/blob/master/server/web/api/torrents.go): `POST /torrents`, JSON actions `add`/`get` и др.; поля link/hash/title/category/save_to_db.
- [`upload.go`](https://github.com/YouROK/TorrServer/blob/master/server/web/api/upload.go): `POST /torrent/upload`, multipart file. Форма ответа зависит от версии; текущий комментарий и историческое поведение расходятся, parser должен тестироваться на закреплённой версии.
- [`stream.go`](https://github.com/YouROK/TorrServer/blob/master/server/web/api/stream.go): stream URL использует link, index, play; examples index=1. Индекс сервера не равен нулевому индексу UI.

Предлагаемый интерфейс (это **внутренний** API приложения, не реальные URL сервера):

```kotlin
interface StreamGateway {
    suspend fun checkConnection(): ServerCapabilities
    suspend fun add(input: TorrentInput): TorrentHandle
    suspend fun status(handle: TorrentHandle): TorrentSnapshot
    suspend fun files(handle: TorrentHandle): List<TorrentFile>
    suspend fun stream(handle: TorrentHandle, file: TorrentFile): StreamRequest
}
// StreamRequest: URI + origin-bound headers + content hints, never credentials in URI
```

Последовательность:

1. Если есть подходящий magnet — передать как link при add. `save_to_db=false` по умолчанию (пользователь не просил постоянную коллекцию сервера).
2. Иначе скачать .torrent через Jackett своим HTTP-клиентом, ограничить 10 МБ, проверить bencode/валидность и ожидаемый origin. Временный файл private, удалить после upload/отмены. **Не передавать TorrServer Jackett URL с apikey.** Передать multipart torrent bytes. Для текущего endpoint проверить singleton/array ответ на version fixture.
3. Получить server hash/handle. При неизвестном результате POST не слать повтор вслепую: проверить по известному infoHash; если нельзя доказать результат, показать повтор пользователю, защищённый session-id. Одновременно не создавать два add для одного выбора.
4. Poll metadata с interval 1 с первые 10 с, затем 2 с; останавливаться на выходе/смене selection/lifecycle policy. Status object нормализовать по адаптеру версии, не фиксировать поля из макета.
5. Перечислить файлы. Использовать server file ID, а не отсортированную позицию строки. Сортировка в UI не меняет ID. Single video без ambiguity выбирается автоматически; multi-video требует экран файлов, кроме уверенно сопоставленного сохранённого эпизода.
6. Сформировать URL средствами URI builder: base path + encoded filename, `link=hash`, `index=serverFileId`, `play`. Auth headers добавлять Media3 data source; credentials не в query. Не включать `save`/другие мутации случайно.
7. Передать запрос Media3 и начать prepare — это инициирует получение данных. Не ждать несуществующего абстрактного «100% кеша» до создания HTTP media request (иначе возможна взаимная блокировка). Если версия поддерживает preload, он optional и version-tested.
8. Start playback при Player.READY и playWhenReady; завершение loading UI по first-frame callback. READY без first frame не объявлять успешным показом видео. Позицию восстановить после получения seekable timeline.

TorrServer в v1 — источник direct HTTP stream. Не считать, что это HLS, не обещать автоматический bitrate ladder или транскодирование. Media3 извлекает контейнер; codec capability зависит от устройства. Playback errors должны отличать сеть/контейнер/декодер/дорожку. При неподдерживаемом аудио предложить другую дорожку, затем другую раздачу.

## 5. Отмена, таймауты и конкуренция

Одна playback session с generation ID. Каждый ответ сверяется с active generation; отменённая сессия никогда не открывает плеер позже. Back/Cancel закрывает requests/poll/player prepare, сохраняет полезный selection и возвращает в раздачи/карточку. Отмена клиента не означает удаление торрента/кеша на общем сервере.

Не вызывать `wipe`, не удалять чужие torrents. В v1 вообще не удалять автоматически даже свой hash: другой клиент мог уже его использовать. Освободить HTTP connection и локальные файлы, сервер управляет cache lifecycle. UI не обещает «полностью остановить раздачу на сервере».

Project defaults: connect=5 с, control response=15 с, metadata/first-frame watchdog=60 с без прогресса; на 30 с объяснение «Подготовка занимает больше времени», действия «Отменить», «Другая раздача» если сервер доступен. Progress resets watchdog только на реальный прирост байтов/этап, не на успешный повтор status request. Максимальная подготовка 180 с → explicit continue/retry/cancel, не бесконечный spinner. Упавшая media connection — максимум 2 reconnect с backoff 1/3 с и сохранением позиции; 401/403 не retry-loop. Seek to uncached area — отдельный buffering state, тот же понятный timeout.

## 6. Телеметрия/секреты

По умолчанию нет внешней аналитики. Локальная диагностика: request-id, adapter version, endpoint label, duration, safe status, player code. Redact apikey, Authorization, cookies, magnet tracker passkeys, filenames при экспорте diagnostics по выбору пользователя. Не отправлять release title в crash reporting без явной настройки. Metadata provider получает только нужные search/id, никогда magnet или пароль.

## 7. Источники платформенных решений

- [Android TV / Media3](https://developer.android.com/media/media3/ui/androidtv) — D-pad integration.
- [Media3 supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats) — контейнер, sample format и capabilities различаются; тестировать на конкретном устройстве.
- [Window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes) — адаптация к фактическому окну.
- [Predictive Back](https://developer.android.com/develop/ui/compose/system/predictive-back) и [PiP](https://developer.android.com/develop/ui/compose/system/picture-in-picture) — системные возможности mobile.

Остальные таймауты, сортировка, module layout и UX — проектные решения MJWatch, не утверждения официальной документации.
