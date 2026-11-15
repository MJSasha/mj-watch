# 0.5.1 — installer progress

Reference: panel 04/05 of `design/server-management-v1/01-connection-and-install-v2.png` (provided design). Native Compose; no WebView, new dependencies or user credentials.

Implemented: five real server stages, completed/current/pending indicators, failed/cancelled status with preserved stage, separate lost-connection status, sanitized protocol details and copy, query/resume, retry installation, return to SSH fields, keyboard dismissal and scroll-to-heading. Readiness screen uses verified health and owner bootstrap, not a timer. Legacy `stage=failed` is displayed without inventing completed steps.

The original generic HTTP message was caused by `require(installed)` being passed through `safeError(IllegalArgumentException)`. Terminal server responses now retain their code and receive installer-specific explanations. The Python error handler previously overwrote the actual stage with `failed`; it now retains the stage, preserves cancellation and exposes only an allowlisted failed command name, never arguments/stderr. Actual cause on the user's server is still unknown.

## Verification

- `:core:testDebugUnitTest`: 36 passed, including three new installer tests (stage/legacy state, sanitization and SSH validation).
- `sh server/check.sh`: Go race tests, vet, Python syntax and three new status-handler checks passed. Runs against local fixtures, not a user server.
- `:app-mobile:lintDebug :app-tv:lintDebug`: passed.
- Both debug APKs and mobile instrumentation APK built with JDK21/SDK36. Local supply regenerated and signed with a disposable local-only key; CI supplies the stable repository key for publication.
- Android API36, dedicated MJWatch_Phone_36/5582: `InstallationUiTest` passed. It deliberately fails host validation before any network operation, checks the five rows, specific error, hidden form, sanitized details and editable return. [Screenshot](failed-stages.png).
- The same Android UI test passed with system fontScale=2.0; labels/details/retry remain reachable by scrolling. Font scale restored to 1.0.
- Initial test timed out because it expected settings from an unconfigured account; it now waits for the actual connection-choice route. An initial screenshot contained a pre-existing legacy-fixture notification; the test now dismisses that unrelated model state before navigation, and the clean run passed.

No new real-server installation, SSH session to the supplied IP, dedicated TV installer-stage UI scenario, or actual host-key interruption test was performed for this change. Earlier server runtime acceptance is recorded separately in SERVER-MANAGEMENT-V1.md. Do not treat these fixture checks as proof that the user's server installation is fixed.

## Publication

[CI 20](https://github.com/MJSasha/mj-watch/actions/runs/43763064500) completed successfully: server/build/unit/lint, phone API35 runtime (including new installer test), TV API36 runtime, and publish. [0.5.1-test.20](https://github.com/MJSasha/mj-watch/releases/tag/test-20) is available through the existing testing updater. Both downloaded APKs have their correct application IDs, versionCode 1020, versionName 0.5.1-test.20, matching SHA256SUMS/update.json and the existing signing certificate. [Verification](published-apks.txt). User-server root cause remains unconfirmed until its real status code is obtained.
