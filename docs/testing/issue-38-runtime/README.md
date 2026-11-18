# Issue #38 — runtime HEVC recovery, 0.9.7

База main: `e8040ff35a782b4426c4753962dca66ebfa68ecc` / 0.9.6. Пользователь: Xiaomi L1 Pro, Android TV **14**. Тот же файл воспроизводится на телефоне; раздача сейчас недоступна. Из присланного экрана: **4003 · video/hevc · hvc1.2.4.L150.90 · 3840×2160** — HEVC Main10, Main Tier Level 5.0. HDR/битрейт/частота кадров из этого экрана не определяются.

4003 означает ошибку при декодировании samples. `setEnableDecoderFallback(true)` из 0.9.6 действует при инициализации, поэтому не восстанавливает такой runtime-сбой. [Media3 PlaybackException](https://developer.android.com/reference/androidx/media3/common/PlaybackException), [DefaultRenderersFactory](https://developer.android.com/reference/androidx/media3/exoplayer/DefaultRenderersFactory).

## Изменение

`DecoderFallback` подключён к общей фабрике плеера. Для ошибки 4003 с известным MediaCodec и форматом он один раз пересоздаёт декодер с синхронной очередью, если кодек заявляет поддержку параметров. На Android 12+ обычная фабрика Media3 использует asynchronous adapter, в том числе на Android TV 14 пользователя; на более старых версиях это ограниченный свежий перезапуск. [Фабрика Media3 1.9.2](https://github.com/androidx/media/blob/1.9.2/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/mediacodec/DefaultMediaCodecAdapterFactory.java).

При повторной ошибке сбойный кодек исключается, но только если есть другой, заявляющий поддержку формата. Максимум на файл: один совместимый перезапуск и два переключения на разные декодеры. Повторная ошибка того же исключённого кодека, отсутствие альтернативы, неизвестный кодек или неподходящая ошибка не создают цикл. Множество исключений потокобезопасно: селектор также читает его на playback thread.

Перед reprepare сохраняются MediaItem/ID, позиция и playWhenReady; скорость и настройки остаются на том же ExoPlayer/MediaSession. При смене файла исключения и бюджет сбрасываются, рабочая синхронная очередь сохраняется в пределах этой Activity. Сеть/авторизация/HTTP-восстановление не менялись. В диагностике добавлено имя последнего MediaCodec; URL, заголовки, токены и текст исключений не показываются.

## Локальные проверки

- Unit: 15 playback tests, включая ограничения количества попыток и сброс на новый файл.
- Обе debug-сборки и lintDebug прошли — [лог](gates.txt).
- Native Android TV API36 arm64: все 23 playback instrumentation-теста прошли, включая три сценария инъекции 4003 (pause, play и единственный доступный декодер). Сначала пересоздаётся тот же реальный AVC-декодер с настоящим `SynchronousMediaCodecAdapter` (проверен фактический класс адаптера), затем происходит переход на другой реальный декодер. Сохраняются позиция, pause/play, скорость и MediaItem ID — [лог](compatible-recovery.txt).
- H.264 и HEVC Main 1080p: первый кадр, duration, аудио/видеотреки и seek прошли. Main10 Level 5.0 UHD: проверен точный разбор MIME/codec string/3840×2160, при отключённом выборе video/audio; это **проверка формата, не декодирования 4K** — [лог](formats.txt).
- Полный playback suite: 23/23 — [лог](player-suite.txt). При единственном декодере разрешён один совместимый перезапуск, затем восстановление прекращается.

Полный TV suite с подписанным серверным пакетом 0.1.6: 52 теста, 38 passed и 14 штатных opt-in skips, ошибок нет — [лог](tv-suite.txt). Первый локальный запуск без сгенерированных серверных assets дал два падения ServerUpdateFlowTest; после подготовки пакета полный suite прошёл без изменений assertions.

В TV BufferingUiTest `/echo` отделён от очереди видео-ответов так же, как уже было сделано в mobile-тесте. Проверка сервера больше не может расходовать ответ для `/movie.mp4`; утверждения восстановления не ослаблены.

## Ограничения проверки Main10

Оба HEVC-декодера локального эмулятора сообщают `NoSupport [codec.profileLevel, hvc1.2.4.L150.90]`. Попытка ожидать от этого эмулятора первый кадр UHD Main10 не прошла; это не доказательство ограничения Xiaomi. Поэтому capability/разбор формата проверяются отдельно, а реальный decode — на поддерживаемом HEVC Main 1080p и H.264. Производитель Xiaomi заявляет воспроизведение 4K; физическое разрешение матрицы 1080p само по себе не определяет поддержку кодеков. [Xiaomi](https://www.mi.com/my/product/xiaomi-smart-projector-l1-pro/).

Файл пользователя и сам MT9630 не воспроизведены в этой среде. Issue #38 остаётся открытым до проверки на Xiaomi. Новые software codecs и серверное перекодирование не добавляются.

## Тестовые данные

Собственные FFmpeg/libx265 ролики: `testsrc2`, 12 секунд, **2 fps**, CRF42, ultrafast, AAC32k, GOP2, `pools=1:frame-threads=1`. HEVC Main — yuv420p/1920×1080/level4; Main10 — yuv420p10le/3840×2160/level5, Main Tier. SDR; HDR и производительность фильма 24/30/60 fps этим не проверяются. Fixture находятся только в `playback/src/debug/assets` и должны быть исключены из release APK.

- `hevc-main-1080-fixture`: 358677 bytes, SHA-256 `9adc4a8c0aea4987c2da3991314f146a06e09232d190f56a9d65b56a2a6bae4a`.
- `hevc-main10-4k-fixture`: 932400 bytes, SHA-256 `317c933215cebb1babfe144fffda58765cbf5b9089ee16a6910dcaf93db2d6ed`.
