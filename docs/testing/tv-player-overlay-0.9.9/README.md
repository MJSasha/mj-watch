# TV player overlay — 0.9.9

The TV player uses the same control-overlay gradients as the mobile player: black with alpha 0xAA at the edge and 0x44 at the intermediate stop; top height 24%, bottom height 38%. The previous dense blue-grey gradients and fixed 160/224 dp heights are replaced. Overlays remain conditional on control visibility and playback readiness.

Local validation: playback Kotlin compilation and TV debug APK build passed; git diff --check passed. No local device visual acceptance is claimed. Full Android build/lint/unit, server checks and phone/TV runtime CI gate publication. CI assigns versionCode and signs both APKs with the existing identity.
