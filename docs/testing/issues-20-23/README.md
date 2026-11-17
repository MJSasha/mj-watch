# 0.8.11: issues #20–23 and mobile QR sign-in

- #20: mobile video double-tap seeks ±10 seconds, bounds positions, ignores non-seekable streams and shows feedback; single tap controls the overlay. Accessible show-controls action remains available and touch exploration keeps controls visible.
- #21: native launch-window MJ logo, followed by an animated branded banner until configuration is ready. Startup does not return during navigation.
- #22: explicit settings exit and confirmation from Home Back, stopping playback/update download and removing the Android task while retaining persisted settings/history. TV defaults focus to Stay.
- #23: APK progress is a dialog outside scrollable settings. Percent, bytes, verification status and focused Cancel remain visible.
- New phone/tablet QR login is optional alongside password/invitation sign-in. An authenticated phone scans the new device QR and approves a separate session. Both devices confirm. Existing TV QR encryption, expiry and account binding are reused; no server upgrade is needed. See [QR flow](../TV-QR.md#mobile-sign-in-0811).

## Local verification (2026-11-17)

- 64 core and 7 playback unit tests: zero failures.
- Mobile/TV lint and debug/release APK builds: passed; local release artifacts are unsigned.
- Eight selected mobile runtime tests: `MobileQrLoginTest`, `AppLifecycleUiTest`, `ControlsUiTest`, all passed. The isolated HTTPS fixture on 18643/18644 verifies a new mobile device/session after pairing approval; the LAN QR peer is exercised directly, without claiming a new optical-camera test.
- `MobileGesturesTest` passed: visible/hidden controls, both directions, duration boundaries and non-seekable media.
- Three TV `AppLifecycleUiTest` scenarios passed with D-pad/OK in a 960×540 dp landscape layout on the local Android 36 phone-image emulator. Full Android TV image regressions run in the release CI.
- Compose screenshots checked for launch branding, progress and exit. A focus request waits for the dialog window to gain focus, avoiding requests made against the underlying Activity.

Publication uses the existing main-branch pipeline: server checks, Android unit/lint/build, phone and Android TV runtime suites, then signature verification and Latest assets. Existing APK/server signatures and updater formats are preserved.
