# 0.8.13 / server 0.1.4

- Update dialog: 100% bytes remains in verification state; only a verified ready file reveals Install. The modal stays open through readiness, permission handoff and installer launch. Later dismisses it without deleting the APK. The section and dialog share the same signature/package/hash validation and FileProvider installer path.
- Player #24: black window/root/shutter and black control scrims; low-resolution PixelCopy samples supply dimmed video-edge gradients for uncovered regions. Sampling is local and foreground-only; unavailable/protected surfaces keep the black fallback. No frames are written to disk or sent anywhere.
- Catalogue: two title lines and one metadata line keep cards aligned without a fixed font-dependent pixel height.
- Maintenance: status-only, expiring root credentials replace device bootstrap. Cleanup is restricted to devices proven by private owned health receipts and matching session hashes. The pre-upgrade helper remains compatible during the binary switch. Server 0.1.4 must be applied to repair an existing server.

Checks include phone/TV ready-dialog installation controls, a real SurfaceView/PixelCopy runtime test, video-edge colour unit checks, server token scope/rotation/expiry/no-device tests, cleanup/idempotency/real-device protection, and Python TLS/helper checks. The release pipeline also runs all existing unit/lint/debug/release and phone/TV suites before signing and publishing.
