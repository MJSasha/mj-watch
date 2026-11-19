# TV action focus — 2026-11-19

Published [MJWatch 0.9.17](https://github.com/MJSasha/mj-watch/releases/tag/test-143), version code 1143, from commit `b7e43cfa4da00706d504ede74e767f4fa54de2b1`.

The shared TV action now retains its normal fill and foreground when focused, with the theme primary stroke (lime in dark mode, dark green in light mode). A background-coloured inner gap keeps the stroke visible on a lime primary action without increasing bounds or changing spacing. The inset stroke survives viewport clipping. Press, loading and disabled activation guards remain unchanged.

`TvButtonsUiTest` checks the actual screenshot colours of the focus stroke and original fill for primary/secondary actions in both themes, with contrast at least 3:1 against the gap. Existing checks cover stable D-pad press bounds, loading/disabled activation, declared neighbouring gaps and strokes at both ends of a clipped list.

[Release CI](https://github.com/MJSasha/mj-watch/actions/runs/44509073902) passed build (unit tests, lint, debug/release APKs and server checks), TV runtime including the updated pixel-colour assertions and playback tests, and mobile runtime. The first mobile attempt failed before compilation because Gradle could not resolve the unchanged KSP plugin; rerunning failed jobs succeeded without changing source or tests.

Downloaded both published APKs, update descriptor, checksums and server supply. Verified version 0.9.17 / 1143, package identities, non-debuggable flags, APK signatures matching the previous release, hashes/sizes/URLs, server manifest signature and embedded payloads. Server remains 0.1.8 with the previous signing key. See [release-verification.json](release-verification.json).

Local runtime checks were not repeated because the local emulator cannot start with available disk space; mandatory CI supplied the runtime verification. No physical TV/OEM check was performed.
