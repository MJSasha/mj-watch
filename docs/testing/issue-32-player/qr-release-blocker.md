# QR release blocker after player merge

Main source `d4ba896f31f926e54bf09a88d63c305f25e3dc94`, [CI86](https://github.com/MJSasha/mj-watch/actions/runs/44176134656), did not publish. The mobile job failed `MobileQrLoginTest.qrIsOptionalRefreshesRejectsAndBackRestoresManualLogin`, line53: `Styled QR did not decode at 216 px`. The feature85 and merged86 trees are identical: `e9d7edafd837135a4da8b52c24bb6c92c92d5f30`. The TV job independently failed before tests: Maven Central returned HTTP429 downloading KSP/Kotlin dependencies on both attempts. Neither failure was treated as a passing release gate or blindly retried.

## Fixed reproduction

All reproduction data is synthetic, unbound to any pairing session. Generate32 bytes with `java.util.Random(32086)`; call `nextBytes(key)`196 times and encode `TvQrLink("10.0.2.16",20195,key)`. ZXing Q encoding chooses mask2,57×57 modules including the four-module quiet zone. Render the existing768px bitmap, then Android `Bitmap.createScaledBitmap(...,216,216,true)`.

On Android36, `QRCodeReader` fails before data decoding. Detector result-point callbacks include true upper markers `(28.5,28.5)` and `(187.5,28.5)`, then data-pattern candidates `(164.5,106.0)`, `(62.5,40.0)`, `(108.0,40.0)`. The true lower-left marker `(28.5,187.5)` is not reported by the callback. Removing only the centre logo, or squaring only the finders on the same module grid, produces the same failure. This is not evidence of a branding defect. The missing lower finder and early candidate sequence are consistent with the single-code finder stopping prematurely; callback output alone does not trace every scanned row.

[ZXing3.5.4 FinderPatternFinder](https://github.com/zxing/zxing/blob/zxing-3.5.4/core/src/main/java/com/google/zxing/qrcode/detector/FinderPatternFinder.java) can stop after multiply confirmed candidate patterns; `TRY_HARDER` changes the initial scan stride but retains this exit. The existing JourneyApps QR-only scanner uses that same single-code reader, so changing only the test would leave the scanner defect intact.

## Narrow correction

`ConnectionQrReader` retains a primary `QRCodeReader` and falls back to the already bundled `QRCodeMultiReader` after a `ReaderException`. The fallback searches multiple finder candidates and requires exactly one decoded QR. This single-result requirement applies only to fallback; primary scene selection is unchanged. Both readers are QR-only. Existing hints and result-point callbacks are passed through. No QR artwork, encoder, dependency, protocol, consent, cancellation or secret handling changes.

The existing capture activity installs the shared decoder factory after `super.onCreate` has initialized the scan intent. The original screenshot test uses that shared reader; its original `image.width`,280,216,160 checks and equality assertion remain. The fixed regression exercises the actual JourneyApps decoder factory at768/280/216/160, verifies the old216 failure and the scanner callback, and rejects blank/Code128 images. An ActivityScenario test verifies that the capture activity really installs this factory.

## Validation

- Build/debug instrumentation compilation and `:app-mobile:lintDebug`: passed.
- [Three reader regressions](qr-reader-phone.txt): passed on owned `issue32-phone`, Android36 Google APIs, native1080×1920/420dpi. Includes the old reader's216px failure, new768/280/216/160 result equality, forwarded callbacks, actual capture-activity hook and blank/Code128 rejection.
- [All three original mobile QR login tests](qr-login-phone.txt): passed with `-e mobileQrFixture true`, including actual HTTPS approval and creation of a separate mobile session. Fresh fixture database on host19843/19844; only the owned phone's reverse mappings18644→19844 and19843→19843 were used.
- [Existing camera-denial test](qr-camera-permission-phone.txt): passed with `-e qrCameraDeny true` after revoking CAMERA on the owned phone. It opens the real scanner, denies the OS permission prompt and verifies the Russian explanation and enabled scanner action.
- Earlier local mobile UI run on the TV AVD timed out in Compose at line40, before QR entry/decoder use; it is not counted as passing mobile coverage.
- Branch/main CI remains required. No physical camera, API35 or cloud-green claim is implied by local Android36 checks.
