#!/usr/bin/env bash
set -euo pipefail
: "${GITHUB_REPOSITORY:?}" "${RUNNER_TEMP:?}"
out=${1:-dist/release}
channel=release
if [[ "${MJWATCH_DRY_RUN:-false}" == true && "${GITHUB_REF:-}" != refs/heads/main ]]; then
  channel=local-test
fi
SERVER_SUPPLY=$(python3 scripts/verify_release_artifact.py "$out" "$channel")
if [[ "${MJWATCH_DRY_RUN:-false}" == true ]]; then
  echo "Ready to publish $GITHUB_SHA at $(date -u +%FT%TZ)"
  exit 0
fi
[[ "${GITHUB_REF:-}" == refs/heads/main ]]
VERSION_NAME=$(sed -n 's/^mjwatchVersionName=//p' gradle.properties)
TAG="test-${GITHUB_RUN_NUMBER}"
SERVER_VERSION=${SERVER_SUPPLY#mjwatch-}
SERVER_VERSION=${SERVER_VERSION%-supply.zip}
cat > "$RUNNER_TEMP/release-notes.md" <<EOF
MJWatch $VERSION_NAME — стабильный выпуск.

Установка и обновление:
- MJWatch-mobile.apk — телефон и планшет.
- MJWatch-tv.apk — Android TV.
- Подписанная серверная поставка: mjwatch-$SERVER_VERSION-supply.zip.
- Устанавливайте APK поверх предыдущей версии: подпись совместима с опубликованными тестовыми выпусками, данные сохраняются.
- Проверить обновление можно в Настройки → Обновления. Установку подтверждает Android.

Что изменилось:
- На телефонах, планшетах и Android TV карточки фильмов и сериалов в подборках, поиске, рекомендациях и списке «Буду смотреть» теперь показывают название поверх крупной обложки.

FFmpeg распространяется по LGPL 2.1 или новее. [Исходники и пересборка](https://repo.maven.apache.org/maven2/io/put/media3-ffmpeg-decoder/1.11.1+1/media3-ffmpeg-decoder-1.11.1+1-ffmpeg-source.tar.xz).

Перед публикацией CI проверяет сборки Android, unit-тесты, lint, сервер и приложение на эмуляторах телефона и Android TV.

Публикация не вводит новую подпись APK или серверной поставки. Старые клиенты продолжают находить обновления: технические test-N и channel=testing сохранены как совместимый формат, сам GitHub Release не является prerelease и помечен Latest.

Вход на TV: https://github.com/$GITHUB_REPOSITORY/blob/$GITHUB_SHA/docs/testing/TV-QR.md
Приглашения ссылкой: https://github.com/$GITHUB_REPOSITORY/blob/$GITHUB_SHA/docs/testing/INVITATION-LINKS.md
Проверки TV-плеера: https://github.com/$GITHUB_REPOSITORY/blob/$GITHUB_SHA/docs/testing/issue-32-player/README.md
Исходники: $GITHUB_SHA. Контрольные суммы: SHA256SUMS.txt.
EOF

gh release create "$TAG" "$out/MJWatch-mobile.apk" "$out/MJWatch-tv.apk" "$out/update.json" \
  "$out/SHA256SUMS.txt" "$out/$SERVER_SUPPLY" --repo "$GITHUB_REPOSITORY" --target "$GITHUB_SHA" \
  --title "MJWatch $VERSION_NAME" --notes-file "$RUNNER_TEMP/release-notes.md" --latest
