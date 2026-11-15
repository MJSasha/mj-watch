# Проверенные контракты

[Managed v1: HTTPS API, версии и схема данных](MANAGED-V1.md). Настройки TorrServer идут по SSH; TMDB управляется через HTTPS admin API.

Jackett: v0.24.3067, commit 1dcaecd45170ff28769a2bd0f1af4979f1328abb.
TorrServer: MatriX.150 (reference), MatriX.145.2 (предоставленный live server).
SHA-256 изученных Go API 145.2 сохранены рядом, исходник https://github.com/YouROK/TorrServer/tree/MatriX.145.2/server.

`file_stats[].id` передаётся как `index`, никогда не позиция в UI. Multipart upload одного файла возвращает объект; несколько — массив. `save` в multipart не посылается: даже строка false означает save в Go endpoint. JSON add: save_to_db=false.

`core/src/test/resources/torrserver-145-upload.json` — ответ разрешённого сервера для открытого фильма Sintel, https://webtorrent.io/torrents/sintel.torrent (CC BY 3.0, Blender Foundation). Не содержит пользовательских торрентов или credentials.
XML fixture синтетический, основан на Torznab RSS https://github.com/Jackett/Jackett#torznab-query-syntax. Live Jackett не предоставлен.

TMDB API v3: https://developer.themoviedb.org/reference/search-multi, ru-RU, read access token в Keystore. По умолчанию none. Логотип получен без изменений с официальной страницы https://www.themoviedb.org/about/logos-attribution, условия https://developer.themoviedb.org/docs/faq. Live TMDB не тестировался без токена.

Jackett DownloadController v0.24.3067: https://github.com/Jackett/Jackett/blob/v0.24.3067/src/Jackett.Server/Controllers/DownloadController.cs . `GET /dl/{indexerId}` проверяет `jackett_apikey`, возвращает bencode body либо HTTP redirect с `Location: magnet:...`. Этот redirect обрабатывается как данные, без HTTP-follow. Только magnet передаётся TorrServer; исходный URL Jackett с ключом туда не отправляется. Отдельный mock HTTP test подтверждает контракт.

## JacRed JSON (0.2.1)

Поддержан `/api/v2.0/indexers/{source}/results?Query=...`, JSON `Results` (также camelCase): `Title`, `Tracker`, `MagnetUri`, `Size`, `Seeders`. Пустой source → `all`; пустой ключ не передаётся. Проверка — тот же read-only endpoint с пустым Query, без Torznab caps. Для jac.red режим auto выбирает JSON, для своего сервера есть явный выбор.

Проверенный исходный контракт: [jacred-fdb/jacred @ 3235bc82](https://github.com/jacred-fdb/jacred/tree/3235bc82a9c1a484a8e8726803b39331a7ef0cca), `Controllers/JackettController.cs`, `Models/Api/RootObject.cs`, `Models/Api/Result.cs`. Это версия исходного reference, не установленная версия jac.red. `core/src/test/resources/jacred.json` — синтетический fixture.

Live 2026-11-15: с curl User-Agent сервис возвращает nginx HTTP403; с идентификацией `MJWatch/0.2.1 (Android)` — HTTP200, JSON `Results`, 12 результатов Sintel и успешный запрос с пустым Query. Guarded client теперь явно идентифицирует приложение этим User-Agent. Ключ/пароль для этих запросов не использовались, TLS проверяется штатно. Установленная версия сервиса неизвестна.
