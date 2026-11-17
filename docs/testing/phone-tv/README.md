# Issue #26: implementation and verification

17 November 2026. Candidate **0.9.0**, server **0.1.5**. This is an implementation branch, **not a published or accepted full #26/Cast release**.

## Implemented path

Managed MJWatch HTTPS relay → native TV APK → existing Media3 player. The phone publishes an Android remote `MediaSession` from a foreground service. It never creates a local player as a fallback. The receiver resolves an account-owned playback handle with its own credentials; controllers cannot supply arbitrary stream URLs or credentials. The system renders notification transport and remote volume. TV volume uses the receiver's `AudioManager` music stream; fixed-volume outputs advertise no volume control.

This is a custom managed protocol, not Google Cast/Cast Connect. It does not automatically create Cast notifications on other phones in the LAN. A second authorized controller can explicitly join the receiver. Registered Cast IDs and real-device Cast compatibility remain unverified.

Server **0.1.5** is required. Additive device-profile/receiver/receipt tables preserve existing IDs and pairing. Existing device entries default to unknown type until the APK registers its type. Neither pairing nor camera/QR flows were replaced. Receiver availability requires the TV app in the foreground; this version does not wake or launch a stopped/background TV app. A phone disconnect never sends stop. TV process restart fences stale controllers with a new instance and requires reconnection.

Direct has no authenticated device registry/channel in the existing pairing protocol. Remote playback in direct is explicitly unavailable in the chooser; independent TV playback remains available. Do not claim direct acceptance or extend QR trust implicitly.

## UI and safety

- One account device list, type icons, stable short IDs, and ⋮ → compact rename dialog; names use 1–32 trimmed Unicode code points, control characters rejected. Own-account aliases are saved on the server without administrator privileges. Offline writes remain errors in the dialog.
- Independent cast/bookmark icons in the film header. No extra TV row or bottom connect button. Phone/tablet launch/resume/file entry points require an acknowledged live target; standalone TV bypasses this controller-only gate.
- Choosing a target does not load media. Changing/loss of the route cancels a pending file preparation so it cannot launch later on a different TV. Loads carry target instance and expected receiver session; same-file joins do not restart. Replacement requires an explicit confirmation; stale confirmations are checked again on server and receiver.
- No in-app remote transport or volume slider. Receiver status is authoritative; an accepted load is not a first-frame acknowledgment. Loss of the relay deactivates phone controls and shows inline retry, preserving the film and receiver target.

## Reproducible checks

Local commands (JDK 21, Android SDK 36):

```sh
./gradlew :core:testDebugUnitTest :playback:testDebugUnitTest :app-mobile:lintDebug :app-tv:lintDebug :app-mobile:assembleDebug :app-tv:assembleDebug :app-mobile:assembleDebugAndroidTest :app-tv:assembleDebugAndroidTest
(cd server && go test -race ./... && go vet ./...)
```

The dedicated `.github/workflows/phone-tv.yml` boots two phones and two independent Android TV API 36 emulators. `scripts/check-phone-tv.py --video <MP4 longer than 3 minutes>` verifies AVD names before resetting test APKs, creates an isolated HTTPS fixture, installs actual APKs, and executes `PhoneTvTest`. It checks receiver frames, system transport/remote volume, TV key events, background/lock, a broken phone transport, reconnect, same-file attach, replacement cancellation and confirmation, independent targets, bookmarks and server-confirmed rename. It also verifies 48dp header actions, actual phone volume keys while paused, TalkBack chooser focus, 200% fonts, tablet rename, and TV D-pad/Back. Test credentials/CA keys are disposable and excluded from uploaded evidence.

## Local results

- 65 core JVM tests and 12 playback JVM tests passed, including Unicode device aliases and expiration of the launch gate.
- Go `test -race ./...` and `vet ./...` passed. Protocol tests cover two targets, a second controller, foreign-account isolation, rename ACL, expired receiver state, stale confirmations/restarts and revoked queued commands.
- Both APKs and instrumentation APKs compile; mobile/TV lint and unsigned release builds pass.
- The isolated four-emulator integration passed locally on Android API 36: receiver first frame, Android play/pause/seek/remote volume, physical-TV key events, second Android sender joining without restart, background/lock, broken sender sockets/reconnect, independent TVs and supplied saved position. Unicode rename also passed at 200% font scale and tablet size.
- Reference-sized screenshots were captured and inspected from the successful four-device run `20261117-175633` at 1080×2340 / 420dpi (about 412×892dp). The header actions, connected launch, playback status, selector, replacement, inline loss and compact rename follow the text specification. Data and typography are native, not pixel copies of the illustrative image; absent history correctly omits the saved-position bar. System media and volume screenshots come from Android itself.
- [PR #31](https://github.com/MJSasha/mj-watch/pull/31) contains the implementation. The complete Android workflow passed on revision `0f01678` ([run](https://github.com/MJSasha/mj-watch/actions/runs/44124049373)), including the converted mobile regressions. The native-focus/volume changes subsequently passed the standard Android build/server and runtime jobs on `6b801ab`. The complete four-device integration passed locally; its cloud workflow remains available manually.
- Packaging syntax and the seven installation-status tests pass. The existing TLS fixture failure is described below and remains separate from the new protocol checks.

## Acceptance tracking

“Code covered” below is not a claim that the Android runtime or visual check passed. The successful local evidence is `dist/phone-tv/20261117-175633` (generated/ignored; CI uploads only logs, screenshots and system dumps). The four devices run Android API 36; phone viewport is about 412×892dp, tablet 1600×2560 / 240dpi, and fontScale 2.0. TalkBack is the official preinstalled service, version 16.0.0.738667889. A passing managed scenario does not imply direct/Cast compatibility. [Original matrix](../../../design/phone-tv-v1/ACCEPTANCE.md).

| IDs | Current evidence / limitation |
|---|---|
| A01, A11–A13 | Server ACL/Unicode tests; shared list and server-confirmed rename passed on phone, 200% fonts and tablet; actual TV D-pad rename and Back passed. Foreign/revoked devices covered by API tests. Active-TV rename and offline-save UI remain unverified at runtime. |
| A02–A06, A24–A26 | Phone launch gates, independent bookmark, 48dp targets/8dp gap, selector Back, connection and launch passed. All existing public model entry points guard the route; converted default regressions passed CI on `0f01678`. Final head CI pending. Offline/unsupported chooser rows have code/API coverage, not a dedicated screenshot test. |
| A07–A10 | Actual first frame, same-file join, replacement cancellation **and confirmation**, selection without launching, supplied resume position and independent TVs passed. Go tests cover stale confirmations and target/instance races. |
| A14 | Existing QR/scanner sources and entry points unchanged; their default regressions passed in the prior complete CI. |
| A15–A17 | Android MediaController play/pause/seek, absolute volume and **phone hardware volume keys while paused** passed against actual receiver state. TV media keys synchronize back to the phone; background/lock preserve playback. Foreground Activity uses Android `setMediaController` for native key routing. OS decides background key routing/lock-screen placement; OEMs remain untested. |
| A18 | A second real Android emulator explicitly joined and controlled the same TV session without restart. Automatic LAN/Cast notification and joining a TV launched independently from its catalogue remain unverified/unimplemented as applicable. |
| A19 | Broken phone sockets closed controls/launch, showed inline retry, and preserved TV playback; retry read the same live session. API tests cover revocation, stale receiver/process instances and expired commands. Android force-stop/TV reboot are not claimed as tested. |
| A20 | Unsupported/offline receivers are excluded from active targets. Notification-denial, Cast/OEM compatibility and isolated-Wi-Fi behavior remain unverified. MediaStyle session notifications use Android's documented notification-permission exemption; no custom bypass exists. |
| A21 | No in-app sender transport added. Native notification/volume screenshots saved separately; standalone TV/shared low-level player retained. |
| A22 | Large-font and tablet rename screenshots inspected: field/count/actions fit above IME. TV D-pad/Back and actual TalkBack focus after chooser Back passed. Full TalkBack narration and rename-dialog focus were not exhaustively audited. |
| A23 | Independent TV player and physical media keys retained; TV/default player regression CI passed on the prior revision. |


QEMU was started outside the sandbox after the user authorized emulator verification. Actual APKs and native Android media sessions supplied the runtime evidence; JVM tests alone are not treated as visual or LAN/Cast proof.

The existing `server/packaging/test_health_tls.py` has an environment-dependent baseline failure: Apple Python 3.9 does not reject the legacy CA as the test expects; Homebrew Python 3.12 rejects it first for a missing authority key identifier rather than the expected key-usage wording. Packaging/TLS implementation was not changed for this issue.

The prior mobile local-playback UI entry assertions have been converted to the connected-target contract. Shared low-level player coverage remains, and the connected four-device fixture covers remote playback. The dedicated fixture does not turn remaining manual/compatibility scenarios into passes.

## Release gate

The user approved a **managed-only 0.9.0 release**; direct remote playback is out of this release's scope. The existing scanner/pairing remains unchanged. Do not mark all original #26/Cast scenarios accepted. Use the existing stable signing/main publishing pipeline after the standard Android CI passes and the documented local four-device check; do not sign production artifacts with the disposable local fixture key.

The Android build, server checks, mobile API 35 runtime and TV API 36/player runtime all passed on `1c66118` ([run](https://github.com/MJSasha/mj-watch/actions/runs/44132045072)). The dedicated four-device job failed before tests because avdmanager and emulator resolved different AVD homes. Their user/AVD paths are now explicit and checked before boot. The same four-device suite passed locally. The later cloud run booted successfully and encountered the system ANRs documented below.

### Cloud four-device runner

The standard build/server, mobile API 35 and TV API 36/player suites passed on `6b801ab`. The cloud four-device job booted and authorized all devices, then failed because Pixel Launcher / Android System UI displayed their own ANR dialogs over MJWatch ([push run](https://github.com/MJSasha/mj-watch/actions/runs/44163476391), [PR run](https://github.com/MJSasha/mj-watch/actions/runs/44163489057)). Screenshots identify these system processes; this is not represented as a successful cloud integration.

The four-device workflow remains `workflow_dispatch`/reusable for a suitable host, with reduced guest rendering load and explicit ANR diagnostics. It is separate from automatic publication. Release validation uses the complete successful local API 36 four-device run `20261117-175633`, plus the required standard CI. The application code did not change after that local run; subsequent changes concern CI/resources, documentation and diagnostic test assertions.
