# Series resume — 0.8.7

## Reproduced failure

History/progress synchronizes through the managed service; encrypted release links stay on the device. `WatchModel.resume` required the local link and rejected synced progress when it was missing. Detail did not render the error, making Continue appear inert. The new missing-local-link test timed out before this fix.

## Fix

Prefer the encrypted local release. Otherwise reconstruct a magnet from the saved content identity, validating the 40-character info hash, complete normalized file path and nonnegative size against `contentKey`. Preserve the original release ID, choose the exact saved file and let the player read its saved position. No private release URLs are added to sync payloads. Missing torrent metadata or unavailable peers can still prevent playback and produce a visible recovery message.

Continue shows loading in its button. Errors use the detail screen's snackbar; unrecoverable legacy identities retain the explicit choice of another release and do not pretend to resume.

## Checks

- Unit checks: valid content identity, mismatched path, malformed legacy key and negative size.
- `SeriesResumeUiTest` in both app modules: second episode and saved position with an existing local link; the same with no local link; visible recovery message for a legacy record with neither a usable link nor a recoverable identity.
- Phone uses touch; TV uses D-pad/OK. All six local runtime checks passed on API 36.
- Fixtures seed Room and encrypted storage, replace only the release/gateway network boundary and play the bundled local MP4 through the real player. They do not verify external torrent availability or live managed-server networking.

```sh
./gradlew :core:testDebugUnitTest :app-mobile:lintDebug :app-tv:lintDebug
ANDROID_SERIAL=<phone> ./gradlew :app-mobile:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.mjwatch.mobile.SeriesResumeUiTest
ANDROID_SERIAL=<tv> ./gradlew :app-tv:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.mjwatch.tv.SeriesResumeUiTest
```
