# Release filters and episode selection — 2026-11-19

Prepared for release 0.9.16 on top of published 0.9.15. Final published artifact verification is recorded separately after CI.

## Changes

- Release filters use the existing settings card language: distinct icons, grouped choices, live result count, fixed result/reset actions, and short voice/language hints beside their fields. All six filters remain available: quality, season, voice, source, seeds, language. The current preferred quality stays visible even if absent from search results. Active optional filters appear as badges.
- File selection adds numbered episode cards, file names/sizes, completion status and saved progress. Selection is visible and scrolls into view when the footer appears. Samples and non-video files remain available through additional-file controls. Mobile headers scroll with the list; TV uses compact fixed controls and a scrollable playback side panel.
- Managed metadata seasons/episodes use the same card/row/divider style. Loading, error messages, retry and selected season remain visible. Choosing an episode preserves the season and episode numbers sent to release search.
- The phone detail scroll viewport now ends above its fixed bottom action. This prevents a visible episode row from receiving a tap on the overlaid generic release button. The metadata list shares the outer scroll instead of adding a nested scroll area.
- Playback dispatch, server file IDs, queue ordering, automatic resume and account scope handling are retained. No new dependencies or server changes.

## Validation

Built mobile/TV debug and instrumentation APKs. Both final lint tasks passed with zero errors. `git diff --check` passed.

Native phone app, emulator 1080×1920, density 420:

- `ReleaseSelectionUiTest`: 3/3 passed on the final APK. Exercises all six filters together, dialog close/reopen, reset/count, season selection, completed/in-progress files, selected-file resume action, sample/non-video visibility and clean screenshot capture.
- The same three scenarios passed at font scale 1.3, including an unclipped-bounds check that the selected card stays entirely inside the list viewport above the playback button. Light-theme capture also passed; font scale was restored afterward.
- Existing `CatalogSectionsUiTest#groupedFiltersShowCountAndResetEveryChoice` and `IssueFixesUiTest#releaseSeasonAndQualityFiltersAllowPhonePlayback` passed.
- `ManagedEpisodesUiTest`: passed in dark/light themes and at font scale 1.3 against the isolated HTTPS fixture. Exercises failed season loading/retry and selecting season 2, episode 3; checks that the episode stays above the fixed bottom action and the correct numbers/metadata reach release search.
- Existing `SeriesResumeUiTest`, `PhonePlaybackRouteTest`, `SeriesPlaybackUiTest`, and `SettingsUiTest#longRawTitleNeverOverlapsBackOrBottomAction`: 7/7 passed. Includes saved episode/position, resume without local release linkage, separate phone/TV launch routes, numeric queue, Activity recreation, previous/next behavior and long title geometry.

Actual TV app, same emulator at 1920×1080, density 320:

- `ReleaseSelectionTvUiTest`, `SeriesPlaybackUiTest`, `SeriesResumeUiTest`: 5/5 passed after compacting the TV layout. Filter controls, reset, file selection and sample controls were activated by D-pad center with focus assertions. Playback and saved-position regression cases also passed.

Physical TV/OEM rendering, real-provider metadata, TalkBack and font scales above 1.3 were not separately audited. All pictured releases, positions, episode metadata and accounts are synthetic; no production server/accounts were changed.

## Screenshots

- `before/`: baseline phone filters and files.
- `dark/`, `light/`: redesigned phone filters and episode cards.
- `large-text/`: font scale 1.3.
- `managed-dark/`, `managed-light/`: metadata seasons and episodes over the local HTTPS fixture.
- `tv/`: final TV filters, compact controls and selected episode.

## Reproduce the opt-in metadata check

Start `python3 docs/testing/release-selection-2026-11-19/episodes_fixture.py` on the test host. It binds only loopback, generates a temporary self-signed certificate, and serves synthetic metadata. Stop it after the check.

Forward ports to the emulator with `adb reverse tcp:18545 tcp:18545` and `adb reverse tcp:18546 tcp:18546`. Install the mobile debug and instrumentation APKs, then run:

```sh
adb shell am instrument -w -r -e episodesFixture true -e class dev.mjwatch.mobile.ManagedEpisodesUiTest dev.mjwatch.mobile.test/androidx.test.runner.AndroidJUnitRunner
```

For clean UI captures use `-e releaseReview review -e class dev.mjwatch.mobile.ReleaseSelectionUiTest#captureReleaseSelection`; add `-e theme light` for light mode. Both manual checks skip unless explicitly opted in.

## Final review adjustment

The browser demo had no margin between its season action and badge. Its CSS now matches the existing native SettingsCard spacing: measured 16 CSS px in the browser. Native layout already uses 16 dp between these children. A native spacing assertion was added and compiled; that extra assertion could not be re-run locally because the emulator refused to start with the available disk space. The underlying native layout was unchanged after the successful runtime checks above.
