# Design QA — MJWatch

Дата: 15.11.2026.

**final result: passed — для статичного дизайн-проекта.** Это не приёмка Android-приложения и не результат live integration.

## Проверено

- Просмотрены исходные TV-скриншоты MJAnime `01-home.png`, `03-episodes.png`, актуальные инструкции и токены `lib/ui.dart`.
- Сохранены фирменная палитра, Roboto, Material-иконки, rail только с иконками, full-bleed TV hero, локальное затемнение, отсутствие scale у фокуса.
- 24 макета отрисованы и экспортированы через in-app browser: TV 1280×720, phone 412×892, player 892×412, tablet 1200×800. Изображения и шрифты загружаются локально.
- Проверены все TV/mobile состояния обзорными contact sheets; отдельно открыты исправленные dark/light settings и preparation, tablet и общий борд.
- Исправлены наложение TV-подсказки на карточки, несовпадение focus ring с play, перекрытие нижней строки настроек навигацией и текста подготовки закреплённой кнопкой.
- Контраст base color pairs рассчитан: dark onSurface/background 17,09:1, secondary/surfaceHigh 6,86:1, CTA 16,46:1; light 14,92:1, 5,83:1, CTA 8,04:1. Dark outline повышен с #526075 до #68778D для >3:1 на surface.
- Альбом на viewport 375 px: scrollWidth=375, 24 tile links, 0 broken images. Проверено открытие индивидуального макета и смена экрана через select. Console warn/error: отсутствуют в проверенном проходе.
- 27-страничный PDF отрендерен Poppler в PNG; просмотрена общая раскладка всех страниц и отдельно страницы 2, 15, 27. Русский шрифт встроен, обрезаний/наложений текста не обнаружено.
- JSON config/tokens корректно разбираются. Локальные Markdown-ссылки разрешаются. JS syntax check прошёл.

## Ограничения

- Рисунки статичны: не проверяют D-pad navigation, Android insets/IME/PiP, player lifecycle, 200% system font scale, TalkBack и реальные media formats. Всё это включено в ACCEPTANCE.md для следующего агента.
- HTML альбом масштабирует reference frames; это не модель адаптации native приложения. Tablet list показан в начальной позиции с продолжающимся ниже контентом; native list должен прокручиваться.
- Светлая тема показана на настройках, остальные экраны используют те же semantic tokens и требуют native regression.
- Визуальные data fixtures не являются ответами Jackett/TorrServer. Metadata provider пока предложен, не подключён.
- Перед реализацией закрепить версии API/зависимостей и проверить серверные fixtures. Не обещать runtime совместимость по этому отчёту.
