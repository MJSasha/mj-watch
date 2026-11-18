# TV focus and carousel release 0.9.8

Ports the unpublished TV focus changes onto current main 274ea73 (0.9.7), preserving all later playback, server, library artwork and mobile fixes.

TV carousels use a stable leading focus position and native row focus restoration. The featured metadata ID survives navigation. TV Material controls reserve 6 dp for external focus borders; custom TV rows draw their outlines inside the viewport. Vertical scroll clearance is converted from 8 dp to physical pixels.

Regression checks: TvCarouselUiTest covers long-row D-pad navigation, detail return and re-entry from the hero. TvButtonsUiTest covers the focus stroke at both ends of a clipped list. Full phone/native TV CI is required before publication. Local emulator startup is blocked by host disk capacity; no local visual acceptance is claimed.

The earlier local 0.8.17 candidate was never published and must not replace the newer production code. CI assigns the release versionCode and uses the existing signing identity. Server supply and decoder recovery remain unchanged.

## Validation before release

Local current-base checks passed: 65 core and 15 playback unit tests, TV debug APK, TV test APK and TV lint. Branch CI build and complete phone runtime passed. Native TV passed the long-row/detail-return test and existing button tests. The new edge test initially failed because it queried an uncomposed LazyColumn item directly; it now scrolls through the list's ScrollToIndex semantics before requesting the last button. Pixel assertions are unchanged. The final main CI must pass all runtime gates before publishing.

## Published and independently verified

[CI #132](https://github.com/MJSasha/mj-watch/actions/runs/44348852251), source `348d2937c579de451d1a4f08a5d7d86c7d640d91`: build, phone runtime, native TV runtime and publish-release all completed successfully.

Downloaded CI HTML reports confirm: phone 50 passed / 34 opt-in skipped; TV 40 passed / 14 opt-in skipped; playback 23 passed / 0 skipped. The new carousel test and all three button tests passed with no skips. Details: [ci-runtime.json](ci-runtime.json).

[0.9.8 / test-132](https://github.com/MJSasha/mj-watch/releases/tag/test-132) is published as stable Latest, versionCode 1132. Public APKs were downloaded independently, not reused from intermediate build artifacts. Package IDs, versions, sizes and SHA-256 match update.json, SHA256SUMS and GitHub digests. apksigner verified the established certificate for both APKs; neither is debuggable. Both APKs contain the new TV carousel/focus code, and debug video fixtures are excluded. Server supply ZIP digest also matches. Details: [release-verification.json](release-verification.json).

The exact `releases/latest/download/update.json` URL used for app updates returns the same 0.9.8/1132 descriptor as test-132. No installation or physical-TV acceptance is claimed; on-device feedback is still needed for the user's specific television.
