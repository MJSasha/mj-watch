#!/usr/bin/env bash
set -euo pipefail
: "${GITHUB_RUN_NUMBER:?}" "${GITHUB_RUN_ID:?}" "${GITHUB_SHA:?}" "${RUNNER_TEMP:?}" "${ANDROID_HOME:?}"
version_code=$((1000 + GITHUB_RUN_NUMBER))
version_name=$(sed -n 's/^mjwatchVersionName=//p' gradle.properties)
tag="test-$GITHUB_RUN_NUMBER"
signing_dir=$(mktemp -d "$RUNNER_TEMP/mjwatch-signing.XXXXXX")
trap 'rm -rf "$signing_dir"' EXIT
export SIGNING_STORE="$signing_dir/apk.keystore"
if [[ "${1:-}" == --local-test ]]; then
  channel=local-test
  keytool -genkeypair -keystore "$SIGNING_STORE" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 1 -dname 'CN=MJWatch CI dry run' >/dev/null 2>&1
  expected_cert=$(keytool -exportcert -keystore "$SIGNING_STORE" -storepass android -alias androiddebugkey | shasum -a 256 | cut -d' ' -f1)
else
  [[ -z "${1:-}" && "${GITHUB_REF:-}" == refs/heads/main ]]
  : "${MJWATCH_TEST_KEYSTORE_B64:?APK release signing key is required}"
  channel=release
  expected_cert=$(cat scripts/test-signing-cert.sha256)
  python3 - <<'PY'
import base64, os
from pathlib import Path
p = Path(os.environ['SIGNING_STORE'])
p.touch(mode=0o600, exist_ok=False)
p.write_bytes(base64.b64decode(os.environ['MJWATCH_TEST_KEYSTORE_B64'], validate=True))
PY
fi
unset MJWATCH_TEST_KEYSTORE_B64
./gradlew "-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8" :app-mobile:assembleRelease :app-tv:assembleRelease --build-cache --no-daemon "-PmjwatchVersionCode=$version_code" "-PmjwatchVersionName=$version_name"
out=dist/release
mkdir -p "$out"
tools="$ANDROID_HOME/build-tools/36.0.0"
for platform in mobile tv; do
  apk="$out/MJWatch-$platform.apk"
  "$tools/apksigner" sign --ks "$SIGNING_STORE" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$apk" "app-$platform/build/outputs/apk/release/app-$platform-release-unsigned.apk"
  cert=$("$tools/apksigner" verify --verbose --print-certs "$apk")
  grep -Fx "Signer #1 certificate SHA-256 digest: $expected_cert" <<< "$cert"
  badging=$("$tools/aapt" dump badging "$apk")
  grep -F "package: name='dev.mjwatch.$platform' versionCode='$version_code' versionName='$version_name'" <<< "$badging"
done
rm -f "$out/MJWatch-mobile.apk.idsig" "$out/MJWatch-tv.apk.idsig"
python3 scripts/build_update_manifest.py --dist "$out" --tag "$tag" --version-code "$version_code" --version-name "$version_name"
python3 scripts/verify_release_artifact.py "$out" "$channel" --prepare
