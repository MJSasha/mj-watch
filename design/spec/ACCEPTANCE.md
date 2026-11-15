# Критерии приёмки MJWatch

Эти проверки относятся к будущей реализации. Наличие макета/fixture не означает прохождение integration/runtime test. Следующий агент ведёт отчёт: версия приложения, build SHA, устройство/API/ABI, сервер/version, test fixture, результат, известные ограничения.

## Дизайн и поставки

- [ ] Два APK собираются из одного проекта, имеют разные applicationId и корректные launcher/Leanback entry; не заменяют MJAnime.
- [ ] Mobile запускается на phone/tablet; TV проходит основной путь только D-pad/OK/Back без мыши.
- [ ] 24 макета реализованы по смыслу; native layout адаптируется, нет requirement буквального совпадения статичных status bars.
- [ ] Темы dark/light/system, реальные insets, large font 200%, TalkBack, reduced motion, window resize.
- [ ] Не работает авто-карусель; focus не масштабирует карточки; hero соответствует выбранному title.

## Конфигурация и сеть

- [ ] Пустой/неверный URL, URL с userinfo/query, неподдерживаемая схема отклоняются inline без стирания данных.
- [ ] Reverse proxy prefix сохраняется у всех requests и streaming URI.
- [ ] Jackett и TorrServer проверяются отдельно; смена поля сбрасывает stale «Проверен»; offline save работает.
- [ ] Экспорт config и diagnostic logs не содержит ключей, Authorization, tracker passkeys. Импорт preview не совершает запросов.
- [ ] Новый origin redirect не наследует credentials; HTTPS downgrade отвергается; локальный HTTP работает только после opt-in для разрешённых local origins.
- [ ] Никакого trust-all TLS и отправки Jackett API key в TorrServer.

## Torznab (unit/contract + mock HTTP)

- [ ] caps без movie/tv search → generic fallback; неизвестная категория не теряется.
- [ ] RSS namespace, CDATA, Unicode, HTML entities, отсутствующие size/seeders, 0 seeders нормализуются корректно.
- [ ] HTTP 200 с XML error классифицируется ошибкой; 401, timeout, malformed XML и пустой результат различаются.
- [ ] Частичный результат сохраняется; повторяет только failed sources; поиск отменяет stale response.
- [ ] Дедуп по hash не смешивает разные cuts; без hash стабильные indexer+guid; page boundaries не повторяют карточки.
- [ ] XML external entity не выполняется; response/item limits enforced.
- [ ] Поиск без metadata provider позволяет выбрать raw release и смотреть. При отсутствии постера нет чужого изображения.

## TorrServer и playback session

- [ ] Magnet add и upload .torrent проверены отдельно на закреплённой версии. Single-object/array upload fixture соответствует адаптеру.
- [ ] Файл выбран по server ID, а не zero-based index UI; сортировка списка не меняет stream ID.
- [ ] Multi-file torrent с sample и 2 сезонами: корректный ручной выбор; unknown episode не сопоставляется самовольно.
- [ ] Путь с пробелами, кириллицей, %, #, ? корректно encoded ровно один раз; subpath preserved.
- [ ] Cancel на add/metadata/prepare отменяет поздний переход; session generation защищает от race.
- [ ] Неопределённый POST результат не приводит к бесконтрольным повторным add.
- [ ] Player prepare инициирует HTTP получение; нет deadlock «ждём буфер до открытия stream».
- [ ] READY без first frame не показывает успех. Metadata без byte progress не продлевает spinner бесконечно.
- [ ] После 30/60/180 с работают заданные сообщения/deadlines; rebuffering/seek имеют recovery.
- [ ] Ни один Back/Cancel/exit не вызывает wipe/rem чужих torrents; stop client не обещает stop shared server.

## Реальный player runtime

Минимальная матрица: phone API 26 и актуальный stable API, TV API 26/30+ на доступном тестовом устройстве/AVD; узкий phone ~360 dp и tablet landscape ≥840 dp. Не заявлять неподтверждённые версии как tested. Тестовое устройство пользователя подключать/управлять только при конкретном разрешении.

| Набор | Что проверить |
|---|---|
| MP4 H.264 + AAC, seekable | Первый кадр, audio, pause, seek, end, resume |
| MKV H.264 + 2 audio tracks + SRT | Track переключается без сброса position; subtitle off/language |
| HEVC/HDR (capable / incapable device) | Поддержанный путь и понятная несовместимость; без ложного обещания HDR |
| Unsupported audio/container | Ошибка и другая дорожка/раздача, без retry loop |
| Unknown duration / unseekable | Нет ложного progress percent и активного seek |
| Bandwidth drop/disconnect | Buffering, bounded retry, сохранение позиции |
| Seek в uncached участок | Возврат к playing либо recoverable timeout |
| TS restart / torrent removed | Recovery из сохранённого release, без утраты истории |
| Audio focus / rotate / background | Нет двойного player и неожиданного звука |
| PiP mobile | Enter/exit/close, media controls, overlays скрыты, position retained |

## UX / regression

- [ ] TV: rail → category → shelf → detail → releases → files → player → Back восстанавливает focus/scroll на каждом уровне.
- [ ] Long OK не запускает short OK. Открытие modal не оставляет второй focus под ней.
- [ ] IME системная; Back при открытой IME сначала закрывает её. Голос без recognizer корректно скрыт/disabled.
- [ ] Mobile CTA не перекрыт IME/navigation bar; source sheet при 200% шрифте прокручивается/fullscreen.
- [ ] История сортируется по lastOpenedAt, не positionMs. Открытие карточки не изменяет прогресс.
- [ ] Завершённый фильм в истории, но не в «Продолжить». Неизвестная длительность не считается завершением.
- [ ] Изменение раздачи не переносит file index/audio track index. Resume позиции требует подтверждения, если версия монтажа другая.
- [ ] Удаление локальной history/bookmark имеет undo и не изменяет TorrServer.
- [ ] Theme change не перезапускает player; process restart не запускает звук автоматически.

## Доказательства сдачи

Unit/contract suite + Compose UI/focus tests + lint/build debug/release + скриншоты ключевых состояний. Отдельный отчёт runtime playback с точными версиями серверов/кодеков и логами без секретов. FPS/плавность подтверждается profiler/benchmark; screenshot не доказывает скорость, воспроизведение или серверную совместимость. Публикация APK за рамками этого поручения.
