# Issue #26: implementation and verification

17 November 2026. This is an implementation branch, **not an accepted Cast release**.

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

The dedicated `.github/workflows/phone-tv.yml` boots one phone and two independent Android TV API 36 emulators. `scripts/check-phone-tv.py --video <MP4 longer than 3 minutes>` verifies AVD names before resetting test APKs, creates an isolated HTTPS fixture, installs actual APKs, and executes `PhoneTvTest`. It checks receiver frames, system transport/remote volume, TV key events, background/lock, a broken phone transport, reconnect, same-file attach, replacement cancellation, independent targets, bookmarks and server-confirmed rename. Test credentials/CA keys are disposable and excluded from uploaded evidence.

## Local results

- 65 core JVM tests and 12 playback JVM tests passed, including Unicode device aliases and expiration of the launch gate.
- Go `test -race ./...` and `vet ./...` passed. Protocol tests cover two targets, a second controller, foreign-account isolation, rename ACL, expired receiver state, stale confirmations/restarts and revoked queued commands.
- Both APKs and instrumentation APKs compile; mobile/TV lint and unsigned release builds pass.
- Android runtime, visual comparison and the new three-emulator integration have **not run**. QEMU cannot boot inside this sandbox. A GitHub tree upload was rejected by automatic approval review; no branch/PR/CI run was published without explicit authorization.
- Packaging syntax and the seven installation-status tests pass. The existing TLS fixture failure is described below and remains separate from the new protocol checks.

## Acceptance tracking

“Code covered” below is not a claim that the Android runtime or visual check passed. Runtime evidence must be linked after the emulator run. [Original matrix](../../../design/phone-tv-v1/ACCEPTANCE.md).

| IDs | Current evidence / limitation |
|---|---|
| A01, A11–A13 | Server ACL/Unicode tests; shared list and rename dialog implemented; Android screenshots pending |
| A02–A06, A24–A26 | All public model/file launch gates updated; dedicated phone UI tests prepared; runtime pending |
| A07–A10 | Receiver-truth protocol, same-file join, CAS replacement, two-target routing covered by Go tests; actual frames/system controls pending |
| A14 | Existing QR/scanner code and entry points untouched; regression runtime pending |
| A15–A17 | Native remote MediaSession/VolumeProvider and foreground sender implemented; actual system UI/runtime pending |
| A18 | Explicit second-controller join tested at API level; automatic LAN/Cast notification is not implemented |
| A19 | Target/session invalidation, stale receiver fencing, revoked queued-command rejection covered by Go tests; Android loss/retry test prepared |
| A20 | Unsupported/offline receivers cannot become targets; Cast/OEM and disabled-notification behavior remain unverified |
| A21 | No in-app sender transport added; existing local player retained for standalone TV and low-level player tests |
| A22 | 48dp actions, wrapping labels and modal rename implemented; TalkBack, 200% font, tablet and D-pad runtime/visual comparison pending |
| A23 | TV launch guard stays independent of remote selection; TV runtime pending |

Local sandbox denies CPU capability access required by QEMU, so it cannot supply Android screenshots or first-frame evidence. Local compilation/unit tests do not establish visual parity with the three reference sheets.

The existing `server/packaging/test_health_tls.py` has an environment-dependent baseline failure: Apple Python 3.9 does not reject the legacy CA as the test expects; Homebrew Python 3.12 rejects it first for a missing authority key identifier rather than the expected key-usage wording. Packaging/TLS implementation was not changed for this issue.

The prior mobile local-playback UI scenarios target the previous product behavior. They need conversion to the connected-target fixture before the complete legacy instrumentation suite can be considered an acceptance suite for #26. The dedicated three-emulator test does not turn the remaining manual/compatibility scenarios into passes.
