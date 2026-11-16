# Issues #14–#18: Android UI regressions

Version: 0.8.6. All fixtures use synthetic titles, local HTTP responses and isolated managed-account IDs. No production server was modified.

- #14: history cards and wide continue-watching cards derive season/episode from the latest saved file path; unnumbered movies retain time-only labels. Unit cases cover S02E03, season directories, Russian filenames and unnumbered films.
- #15: poster cards use NativeCard and the shared positive-inset external TV ring. Focus and press keep the same bounds; verified in dark/light themes.
- #16: defer the remote long-click action until after the release event has reached the card. Request delete-button focus inside the dialog window. The TV test sends actual `input keyevent --longpress 23`, verifies that the dialog survives release without opening the title or deleting history, then deletes with a separate OK press.
- #17: server URL/role and TorrServer/database/stream summaries use themed surfaces, icons and explicit text states. Cards wrap on narrow displays. Unknown health is never presented as healthy.
- #18: catalog loading uses a 28 dp spinner instead of a 240 dp panel. Search and the release sheet show spinners; search busy state includes both concurrent requests. A local MockWebServer delays release responses to verify loading appears and disappears when the request completes.

## Reproducible checks

```sh
./gradlew :core:testDebugUnitTest :app-mobile:lintDebug :app-tv:lintDebug
./gradlew :app-mobile:assembleDebug :app-tv:assembleDebug :app-mobile:assembleRelease :app-tv:assembleRelease
ANDROID_SERIAL=<phone> ./gradlew :app-mobile:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.mjwatch.mobile.NovemberIssuesUiTest
ANDROID_SERIAL=<tv> ./gradlew :app-tv:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.mjwatch.tv.NovemberIssuesUiTest
```

Local runtime: Android 16 / API 36 phone and TV emulators, 3 phone tests and 2 TV tests passed. The pre-existing Android 17 / API 37 emulator cannot run the current Espresso library (`InputManager.getInstance` removed); tests were run on supported API 36 devices instead. A local version-code override preserved a previously installed test APK.

Screenshots: [phone](phone/), [TV](tv/). Poster fixtures intentionally omit remote artwork. Server overview fixtures have no valid session and show unavailable status, not a real server health result. CI runs the complete phone/TV suites before publication.
