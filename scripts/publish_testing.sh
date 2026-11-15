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
VERSION_NAME="${BASE_VERSION}-test.${GITHUB_RUN_NUMBER}"
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
./gradlew :app-mobile:assembleRelease :app-tv:assembleRelease "-PmjwatchVersionCode=$VERSION_CODE" "-PmjwatchVersionName=$VERSION_NAME"
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
MJWatch $VERSION_NAME — управляемый сервер и синхронизация.

- Телефон/планшет: MJWatch-mobile.apk
- Android TV: MJWatch-tv.apk
- Подписанная серверная поставка: mjwatch-$SERVER_VERSION-supply.zip
- Проверка обновлений: Настройки → Обновления.
- Подпись APK совместима с предыдущими тестовыми выпусками; приложение обновляется с сохранением данных.
- Android требует разрешение на установку из MJWatch и подтверждение установки.

Что изменилось:
- Исправлена ошибка unsupported_field при сохранении TorrServer: отправляются только изменённые редактируемые поля, без служебных массивов и объектов.
- Минимальный объём RAM для автоустановки снижен до 500 МБ; порог свободного места — 2 ГиБ.
- Автоустановка принимает Ubuntu/Debian по системным возможностям, включая Ubuntu 26.04; проверены preflight-fixtures, полная установка на 26.04 пока не подтверждена.
- Экран пяти этапов установки: текущий шаг, конкретная ошибка, безопасная диагностика, повторная проверка и установка.
- Два режима: direct к готовому TorrServer и managed с аккаунтами, историей и синхронизацией.
- В direct новые открытия и позиции больше не сохраняются. Избранное остаётся. Старая история сохраняется до явного выбора; импорт в managed выполняется отдельно.
- Нативная установка TorrServer и MJWatch по root SSH, настройки сервера, пользователи и вход TV по коду.
- Каталог TMDB через HTTPS-сервис; общий ключ не передаётся клиентам.
- Версия сервера $SERVER_VERSION не зависит от APK: подписанный ZIP импортируется из приложения, установка сопровождается encrypted backup и проверкой отката.
- Существующие настройки источников, плеера, оформления и обновлений APK сохранены.

Проверки: unit/lint/build, серверные race/vet, phone/TV runtime; локально также проверены реальный TorrServer, установка Debian 12 arm64, чистое восстановление, обновление и откат, планшет и шрифт 200%.

Ограничения тестового канала: live-матрица Ubuntu/amd64, реальный TMDB-ключ и полный TalkBack не подтверждены. Сервер поддерживает схему БД 1; будущие схемы требуют миграций. Отдельная production signing identity не вводится.

Подробный отчёт: https://github.com/$GITHUB_REPOSITORY/blob/$GITHUB_SHA/docs/testing/SERVER-MANAGEMENT-V1.md

Исходники: $GITHUB_SHA. Контрольные суммы: SHA256SUMS.txt.
EOF
if [[ "${MJWATCH_DRY_RUN:-false}" == "true" ]]; then
  echo 'Signed APKs and update manifest verified; publication skipped in local dry run.'
else
  gh release create "$TAG" dist/MJWatch-mobile.apk dist/MJWatch-tv.apk dist/update.json dist/SHA256SUMS.txt "$SERVER_SUPPLY" --repo "$GITHUB_REPOSITORY" --target "$GITHUB_SHA" --title "MJWatch $VERSION_NAME" --notes-file "$RUNNER_TEMP/release-notes.md" --prerelease
fi
