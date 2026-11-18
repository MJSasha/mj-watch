# Server resilience and buffers — 2026-11-18

Scope: requested items 1, 3, 4 and 5. Playback retry budgets/delays and system memory limits were excluded. Initial implementation targeted server 0.1.5 on the old local branch. Publication rebases the changes onto 0.9.9/server 0.1.6 and releases client 0.9.10/server 0.1.7, preserving device cleanup, remote control and decoder fallback.

Implementation/commands: [server README](../../../server/README.md#resource-profiles-and-diagnostics-017).

Added automated coverage:

- Host/container RAM, neighbour consumption, zero/unknown headroom and buffer budgets for normal/weak/low-memory clients.
- Cache increases and disk-to-RAM switches blocked under pressure; cache reductions/disk mode remain available.
- Viewing admission, bounded Range overlap/release, overload rejection before upstream work and credential redaction.
- Presence writes verified through a SQLite trigger; indexed sync query plan; expiry preserving replay/change records.
- Logging migration rollback/retry and no repeated restart after completion; failed-sync delay cap/reset.

Initial implementation validation (before rebase):

- `PATH=/opt/homebrew/opt/python@3.12/libexec/bin:$PATH sh server/check.sh` — passed: Go race tests, vet, 7 installation tests, 6 TLS/maintenance tests and packaging syntax. The final torrent-inventory failure guard was additionally checked with `go test -race ./internal/control`.
- `CGO_ENABLED=0 GOOS=linux GOARCH={amd64,arm64} go build -trimpath -buildvcs=false ./cmd/mjwatch` — both targets passed; temporary binaries removed.
- JDK 21: `./gradlew :core:testDebugUnitTest :playback:testDebugUnitTest` — 65 core + 11 playback tests passed.
- `./gradlew :app-mobile:lintDebug :app-tv:lintDebug --max-workers=1` — passed.
- `./gradlew :app-mobile:assembleDebug :app-tv:assembleDebug --max-workers=1` — passed.
- `git diff --check` — passed.

No live deployment, 512 MiB load test or runtime UI screenshots: `adb devices` returned no devices. Linux systemd activation is covered by configuration/migration tests, not a live systemd host. Estimates cannot predict future neighbour growth or arbitrary torrent metadata/decoder overhead.

Combined verification hit local disk exhaustion. Debug APK outputs and reproducible merged/compressed assets from this task were removed. Five oldest Gradle transform-cache entries were also removed after checking their manifests identify `dev.mjwatch.core`; these held redundant extracted server bundles from November 16. Source and release artifacts were preserved. Checks then ran sequentially. The TLS fixture now supplies explicit leaf AKI/SKI and CA SKI to isolate missing CA key usage under OpenSSL 3. That specific reproduction is skipped on pre-OpenSSL-3 runtimes; trust/hostname/expiry tests remain active.

## Publication candidate 0.9.10 / server 0.1.7

Rebased onto published main `431ec5c44fa10c69695fa7bc47a9b11b4d38acf1` (client 0.9.9/server 0.1.6). Changes were merged rather than replacing whole files; remote control, device installation identity/cleanup, decoder fallback, explicit Matroska handling and the latest TV UI remain intact. Unrelated untracked audit files from the original checkout are excluded.

Rebased checks passed: full `server/check.sh` with race/vet and packaging/TLS, 66 core and 16 playback unit tests, shared design-system compilation and release-script shell syntax. Full Android builds, lint, phone/TV runtime suites and signing remain mandatory CI publication gates. The published supply is generated in CI with the established release signing key.
