<p align="center"><img src="docs/images/banner.svg" alt="MJWatch — ваше кино на любом экране" width="100%"></p>

<p align="center">
  <a href="https://github.com/MJSasha/mj-watch/releases/latest"><img src="https://img.shields.io/github/v/release/MJSasha/mj-watch?style=for-the-badge&label=Скачать%20APK&color=c7ff68" alt="Скачать MJWatch"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-c7ff68?style=for-the-badge&logo=android" alt="Android 8.0+">
  <a href="https://github.com/MJSasha/mj-watch/actions/workflows/android.yml"><img src="https://github.com/MJSasha/mj-watch/actions/workflows/android.yml/badge.svg" alt="Android checks"></a>
</p>

<p align="center"><b>Найдите фильм. Выберите экран. Продолжите с того же места.</b><br>Фильмы и сериалы на телефоне, планшете и Android TV — в одном приложении.</p>

<img src="docs/images/tv-home.png" width="100%" alt="Кинематографичная главная MJWatch на Android TV">

<table>
<tr><td width="33%" align="center"><b>Находите своё кино</b><br>Каталог TMDB, постеры и описания.<br>Поиск Jackett / JacRed и фильтры раздач.</td><td width="33%" align="center"><b>Смотрите на любом экране</b><br>Телефон, две панели планшета, TV с пультом.<br>Кнопка «Смотреть» предлагает экран, когда подключён TV.</td><td width="33%" align="center"><b>Возвращайтесь к просмотру</b><br>Избранное, сезоны и серии.<br>История и позиции между устройствами в managed.</td></tr>
</table>

## Ваше кино — всегда под рукой

<table>
<tr><td align="center" width="34%"><img src="docs/images/mobile-home.png" width="300" alt="Главная MJWatch на телефоне"></td><td width="66%"><img src="docs/images/tablet.png" width="100%" alt="Поиск и карточка фильма рядом на планшете"><br><b>Больше экран — больше пространства для кино.</b><br>Адаптивные экраны, тёмная и светлая темы, управление касанием или пультом.</td></tr>
</table>

## Плеер, в котором главное — видео

<img src="docs/images/player.jpg" width="100%" alt="Плеер MJWatch с тонкой шкалой времени и компактными элементами управления">

**MKV и MP4 · аудиодорожки и субтитры · двойной тап ±10 секунд · PiP · серии и автопереход.**
Контейнеры воспроизводит Media3; доступность конкретных кодеков зависит от Android-устройства.

<table>
<tr><td align="center"><b>Ваши подключения</b><br><img src="docs/images/settings.png" width="280" alt="Настройки подключений MJWatch"></td><td align="center"><b>Обновления внутри приложения</b><br><img src="docs/images/updates.png" width="280" alt="Проверка и установка обновлений MJWatch"></td></tr>
</table>

## Начните смотреть

1. [Скачайте последнюю версию](https://github.com/MJSasha/mj-watch/releases/latest): **MJWatch-mobile.apk** для телефона/планшета, **MJWatch-tv.apk** для телевизора.
2. Подключите **Jackett / JacRed + TorrServer** или войдите на свой **managed-сервер MJWatch**.
3. Найдите фильм и выберите раздачу. Проверить интерфейс можно в демонстрации с локальным видео.

**Офлайн на телефоне и планшете.** Выберите раздачу, нажмите **Скачать** и выберите файл фильма или отдельную серию. Загрузка сохраняется на этом устройстве и при подключённом TV. Перед поездкой откройте **Моё → Загрузки** и дождитесь готовности. Отсюда можно смотреть видео без сети или удалить его, чтобы освободить место на устройстве.

Managed добавляет аккаунты, приглашения, историю, синхронизацию и просмотр на подключённом TV. Для удалённого просмотра нужен managed-сервер и открытый MJWatch на телевизоре. Direct работает с готовым TorrServer без истории и удалённого подключения TV. [Подключение и сервер](server/README.md) · [Приглашения](docs/testing/INVITATION-LINKS.md) · [Телефон → TV](docs/testing/phone-tv/README.md).

Обновляйте APK **поверх установленного приложения**, чтобы сохранить данные. Встроенный updater проверяет размер, SHA-256, пакет и подпись; установку подтверждает Android. [Как выпускаются обновления](docs/UPDATES.md).

<sub>На изображениях — работающий интерфейс с демонстрационным каталогом и локальным видео. Приложение использует настроенные вами источники и не предоставляет фильмы.</sub>

<details>
<summary><b>Для разработчиков</b></summary>

Kotlin · Jetpack Compose · Media3 · Go / SQLite. JDK 21, Android SDK 36, Build Tools 36.0.0, Gradle 8.13. Укажите SDK в `local.properties` или `ANDROID_HOME`; подготовьте серверные assets по [инструкции сервера](server/README.md).

```sh
./gradlew :core:testDebugUnitTest :playback:testDebugUnitTest :app-mobile:lintDebug :app-tv:lintDebug
./gradlew :app-mobile:assembleDebug :app-tv:assembleDebug
```

[Состояние и ограничения](STATUS.md) · [Контракты](docs/contracts/README.md) · [Проверки](docs/testing/ACCEPTANCE-REPORT.md) · [Дизайн](design/README.md)

Программное декодирование аудио использует FFmpeg (LGPL 2.1 или новее) через расширение AndroidX Media3. Уведомления о лицензиях входят в APK. [Исходники и инструкции по пересборке и замене библиотеки](https://repo.maven.apache.org/maven2/io/put/media3-ffmpeg-decoder/1.11.1+1/media3-ffmpeg-decoder-1.11.1+1-ffmpeg-source.tar.xz) · [Репозиторий сборки и уведомления](https://github.com/putdotio/media3-ffmpeg-decoder/tree/v1.11.1%2B1).

Из семьи [MJAnime](https://github.com/MJSasha/mj-anime) и [MJReader](https://github.com/MJSasha/mj-reader). Ключи и пароли шифруются Android Keystore; внешней аналитики нет.

</details>
