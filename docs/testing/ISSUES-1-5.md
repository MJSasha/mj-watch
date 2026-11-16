# Issues 1–5: local regression checks

## Behavior

- [#1](https://github.com/MJSasha/mj-watch/issues/1): the MJ path, stroke width,
  caps and joins match the Reader SVG exactly. Only the subject symbol changes
  to a film strip. Both manifests use adaptive launcher/round icons; API 33 adds
  a transparent monochrome layer. The 320×180 banner remains a separate asset.
  A white notification silhouette is supplied; no new notification flow is added.
- [#2](https://github.com/MJSasha/mj-watch/issues/2): releases linked to one
  metadata ID are grouped into a film/series card in history and Continue.
  The card reads account-scoped cached metadata; missing synced artwork can be
  retrieved by the metadata detail endpoint. Opening it shows the film detail,
  with resume and release selection. The latest unfinished linked position is
  used. Unassociated releases remain available without inventing a TMDB match.
- [#3](https://github.com/MJSasha/mj-watch/issues/3): resolution aliases are
  normalized; source titles supply season and voice filters. Season ranges and
  packs are supported. File selection sorts episode numbers numerically and
  keeps the server's original file IDs. A season selected in releases carries
  into file selection; episode searches retain their film and episode context.
  Saved UI state is separate for different episodes of the same release.
- [#4](https://github.com/MJSasha/mj-watch/issues/4): managed accounts use the
  server for TorrServer/TMDB. Those direct configuration rows are hidden;
  server management is shown only for a confirmed admin of the current scope.
  Search settings merge into the active account, including when an old direct
  draft exists. Section saves write only the relevant credentials. Blank legacy
  parser settings and new configs use https://jac.red with API auto detection.
- [#5](https://github.com/MJSasha/mj-watch/issues/5): removed the nonfunctional
  animations setting. Platform animation behavior continues to follow Android.

## Checks

```sh
source .env.local
./gradlew :core:testDebugUnitTest :app-mobile:lintDebug :app-tv:lintDebug \
  :app-mobile:assembleDebug :app-tv:assembleDebug \
  :app-mobile:assembleDebugAndroidTest :app-tv:assembleDebugAndroidTest
```

44 core unit tests cover contracts and regressions, including Cyrillic season
labels, multi-season folders, resolution aliases, voice filtering, history
aggregation, preserving file IDs and account-preserving parser edits.

On the dedicated Android 16 / API 36 arm64 emulator, run the seven tests in:

- `dev.mjwatch.mobile.IssueFixesUiTest` (4 tests)
- `dev.mjwatch.mobile.SettingsUiTest` (2 tests)
- `dev.mjwatch.mobile.MetadataFlowTest` (1 test)

All seven selected mobile UI tests passed. The TV `VerticalFlowTest` also passed
on the same emulator configured as 1920×1080 at 240 dpi: D-pad navigation,
release/file selection and the actual Media3 first-frame callback. This checks
the TV app/layout, not a physical TV or a dedicated Android TV system image.
Both application builds and lint completed with zero lint errors (existing
warnings remain).

The new UI tests exercise the old direct draft on a synthetic managed scope,
member-safe settings, grouped history → film detail → releases, quality/season
filters, episode ordering and adaptive/monochrome icon resources. Existing tests
check independent connection pages, credential preservation, layout and direct
metadata/bookmark behavior. The synthetic managed scope deliberately has no live
server or credentials; this is not a live account or external TMDB/JacRed check.

Icon previews are rendered from the Android foreground vector at 32/48/64/128 px,
with circle/rounded-square masks and light/dark monochrome colors. Generated
screenshots and APKs stay out of Git. No release version or signing identity is
changed. Voice labels are inferred from known studio/type names in release titles;
unknown voice labels are not guessed. Actual audio tracks remain selectable in
Media3 after playback starts.
