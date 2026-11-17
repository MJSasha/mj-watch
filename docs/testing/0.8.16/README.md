# MJWatch 0.8.16 — cinematic startup

The shared Compose startup draws the existing MJ emblem with converging light beams, an orbit and a staged wordmark entrance. After the 1,100 ms entrance and local initialization, a 420 ms dissolve expands the emblem while the ready application fades in from 97% scale. An opaque backdrop prevents the native Android launch logo from showing through the dissolve. Compose animation duration scaling applies to the entrance, reveal and ambient orbit.

The completed state survives Activity recreation; navigation does not replay it. Touch and Back are consumed during the transition. A compact layout fits short landscape windows. No new dependencies or bitmap assets.

## Local validation

- 64 core and 10 playback unit tests: passed.
- Mobile and TV lintDebug, assembleDebug and assembleRelease: passed.
- Both test APKs built successfully.
- Mobile AppLifecycleUiTest: 5/5 passed on local API36 emulator.
- TV AppLifecycleUiTest: 5/5 passed on the same emulator with a 1920×1080, mdpi window. This checks the TV Compose/focus path; it is not a native Android TV device run. Previous display settings were restored afterwards.
- Tests capture the settled entrance and intermediate transition, verify that real app content exists during the dissolve, and that the overlay is removed afterwards. Separate checks cover navigation, Activity recreation, download cancellation, installation and explicit exit.
- Entrance and transition screenshots visually inspected in portrait and landscape. A cold-launch recording was captured locally.

GitHub CI must pass the full phone API35 and native TV API36 suites before publishing. The existing 0.8.15 publication must finish before this release is pushed, because workflow concurrency cancels an older main run.
