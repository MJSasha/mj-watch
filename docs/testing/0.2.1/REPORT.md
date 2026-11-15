# MJWatch 0.2.1 (3): JacRed regression

Дата 2026-11-15; исходники `7f3496eb1c97c0eb59de19375b0eb869ef1b397c`.

## Исправления

- Пустые индексаторы раньше блокировали check/search до сети; теперь `all`.
- Отдельный JSON adapter JacRed, автоматический выбор для jac.red и ручной выбор для custom deployment. Пустой ключ не отправляется, API mode сохраняется в config.
- Jac.red отвечает HTTP403 на curl User-Agent, HTTP200 на явный `MJWatch/0.2.1 (Android)`; client теперь идентифицирует приложение. HTML вместо XML больше не считается успешным caps.
- Live вызовы check→search подряд вызвали HTTP429. Добавлен один отменяемый повтор только read-only GET с коротким Retry-After/default 2s; длинный cooldown возвращается пользователю. POST add/upload этот механизм не затрагивает.
- Local HTTP policy denial и HTTP-коды не скрываются общей ошибкой сети. Для .local в Android предлагается LAN IP; opt-in local HTTP обязателен.

## Доказательства

- 21 core tests без failures/errors: [XML](contracts.xml). Новые JSON/casing/dedupe, blank key/all, URL/prefix, User-Agent, HTML rejection, 429 bounded retry/cooldown, сохранение причины network policy.
- Локальные debug/release сборки обоих APK и lint прошли. [SHA256](APK-SHA256.txt). Остальные проверки 0.2.0 остаются историческими, а не автоматически повторёнными на новой версии.
- Android API36 ARM64: JacredFlowTest использует реальные HTTP adapters, синтетический JSON/mock TorrServer и локальный MP4 по HTTP; оба подключения без ключей/индексаторов, поиск, выбор, stream ID17 и фактический первый кадр прошли.
- Итоговый runtime после User-Agent/rate-limit fix: JacredFlowTest + SessionTest + VerticalFlowTest + LiveTorrServerTest — 4 tests OK. Последний тест реально использует MatriX.145.2, отдельные magnet и torrent upload исходного Sintel MP4 и Media3 first frame. [Лог](android-api36.txt).
- Live Android: `https://jac.red`, без ключа/индексаторов, check OK, поиск Sintel дал 12 playable release records, failures=[] после обработки rate limit.
- Live выбранный Sintel CC BY3, размер 60,775,464 bytes, показан 1 seed: TorrServer MatriX.145.2 принял magnet, но остался `stat=1`, `Torrent getting info`, `file_stats=[]`. Через 60 секунд test закономерно FAILED по metadata timeout: [лог](jacred-live-metadata-timeout.txt). Первый кадр именно этой раздачи НЕ подтверждён. Точная причина отсутствия метаданных/пиров не установлена.
- Никаких rem/wipe/drop, сохранения torrent в DB и изменений настроек общего сервера.

Финальный [CI 43727812428](https://github.com/MJSasha/mj-watch/actions/runs/43727812428), `9ee2120`: build, phone API35, TV API36 — success. После предыдущего CI исправлено ожидание UI-теста: завершение async save определяется появлением home-list, затем проверяется поле поиска; код приложения относительно `7f3496e` не менялся. Полный поиск→плеер на синтетических HTTP fixtures не равен подтверждённому воспроизведению любой живой раздачи.
