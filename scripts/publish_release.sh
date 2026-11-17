#!/usr/bin/env bash
set -euo pipefail
: "${MJWATCH_TEST_KEYSTORE_B64:?Set the MJWATCH_TEST_KEYSTORE_B64 Actions secret first}"
: "${GITHUB_RUN_NUMBER:?}"
: "${GITHUB_REPOSITORY:?}"
: "${GITHUB_SHA:?}"
: "${RUNNER_TEMP:?}"
: "${ANDROID_HOME:?}"
VERSION_CODE=$((1000 + GITHUB_RUN_NUMBER))
BASE_VERSION=$(sed -n 's/^mjwatchVersionName=//p' gradle.properties)
VERSION_NAME="$BASE_VERSION"
TAG="test-${GITHUB_RUN_NUMBER}"
SIGNING_STORE="$RUNNER_TEMP/mjwatch-testing.keystore"
trap 'rm -f "$SIGNING_STORE"' EXIT
export SIGNING_STORE
python3 - <<'PY'
import base64, os
from pathlib import Path
p = Path(os.environ['SIGNING_STORE'])
p.write_bytes(base64.b64decode(os.environ['MJWATCH_TEST_KEYSTORE_B64'], validate=True))
p.chmod(0o600)
PY
unset MJWATCH_TEST_KEYSTORE_B64
./gradlew "-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8" :app-mobile:assembleRelease :app-tv:assembleRelease --no-daemon "-PmjwatchVersionCode=$VERSION_CODE" "-PmjwatchVersionName=$VERSION_NAME"
mkdir -p dist
APKSIGNER="$ANDROID_HOME/build-tools/36.0.0/apksigner"
for platform in mobile tv; do
  "$APKSIGNER" sign --ks "$SIGNING_STORE" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "dist/MJWatch-$platform.apk" "app-$platform/build/outputs/apk/release/app-$platform-release-unsigned.apk"
  "$APKSIGNER" verify --verbose --print-certs "dist/MJWatch-$platform.apk" > "$RUNNER_TEMP/cert-$platform.txt"
  ACTUAL_CERT=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$RUNNER_TEMP/cert-$platform.txt")
  test "$ACTUAL_CERT" = "$(cat scripts/test-signing-cert.sha256)" || { echo 'Unexpected signing certificate'; exit 1; }
done
python3 scripts/build_update_manifest.py --dist dist --tag "$TAG" --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"
SERVER_VERSION=$(python3 -c 'import json; print(json.load(open("core/src/main/assets/server/manifest.json"))["version"])')
SERVER_SUPPLY="dist/server/mjwatch-$SERVER_VERSION-supply.zip"
test -f "$SERVER_SUPPLY"
python3 - "$SERVER_SUPPLY" <<'PY'
import hashlib, pathlib, sys
p = pathlib.Path(sys.argv[1])
with pathlib.Path('dist/SHA256SUMS.txt').open('a') as out:
    out.write(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n')
PY
cat > "$RUNNER_TEMP/release-notes.md" <<EOF
MJWatch $VERSION_NAME — стабильный выпуск.

Установка и обновление:
- MJWatch-mobile.apk — телефон и планшет.
- MJWatch-tv.apk — Android TV.
- Подписанная серверная поставка: mjwatch-$SERVER_VERSION-supply.zip.
- Устанавливайте APK поверх предыдущей версии: подпись совместима с опубликованными тестовыми выпусками, данные сохраняются.
- Проверить обновление можно в Настройки → Обновления. Установку подтверждает Android.

Что изменилось:
- В окне загрузки после проверки APK появляется кнопка установки обновления. Можно установить сразу или нажать «Позже»; фокус TV остаётся в окне.
- Поля плеера затемняются по цветам текущего кадра. До готовности видео они чёрные; статичная синяя заливка убрана (#24).
- Карточки каталога одной высоты: под название зарезервированы две строки.
- Серверная поставка $SERVER_VERSION исправляет Maintenance check: служебные проверки больше не создают устройства. Обновление сервера очищает старые служебные записи по их подтверждённым root-квитанциям.

Чтобы очистить уже накопленные Maintenance check, после установки APK обновите управляемый сервер до $SERVER_VERSION: Настройки → Мой сервер → Обслуживание → Обновить сервер. Реальные устройства, аккаунты и история сохраняются; перед обновлением создаётся проверенная зашифрованная копия.

Проверки перед публикацией: Android unit/lint/debug+release build, серверные race/vet и phone/TV runtime tests.
Озвучка определяется по названию раздачи; фактические дорожки выбираются в плеере. История объединяется по связанному ID фильма/сериала; неизвестные соответствия не угадываются.

Публикация не вводит новую подпись APK или серверной поставки. Старые клиенты продолжают находить обновления: технические test-N и channel=testing сохранены как совместимый формат, сам GitHub Release не является prerelease и помечен Latest.

Вход на TV: https://github.com/$GITHUB_REPOSITORY/blob/$GITHUB_SHA/docs/testing/TV-QR.md
Приглашения ссылкой: https://github.com/$GITHUB_REPOSITORY/blob/$GITHUB_SHA/docs/testing/INVITATION-LINKS.md
Исходники: $GITHUB_SHA. Контрольные суммы: SHA256SUMS.txt.
EOF
if [[ "${MJWATCH_DRY_RUN:-false}" == "true" ]]; then
  echo 'Signed APKs and update manifest verified; publication skipped in local dry run.'
else
  gh release create "$TAG" dist/MJWatch-mobile.apk dist/MJWatch-tv.apk dist/update.json dist/SHA256SUMS.txt "$SERVER_SUPPLY" --repo "$GITHUB_REPOSITORY" --target "$GITHUB_SHA" --title "MJWatch $VERSION_NAME" --notes-file "$RUNNER_TEMP/release-notes.md" --latest
fi
