#!/bin/sh
# Test jobs may use an ephemeral key. Publishing must provide the stable release key.
set -eu
TASK_SUPPLY_TMP=$(mktemp -d)
trap 'rm -rf "$TASK_SUPPLY_TMP"' EXIT
if [ "${1:-}" = "--local-test" ]; then
 openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$TASK_SUPPLY_TMP/key.pem" 2>/dev/null
 MJWATCH_SIGNING_KEY="$TASK_SUPPLY_TMP/key.pem"
 MJWATCH_SUPPLY_CHANNEL=local-test
elif [ -n "${MJWATCH_RELEASE_SIGNING_KEY:-}" ]; then
 printf '%s\n' "$MJWATCH_RELEASE_SIGNING_KEY" > "$TASK_SUPPLY_TMP/key.pem"
 chmod 600 "$TASK_SUPPLY_TMP/key.pem"
 MJWATCH_SIGNING_KEY="$TASK_SUPPLY_TMP/key.pem"
 MJWATCH_SUPPLY_CHANNEL=release
else
 echo 'Stable MJWATCH_RELEASE_SIGNING_KEY is required to publish managed-capable APKs.' >&2
 exit 1
fi
export MJWATCH_SIGNING_KEY MJWATCH_SUPPLY_CHANNEL
TORRSERVER_ARTIFACT_DIR="$TASK_SUPPLY_TMP/artifacts"
export TORRSERVER_ARTIFACT_DIR
mkdir -p "$TORRSERVER_ARTIFACT_DIR"
for arch in amd64 arm64; do
 curl --fail --location --proto '=https' --tlsv1.2 --retry 3 "https://github.com/YouROK/TorrServer/releases/download/MatriX.145.2/TorrServer-linux-$arch" -o "$TORRSERVER_ARTIFACT_DIR/TorrServer-linux-$arch"
done
# build.sh checks both pinned hashes before packaging.
sh "$(dirname "$0")/build.sh"
