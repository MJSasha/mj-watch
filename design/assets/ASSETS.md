# Происхождение ресурсов

- `orbit.png`: создан через ImageGen 15.11.2026 специально для вымышленного фильма «Тихая орбита». Это демонстрационная обложка, не реальный каталог.
- `frieren.jpg`, `titan.jpg`, `apothecary.jpg`, `frieren-scene.jpg`: скопированы из `MJAnime/test/fixtures/tv/{0,1,3,8}.img`. Соответствующие source URL находятся в `MJAnime/test/fixtures/tv/urls.json` (Shikimori). Использованы как reference content в локальном дизайн-проекте; права на изображения не передаются, не включать fixture-постеры в production bundle как собственные assets.
- `Roboto-Regular.ttf`, `Roboto-Medium.ttf`: из MJAnime/assets; оригинальная лицензия рядом `Roboto_LICENSE.txt`.
- `MaterialIcons-Regular.ttf`: официальный google/material-design-icons, https://github.com/google/material-design-icons/blob/master/font/MaterialIcons-Regular.ttf; Apache 2.0, `MaterialIcons-LICENSE.txt`.
- Логотип MJWatch — текстовая адаптация обозначения MJ, не заявление об отдельном зарегистрированном знаке.

Макеты не используют внешние шрифты/CDN и не отправляют запросы к серверам просмотра. Неизвестные значения в реализации должны оставаться неизвестными; fake statistics из макетов не копировать в runtime.
