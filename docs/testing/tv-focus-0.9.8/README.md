# TV focus and carousel release 0.9.8

Ports the unpublished TV focus changes onto current main 274ea73 (0.9.7), preserving all later playback, server, library artwork and mobile fixes.

TV carousels use a stable leading focus position and native row focus restoration. The featured metadata ID survives navigation. TV Material controls reserve 6 dp for external focus borders; custom TV rows draw their outlines inside the viewport. Vertical scroll clearance is converted from 8 dp to physical pixels.

Regression checks: TvCarouselUiTest covers long-row D-pad navigation, detail return and re-entry from the hero. TvButtonsUiTest covers the focus stroke at both ends of a clipped list. Full phone/native TV CI is required before publication. Local emulator startup is blocked by host disk capacity; no local visual acceptance is claimed.

The earlier local 0.8.17 candidate was never published and must not replace the newer production code. CI assigns the release versionCode and uses the existing signing identity. Server supply and decoder recovery remain unchanged.
