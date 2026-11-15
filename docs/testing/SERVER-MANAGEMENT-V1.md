# Managed server v1 — implementation and acceptance evidence

Date: 2026-11-15. Base `5edc318`, isolated branch `codex/server-management-v1`. Main checkout and its untracked design originals were preserved. This report distinguishes implementation from runtime coverage; it is not a production-readiness certificate.

Publication follow-up, 2026-11-15: [0.5.0-test.19 (1019)](https://github.com/MJSasha/mj-watch/releases/tag/test-19), source `0a82997`, is marked Latest. [CI 19](https://github.com/MJSasha/mj-watch/actions/runs/43760217990) passed build, phone API35, TV API36 and publication. Downloaded APKs were verified for package/version/hash, non-debuggable release configuration and the established Android certificate. The downloaded server 0.1.0 ZIP and both embedded supplies share the same verified manifest/signing identity; every payload hash was checked. The earlier local server key is now retained as the stable testing-channel key in the protected `MJWATCH_RELEASE_SIGNING_KEY` Actions Secret. The implementation-stage notes below describe the earlier pre-publication state; live-platform/provider/accessibility limits remain. No deployment to a user server or separate production signing identity was introduced.

## Delivered behavior

* Direct and managed are independent modes. Direct preserves ready TorrServer/Basic Auth, Jackett/JacRed, personal optional TMDB, demo and favorites, while openings/progress/completion do not write disk or sync queues. Legacy history is preserved with explicit keep/delete and separate managed import.
* Managed Go service with SQLite WAL, API/schema versioning, owner bootstrap, password/invite login, TV pairing, devices/revocation, user isolation, revision/cursor/idempotent synchronization, conflicts, tombstones/history epochs and post-restore resnapshot.
* Managed media is an authenticated fixed-operation TorrServer gateway with ownership, actual file IDs, Range/seek and bounded memory. Metadata and images use the server, never the shared credential on clients.
* Root SSH plug-and-play installs both components; detached systemd installation and operation jobs expose real state/receipts. Strict host-key trust, private CA/hostname checks, separate service users, loopback TorrServer, autostart/firewall/leaf renewal. TorrServer settings travel through fixed root SSH commands, drafts/revisions/validation/reread. TMDB settings/key validation/rotation/deletion/cache travel through HTTPS admin API.
* Server SemVer is independent of APK. Signed ZIP import is verified against the APK trust key; root verifies with the installed trust key. Update creates encrypted online backup, switches a version symlink, checks both components, and verifies rollback on failure. Unknown schemas are rejected.
* Online encrypted backup, clean-server restore, session/invitation invalidation and new sync epoch. Retention/scheduled encrypted backups. Native account/admin/settings screens on phone/tablet/TV with existing navigation and settings retained.

## Existing function preservation

| Function | Location / handling |
|---|---|
| Jackett/JacRed, API/indexers/credentials/check | Existing search settings and repositories retained; direct HTTP adapter regression tests |
| Direct TorrServer URL/proxy-prefix/TLS/local HTTP/Basic Auth | Existing native settings and guarded transport retained; bare host normalization added |
| Optional direct TMDB and attribution | Existing catalog settings/attribution retained; managed uses server API + authorized images |
| Catalog/details/series | Existing home/details retained; managed seasons/episodes added; torrent selection remains explicit |
| Quality/audio/subtitles | Existing settings/player controls retained; scoped profile preferences and sync |
| Theme/motion | Existing settings and native theme retained |
| APK updates/about/demo/import/export | Existing sections retained; server update is an additional maintenance operation; export excludes account trust/secrets |
| Mobile navigation/TV rail/tablet panes | Existing shells retained; server screens are nested additions |
| History/favorites | Deliberate requirement change: history managed-only; favorites separate; no silent legacy deletion/upload |

## Verified locally

Build commands and details are in [`../../server/README.md`](../../server/README.md). Logs/screenshots are copied into the local delivery folder alongside APKs; generated binaries are excluded from Git.

| Check | Evidence |
|---|---|
| Baseline unit/lint | Passed before implementation in isolated checkout |
| Go service/control tests + race detector + vet | Passed; auth/isolation/invites/last owner, synchronization conflicts/epochs/dedup, paging/retention, provider fixtures, encrypted online backup/restore, settings revision/unknown-field preservation |
| Stream lifecycle | Real HTTP streaming test survives token rotation and is cut off after device revocation; finite test, not a multi-hour throughput benchmark |
| Both Android variants | 33 core unit tests, lint, debug/test APK and unsigned release builds passed |
| Managed phone + TV fixture | Real Go HTTPS/SQLite + controlled MP4, Media3 first-frame/seek, server progress, native settings/account/admin pages |
| Preserved phone regressions | Managed/direct, two native settings tests, cached metadata/favorites, JacRed HTTP, cancellation/secrets and demo vertical flow passed (9 test results; PiP was assumption-skipped in this environment) |
| Tablet / large font | Tablet width split-pane search/detail + managed smoke passed; phone managed smoke passed at fontScale 200%; labels moved above fields after visual inspection |
| Direct privacy | Real Media3 first frame with no new persistent opening/progress/release URL; hidden metadata timestamp checked |
| Debian 12 arm64 installation | Dedicated VZ VM, no host mounts. Native phone one-button SSH flow installed real pinned TorrServer + service and automatically connected owner via HTTPS; initial permissions failure was fixed and resume passed |
| Actual installed TorrServer | SSH settings apply/read/restart persistence; controlled local libtorrent seeder + `.torrent` upload, Media3 first frame/seek/progress through Go HTTPS gateway |
| Clean second-server restore | Running SQLite Backup API → encrypted archive → second Debian 12 arm64 VM: database/TorrServer healthy, epoch changed, old devices invalidated, progress preserved |
| Android imported update | Dedicated phone instrumentation imported the signed ZIP with `ServerBundles`, uploaded it through `OwnerSsh`, installed 0.1.1 and verified admin/status without replacing the APK or changing account scope |
| Signed update + failed update | 0.1.0→0.1.1 passed. Intentionally broken but validly signed 0.1.2 rolled back to 0.1.1; health, epoch and progress verified |
| Autostart | Second VM rebooted; MJWatch, TorrServer and SSH active; installed version still 0.1.1 |
| Reproducible supply | Repeated build with same source/key produced matching binary/archive SHA256SUMS |

## Limits and negative evidence

* Full Debian/Ubuntu × amd64/arm64 live matrix is **not** verified: Linux binaries cross-built for both architectures; actual installation/restore/update evidence is Debian 12 arm64. Ubuntu 24.04 and amd64 runtime remain acceptance work.
* No real TMDB key or user SSH target was supplied. TMDB token/v3 validation/rotation/errors/rate limits/cache/image credential isolation use controlled HTTP fixtures; live provider behavior is unverified. No real user server was contacted.
* Controlled magnet metadata discovery timed out with the isolated peer fixture; actual installed-server playback proof uses `.torrent` upload. Do not treat that result as proof of successful magnet discovery on real networks.
* TalkBack, all physical remotes, every focus-return/IME/large-font/resize state, long network partitions, multi-hour streams, codec/HDR matrix, disk-exhaustion and all installer interruption boundaries are not fully covered. Screenshots are visual smoke evidence only.
* Future incompatible SQLite schemas deliberately require new migration support; current binary rollback supports schema 1 and does not promise arbitrary down-migrations.
* There is no configured online release feed for the server. Future updates are imported signed ZIPs from native maintenance. Release signing infrastructure/publication is not provisioned. Local supply uses a temporary test signing identity. CI test jobs generate ephemeral identities; publishing requires the stable `MJWATCH_RELEASE_SIGNING_KEY` secret and will fail without it.
* Initial verification accidentally installed debug/test APKs and attempted four tests on the pre-existing `Medium_Phone_API_37.0` AVD before its identity was checked. Espresso failed against API 37 InputManager. This was disclosed during work. No later action was taken on that AVD; all subsequent mutations verify the dedicated AVD name first. Those failed tests are excluded from acceptance evidence.
* The local seeder initially used a hybrid v1/v2 torrent; its outgoing peer connection selected a different hash than TorrServer v1. Final verification uses an explicit v1-only seeder and matching host fixture. Early failures are retained in logs: installer directory permissions, update directory permissions (rollback worked), old-handle replay after TorrServer restart, and asynchronous test navigation, and a cached-snapshot progress assertion (the final fixture resets history, waits for outbox acknowledgement and requests a fresh snapshot). Corrected runs are separately named; failures are not relabeled as successes.

No deployment/publication or production signing was performed. Private SSH credentials, CA keys, release private key, restore passphrase and backup archives remain outside source/delivery. Test-only fixture control is not shipped in the APK/server supply.
