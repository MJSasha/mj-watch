# Старт следующего агента

## Задача

Реализовать MJWatch в `/Users/bloba/projects/MJWatch`: нативный Kotlin Android-клиент с двумя APK (mobile/tablet и TV), поиск раздач через настроенный Jackett/Torznab, воспроизведение через настроенный TorrServer и Media3. Дизайн основан на MJAnime, но пользователь явно разрешил новый нативный стек.

## Сначала прочитать

1. `../../spec/DEVELOPMENT.md` — scope, архитектура, данные, этапы.
2. `../../spec/INTEGRATIONS.md` — сеть/контракты/caps/file IDs/отмена.
3. `../../spec/FLOWS.md` — переходы, фокус, player Back, state matrix.
4. `../../spec/DESIGN-SYSTEM.md` — tokens/layout/motion/a11y.
5. `../../spec/ACCEPTANCE.md` — критерии готовности.
6. Открыть `index.html` и `screens/00-overview.jpg`, затем нужный индивидуальный screen. PDF содержит те же макеты.

## Начать с конкретного вертикального среза

Собрать два запускаемых APK с общими fake repositories и темой; реализовать подключение → поиск → раздача → файл → плеер → сохранение позиции на контролируемом локальном видео. Затем подключить реальные адаптеры. UI HTML не переносить как WebView; это описание native screen, не приложение.

## Не потерять решения

- TV и mobile одинаково обязательны; tablet адаптируется из mobile APK.
- Jackett/Torznab уже выбран, не спрашивать заново о типе парсера.
- Не превращать Jackett в выдуманный каталог. Metadata adapter optional; без него raw search/playback работает.
- Один player/session, bounded retry; не ждать полного cache до запуска media request.
- Список файлов может прийти только после add/metadata. Server file ID не равен позиции строки.
- Не передавать URL Jackett с API key серверу потока, не логировать секреты.
- Back из основного TV player выходит; из panel закрывает panel. Фокус возвращается.
- История открытия и прогресс независимы. Long OK не запускает short OK.
- Не запускать/не менять соседний MJAnime и не подключаться к пользовательскому TV/проектору.
- Не заявлять live integration/runtime/плавность по fake tests или screenshot.

## Где есть открытые интеграционные данные

До реального подключения понадобятся URL/версии Jackett и TorrServer, caps выбранных индексаторов, auth/reverse-proxy path и metadata credential. Они не нужны для каркаса, fake integration, UI и тестов. Ключи вводить в secret store, не просить коммитить их. Предлагаемый каталог TMDB не подтверждён пользователем; оставить переключаемым.

## Definition of done

Рабочие APK, пройденные релевантные пункты `../../spec/ACCEPTANCE.md`, проверка focus/navigation на TV, accessibility на mobile и runtime playback с точным описанием окружения. Финальный отчёт разделяет fake, emulator, реальное устройство и серверную совместимость. Публикация не входит в текущую задачу.
