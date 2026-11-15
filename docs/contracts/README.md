# Проверенные контракты

Jackett: v0.24.3067, commit 1dcaecd45170ff28769a2bd0f1af4979f1328abb.
TorrServer: MatriX.150 (reference), MatriX.145.2 (предоставленный live server).
SHA-256 изученных Go API 145.2 сохранены рядом, исходник https://github.com/YouROK/TorrServer/tree/MatriX.145.2/server.

`file_stats[].id` передаётся как `index`, никогда не позиция в UI. Multipart upload одного файла возвращает объект; несколько — массив. `save` в multipart не посылается: даже строка false означает save в Go endpoint. JSON add: save_to_db=false.

`core/src/test/resources/torrserver-145-upload.json` — ответ разрешённого сервера для открытого фильма Sintel, https://webtorrent.io/torrents/sintel.torrent (CC BY 3.0, Blender Foundation). Не содержит пользовательских торрентов или credentials.
XML fixture синтетический, основан на Torznab RSS https://github.com/Jackett/Jackett#torznab-query-syntax. Live Jackett не предоставлен.

TMDB API v3: https://developer.themoviedb.org/reference/search-multi, ru-RU, read access token в Keystore. По умолчанию none. Логотип получен без изменений с официальной страницы https://www.themoviedb.org/about/logos-attribution, условия https://developer.themoviedb.org/docs/faq. Live TMDB не тестировался без токена.

Jackett DownloadController v0.24.3067: https://github.com/Jackett/Jackett/blob/v0.24.3067/src/Jackett.Server/Controllers/DownloadController.cs . `GET /dl/{indexerId}` проверяет `jackett_apikey`, возвращает bencode body либо HTTP redirect с `Location: magnet:...`. Этот redirect обрабатывается как данные, без HTTP-follow. Только magnet передаётся TorrServer; исходный URL Jackett с ключом туда не отправляется. Отдельный mock HTTP test подтверждает контракт.
