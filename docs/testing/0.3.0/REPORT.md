# GitHub publication and updater — 2026-11-15

Application source: `98404875e8ef3ba8bb35c6833452f9ad3add340a`. Local baseline 0.3.0/code4; first published release [0.3.0-test.12/code1012](https://github.com/MJSasha/mj-watch/releases/tag/test-12).

## Publication

[Workflow #12](https://github.com/MJSasha/mj-watch/actions/runs/43729640745): build, phone API35, TV API36 and publish-testing all succeeded. Publish was retried once because the initial workflow had been queued before the approved signing secret was created. Existing successful build/runtime jobs were retained. Secret is stored in repository Actions Secrets, not source; temporary transfer file removed and clipboard replaced.

Both published APKs were downloaded independently and checked against update.json: exact size, SHA256, package IDs, versionCode1012, and signing certificate matching the locally supplied 0.2.1 APK. No production key/signing claims. Published release APKs are not debuggable. [Published descriptor](published-test-12.json).

## Unit and contract checks

21 existing contracts + 6 updater tests = 27 tests, zero failures. [Contracts](logs/contracts.xml), [updater suite](logs/updates.xml). Updater cases: platform/channel/repository/version binding; tampered/truncated bytes; allowed CDN redirect and absence of server credentials/cookies; downgrade/other-origin/userinfo redirects blocked before request; streamed size/hash; cancellation closes the body and stops writing. Final cancellation test was added after the first release and is included in the next source commit.

## Android runtime

Own AOSP API36 ARM64 phone AVD. No user TV/projector was controlled.

1. Existing app 0.2.1/code3 upgraded in place to local 0.3.0/code4 with the same key. Room history/progress and encrypted sentinel retained.
2. Actual signed higher-version candidate accepted; actual APK signed with a different key rejected. Wrong package/hash rejected. FileProvider non-exported, configured only for cache/updates; attempt to share a private files-dir path rejected. [Log](logs/candidate-verification.txt). Same APK parsing verified for temporary `.part` filename: [log](logs/part-verification.txt).
3. Through app settings, checked public GitHub release test-12, downloaded the real published APK, verified bytes/package/version/signature, and opened Android's per-app unknown-source permission settings. [Test](logs/published-permission.txt).
4. With permission enabled on the test AVD, repeated check/download through app UI and opened the real native installer. [Test](logs/published-installer.txt), [screenshot](native-installer.png).
5. Confirmed UPDATE in native installer on the test AVD. PackageManager then reported versionCode1012/versionName0.3.0-test.12 and flags without DEBUGGABLE. No uninstall/data clear.
6. After native installation, instrumented verification again decrypted the old sentinel and found the original history and nonzero progress: [test](logs/after-native-install.txt). [Safe diagnostic proof](logs/runtime-proof.txt).

The test installer permission was changed only on our disposable AVD. Actual user devices retain Android's normal permission and confirmation flow. TV updater shares data/download/verification code and has focusable TV buttons; CI covers the TV shell. Physical-TV install UI and API26 updater matrix have not been manually confirmed.

## Operation

First installation of the updater-containing release is manual over 0.2.1. Thereafter startup/foreground checks are automatic with a 15-minute throttle; manual check bypasses it. Downloads and system installation require user actions; silent install is not implemented. Main pushes publish only after all checks succeed. See [release workflow](../../UPDATES.md).
