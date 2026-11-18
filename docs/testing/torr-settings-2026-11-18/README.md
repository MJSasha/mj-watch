# TorrServer settings and viewing capacity

Published 0.9.11 / server 0.1.8, 2026-11-18.

## Evidence and limits

- `01-before-access.png`: actual previous native SSH access screen, captured before changes. No authenticated SSH settings snapshot was available; this is not evidence of the old loaded editor.
- `phone/access.png`: new actual native access page, without a live managed server.
- `phone/memory.png`, `phone/disk.png`, `phone/limits.png`: production Compose editors rendered with deterministic server snapshots. The 512 MiB RAM / 4 GiB free disk values are fixtures, not this machine's configuration.
- `phone/stream-limit.png`: actual PlaybackActivity receiving HTTP 429 from MockWebServer, using Media3 and the production error policy. The response uses the old-server JSON error marker and the new account scope header.
- `wide/`: the same production editors rendered with TV styling in a landscape phone emulator. This checks the layout and native card activation with DirectionCenter, not physical TV hardware or a complete TV application flow.

Adjacent recommendation/maximum tiles expand to the same row height, including wrapped titles. Selecting a recommendation updates the draft and dismisses input focus/keyboard; saving remains explicit. Cache, preload, peers, rates and read-ahead have inline advice. Protocol ceilings are labelled separately from measured RAM/disk estimates.

## Verification

- `server/check.sh` with Python 3.12: Go race tests and vet, seven install-status tests, six TLS tests, packaging syntax checks passed. Existing Python invalid-escape warnings remain.
- Viewing-settings regression: owner-only access, revision conflicts, bounds, per-account <= global validation, persistence across restart, auto defaults, and preservation of existing streams when lowering limits passed.
- Core/playback JVM tests passed, including cache budget guidance and permitting cache reductions under pressure.
- Mobile and TV debug APK builds and lint passed.
- Portrait Android instrumentation: all four settings/player tests passed; all three editor tests passed again after the tile-height adjustment.
- Landscape editor instrumentation: all three tests passed with TV styling and DirectionCenter activation.
- The player capacity test asserts exactly one `/stream` request after an automatic refusal, then exactly one additional request after manual Retry. MainActivity connection probes are excluded from the stream count.

No settings were applied over SSH to a live server. These checks do not measure throughput, OOM behaviour or real concurrent video decoding. RAM/disk limits remain conservative snapshots and are validated again by root control when allocating cache.

CI release preparation: the first TV run timed out while an existing card-height test waited for a second Room instance's invalidation. Both cards are now seeded before activity launch, preserving the height assertion. A retry failed before tests with missing cached Maven artifacts; runtime jobs now refresh dependency metadata. No test was disabled or assertion relaxed.

## Published release

[0.9.11 / Latest / test-136](https://github.com/MJSasha/mj-watch/releases/tag/test-136), versionCode 1136, source `b9ee6ac931fd69752f9170d56a0f8a6fd1650853`. [CI](https://github.com/MJSasha/mj-watch/actions/runs/44383905268) passed build, mobile runtime, TV runtime and publication. The final TV run completed 68 app tests (14 fixture-dependent skips) and 23 player tests without failures.

Published APKs and server supply were independently downloaded. Sizes, GitHub digests, update.json and SHA256SUMS matched; both APKs have the existing signing certificate, expected package/version and no debuggable flag. Server 0.1.8's manifest signature and all bundled file hashes passed; its signing key matches previous releases. Both APKs embed the same verified supply. See [release-verification.json](release-verification.json).
