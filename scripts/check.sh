#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
./gradlew :core:testDebugUnitTest :app-mobile:lintDebug :app-tv:lintDebug :app-mobile:assembleDebug :app-tv:assembleDebug :app-mobile:assembleRelease :app-tv:assembleRelease "$@"
