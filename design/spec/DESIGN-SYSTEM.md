# MJWatch — дизайн-система

Расширение 17.11.2026: [телефон → ТВ](../phone-tv-v1/README.md). Общие токены сохраняются; прежние quick-play и встроенные transport controls не применяются к телефону в роли контроллера ТВ. Устройства имеют общий список с иконками типа.

## Основа

Наследуем утверждённую семью MJAnime/MJReader: Roboto, Material icons, тёмный сине-чёрный фон, лаймовый акцент, крупные обложки. TV: слева только иконки с accessibility labels; hero до краёв контента, локальный scrim под нижним левым текстом и мягкий нижний fade, без автоматической карусели. Визуальное выделение фокуса не меняет размер карточки. Это сохранённые решения из локальных `MJAnime/AGENTS.md`, `docs/MJANIME-DESIGN.md`, `lib/ui.dart` и изученных screenshots.

Новая часть: phone bottom navigation, touch sheets, adaptive tablet, технические экраны раздач и состояния streaming. Нативные controls важнее буквального воспроизведения HTML-пикселей. TV-макет — 1280×720 reference pixels; mobile — 412×892 logical dp reference. Для TV измерять actual density/window и использовать типографику TV, не переносить 1280 px как 1280 dp при любой density.

## Цветовые токены

| Token | Dark | Light | Назначение |
|---|---|---|---|
| background | #0C0E15 | #FAFAF7 | Основной фон |
| surface | #191E29 | #EAEDE6 | Карточки/нижняя навигация |
| surfaceHigh | #242B39 | #E1E5DC | Вложенные controls |
| onSurface | #F0F2EC | #20251F | Основной текст |
| onSurfaceVariant | #ABB5C5 | #4D584E | Вторичный текст |
| primary | #C7FF68 | #345A05 | CTA/активный tab/фокус |
| onPrimary | #0C0E15 | #FFFFFF | Текст главного CTA |
| outline | #68778D | #687267 | Поля/значимые границы |
| error | #FFB4AB | #A32323 | Ошибка + icon/text |
| warning | #F1C879 | #775500 | Ограничения + icon/text |

Не использовать translucent текст. На обложке измерять итоговый контраст с scrim. Акцентные selected-container: 12–18% primary над непрозрачной surface. Основной/вторичный текст ≥4,5:1, крупный текст/значимые icons/borders ≥3:1. Не использовать цвет как единственный сигнал. Dynamic color — возможная пользовательская опция после v1; фирменная палитра по умолчанию.

## Размеры, ритм, шрифт

Roboto Regular/Medium, стандартный Android fallback; реальные sp, системный fontScale. База отступов 4; последовательность 4/8/12/16/20/24/32/40/48. Не фиксировать высоту текстового блока в реальном UI.

| Элемент | Mobile dp/sp | TV reference @1280×720 |
|---|---|---|
| Горизонтальное поле | 20 dp, compact 16 | content x=128, right=48 px |
| Rail | нет; tablet 80–88 dp | 88 px, safe controls inset |
| Заголовок hero | 34–36 sp / 40 | 52–56 px / 60 |
| Заголовок экрана | 24–30 sp | 36–40 px |
| Тело | 16 sp / 24 | 18–20 px / 26 |
| Вторичное | 14 sp / 20 | 16–18 px |
| Технические badges | 12–13 sp (не основное чтение) | 14–16 px при финальной native адаптации |
| CTA | h≥52 dp, radius 26 | h≥52 px reference, итог ≥48 dp focus target |
| Icon | 24 dp, target≥48 dp | 24–28 px, большой focus target |
| Card radius | 16–24 dp | 14–20 px |
| Sheet radius | 28 dp top | right panel/dialog radius 20 |
| Focus ring | keyboard 2–3 dp | 3 px + 4 px наружный offset reference |

TV-макеты технических меток иллюстрируют группировку. На реальном TV издалека не уменьшать значимые данные ниже комфортного 16 sp: при нехватке места переносить, упрощать строку или раскрывать детали. Не компенсировать плотность уменьшением шрифта.

## Компоненты и состояния

- **PosterCard:** image ratio 2:3, title до двух строк, год/тип; content description = title/year/type/progress. История может использовать 16:9 кадр. Нет сетевого availability badge до проверки.
- **ContinueCard:** название, явный номер серии, время, determinate progress только при известной длительности. Main tap → detail; отдельная play button → resume. На TV короткое OK всегда detail.
- **ReleaseRow:** source/raw title, resolution/codec/HDR, размер, seeders, audio hints, freshness; неизвестное = «Не указано». Compact summary + раскрываемые исходные данные. В desktop-like TV list нет мелких action targets внутри строки.
- **FileRow:** постоянный номер серии + title + исходное имя (details), размер, выбор radio, прогресс. Sample/bonus отделены, но не удалены из списка.
- **ConnectionField:** постоянный label, value, password toggle с доступным именем, inline error. Error не стирает ввод. Сохранить можно offline; «Проверен» включает timestamp и сбрасывается после изменения адреса/ключа.
- **Primary/secondary button:** default, pressed, focused, disabled, loading. Disabled не выглядит как обычный CTA. Loading защищён от повторного запроса; Cancel остаётся доступен.
- **Skeleton:** размер будущего блока; без скачка layout; при reduced motion статичен. При первой загрузке текста — не выдавать случайные фильмы за данные пользователя.
- **Error/empty/offline:** icon + заголовок + причина + действие; не показывать JSON, stacktrace или API-ключ.
- **Snackbar:** удаление истории/избранного с «Отменить», 5 с минимум; при accessibility увеличить системным recommended timeout. TV toast не перехватывает фокус; действие undo доступно через контекстное меню/диалог.

## Движение

| Событие | Длительность | Поведение |
|---|---:|---|
| TV focus | 0–100 мс | контур сразу, tonal fill; без scale/glow |
| Touch pressed | системная | Material ripple; haptic лишь выбор/подтверждение |
| Hero меняется от фокуса | delay 180 + fade 220 мс | отмена старой загрузки; title соответствует выбранному item |
| Push/pop screen | 280–320 мс | стандартный navigation transition, spatial continuity |
| Poster → detail, mobile | 300–360 мс | shared bounds только при стабильном image key, иначе crossfade |
| Sheet | 250–300 мс | native anchored transition; finger-driven dismiss |
| Player controls | 160–200 мс | opacity; видео не анимировать вместе с overlay |
| Переключение темы | 150–200 мс | без белой вспышки |

Кривые: стандартная Material easing, вход decelerate, выход accelerate; конкретные motion tokens закрепить в Compose. `Animator duration scale=0` / accessibility reduced motion → убрать spatial/shared transform, оставить мгновенную смену или crossfade ≤100 мс. Никаких infinite decorative animations. UI не ждёт окончания анимации для получения ввода.

## TV focus

Один current focus; selected rail/category не равен focused. Main→rail по Left с крайней левой карточки; rail→main возвращает remembered node. Up/down между полками сохраняет ближайший x. Граница списка не циклична. После dialog, player и details возвращать focus к исходному элементу и scroll; если он исчез, ближайший surviving sibling, затем heading/main action. Не терять фокус при завершении HTTP-запроса. Long OK не должен вызывать short OK. Все меню имеют Back; скрытый overlay не содержит активных focus nodes.

## Mobile и планшет

Bottom navigation 4 labelled destinations: Главная / Поиск / Моё / Настройки. В detail/player/flow настройки bottom bar скрывается. Navigation state восстанавливается по top-level destination. При ширине <600 dp — один pane; 600–839 dp — rail, один pane либо list/detail по фактической доступной ширине; ≥840 dp — два pane, минимум ~320 dp у каждого, не перекрывать hinge. Размерные классы брать из actual window, не модели устройства.

Insets: system bars, IME, cutout, gesture/nav. CTA остаётся над IME или экран прокручивается. 200% font scale: chips wrap/scroll, source sheet разворачивается в full screen, текст/CTA не перекрываются; без fixed line clipping. Системные status/nav bars в mock — иллюстрация, в приложении ими управляет Android.

## Доступность

Проверить TalkBack semantic order, headings, selected/radio/disabled, labels icon-only buttons, контраст обеих тем. Progress announces major state transition, не каждый скачок скорости. Ошибка переводит semantic focus к заголовку, не крадёт D-pad focus из пользовательского действия. Поддержать keyboard/switch access; gestures имеют видимые альтернативы. Player controls не скрывать, пока screen reader/keyboard активно ими пользуется. При pause controls остаются видимыми.
