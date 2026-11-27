# Проверка выпуска MJ Watch 0.12.46

[GitHub Release](https://github.com/MJSasha/mj-watch/releases/tag/test-289) опубликован как Latest. Тег test-289 указывает на ae10173e7d3cc25fbf198131d5b00825d718dd01. [Релизный CI](https://github.com/MJSasha/mj-watch/actions/runs/45917670699) прошёл.

Скачанные APK телефона и TV имеют версию 0.12.46, код 1289 и сертификат проекта. Размеры и SHA-256 совпали с update.json и SHA256SUMS.txt. Публичный Latest отдаёт тот же манифест, что и выпуск. [Результаты проверки APK](apk-verification.txt).

- Телефон, Android 36 AVD. Исходная 0.12.45 (1283) [нашла и скачала 0.12.46](phone-update-available.png). Android [запросил подтверждение](phone-system-confirmation.png). После установки время первой установки прежнее. Холодный запуск открыл приложение в сохранённом деморежиме, [главная загрузилась](phone-after-restart.png).
- Android TV, Android 36 AVD. Официальный TV APK установлен поверх 0.12.45 (1283) через adb install -r. Сохранилось время первой установки. Версия 0.12.46 (1289) [открылась после холодного старта](tv-after-restart.png).

[Плавающая панель, клавиатура и трёхкнопочный режим](../0.12.45-polish/floating-navigation/) · [Нативный аудит](../0.12.45-polish/audit/) · [Замеры старта Android TV](../0.12.45-polish/tv-startup-metrics.md)
