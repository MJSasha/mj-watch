#!/bin/sh
set -eu

module=$1
shards=$2
shard=$3
touch "$RUNNER_TEMP/mjwatch-runtime-started"
echo "Emulator ready at $(date -u +%FT%TZ)"

if [ "$module" = core ]; then
    ./gradlew --profile --build-cache :core:connectedDebugAndroidTest :app-mobile:assembleDebug :app-mobile:assembleDebugAndroidTest
    bash scripts/check-offline-emulator.sh
else
    ./gradlew --profile --build-cache ":${module}:connectedDebugAndroidTest" \
        "-Pandroid.testInstrumentationRunnerArguments.numShards=$shards" \
        "-Pandroid.testInstrumentationRunnerArguments.shardIndex=$shard"
fi

echo "Runtime checks passed at $(date -u +%FT%TZ)"
