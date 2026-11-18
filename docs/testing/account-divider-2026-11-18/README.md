# Account settings divider — 2026-11-18

Published [0.9.15 / build 1141](https://github.com/MJSasha/mj-watch/releases/tag/test-141), marked Latest.

The account and server links now share one settings group with the standard divider between them. The server link and divider are shown only to administrators. Existing destinations and scope checks are unchanged. This also removes the separate 16 dp gap between these rows.

Local validation: shared design-system Kotlin compilation and `git diff --check` passed. No new instrumentation tests were added for this visual adjustment.

[Release CI](https://github.com/MJSasha/mj-watch/actions/runs/44410455932): server race/vet, Android unit/lint/debug/release builds and phone/TV runtime checks passed.

Downloaded both APKs, update manifest, checksums and server supply after publication. Verified package names, version 0.9.15 / 1141, non-debuggable APKs, matching hashes, previous APK signing certificate, signed supply contents and unchanged server signing key. [Verification report](release-verification.json).

Physical TV/OEM visual behavior was not separately checked for this small change.
