# TV connection through QR

The user-requested QR flow replaces the 0.6.2 file-transfer bootstrap as the
default. It is an extension of the managed flow, not an existing T09 mockup.

## User flow

Open managed connection on TV. A QR appears without entering a server address.
On an authenticated phone: Account → Add TV → Scan QR. The phone's portrait
camera scanner reads the TV's one-time connection invitation. TV names the
account and HTTPS origin and requires “Connect”. The phone then displays the
actual device returned by the managed server and requires “Connect TV”. The TV
polls its existing protected pairing request, receives its own session and opens
Home. No TV text entry, certificate copying or file transfer is needed. The phone
and TV must be on the same private IPv4 LAN with peer-to-peer access.

Code/password/file paths remain under “Other sign-in methods” for compatibility;
they are not the default QR path. Both APKs need the QR update. Camera permission
is requested only by the mobile APK, which still declares camera hardware optional.
The TV APK has no camera permission or scanner activity.

## Trust and protocol

- The TV opens a temporary inbox bound to its private LAN address. The QR contains
  its numeric private IPv4 address, ephemeral port, protocol version and random
  256-bit key. Unknown schemes, public/loopback/link-local/DNS targets, duplicate
  parameters and malformed keys are rejected. Neither QR nor key is persisted,
  copied to the clipboard, or written to saved UI state.
- Setup, status and cancellation messages use AES-256-GCM with fresh 96-bit IVs.
  Associated data binds both endpoint and request/response direction. Local HTTP
  carries authenticated ciphertext; ManagedApi continues using verified HTTPS.
  No trust-all TLS client, hostname bypass or unauthenticated CA download exists.
- The phone checks its authenticated server session before sending only the HTTPS
  address, public CA, account display name and public server/user IDs. TV checks
  those IDs against its authenticated `me` response before storing any session.
  Access/refresh tokens, passwords,
  invitation tokens and the TV polling secret are never transferred through LAN.
- The inbox accepts one phone nonce and one proposal. Exact setup retries are
  idempotent; another phone/changed proposal cannot overwrite it. TV explicitly
  accepts the QR-bound proposal before saving CA trust for that exact origin.
- The existing backend pairing secret remains on TV. The six-digit code travels
  encrypted to the phone. Phone retrieves the server's device preview and checks
  that the same live TV is still present before explicit approval. The backend
  issues a separate TV session only through the protected poll.
- QR expires after five monotonic minutes. Refresh/rejection replaces the key and
  closes the old inbox. Phone cancellation notifies the authenticated inbox; TV
  refreshes and stops pairing. Back closes the inbox and stops polling. Account
  changes clear phone state and cancel the old exchange. Late callbacks are scoped
  to their QR/account generation.
- The inbox bounds headers and body (64 KiB), uses per-connection timeouts, rejects
  chunked/duplicate lengths and redirects, and closes on disposal/expiry. It is a
  minimal encrypted local protocol, not a browser-based setup website.

## Reproduction / checks

Use isolated temporary AVDs `mjwatch-qr-tv` (5564) and `mjwatch-qr-phone` (5566).
Do not alter the audit's 5554/5560. Start a new Go fixture database with
MJWATCH_FIXTURE_PORT=18643 / MJWATCH_FIXTURE_CONTROL_PORT=18644 and reverse these
ports to both test devices. No production server, real account, or physical TV
is used. Emulator 37's shared Wi-Fi provides actual inter-device private IPs.

`QrHostTest` with `-e qrHost true` starts a fresh TV without URL/CA, captures and
decodes the visible QR, then waits for the phone. It saves the captured QR and a
camera fixture in external `qr-proof`. The imagefile camera fixture positions that
same rendered QR inside the emulator camera crop. It does not supply a
fake scan callback. Restart the phone with the official emulator option
`-camera-back imagefile:<camera-qr.png>` and reverse the fixture ports again.

`QrCameraTest` with `-e qrCamera true` opens the actual scanner activity, allows
camera permission, waits for optical decode, passes the existing activity-result
handler, displays the real server device preview and explicitly approves it.
Host confirms using D-pad and requires actual Home/account state. Phone requires
a new device ID in the real fixture database. The test camera is synthetic;
physical-camera focus/glare and guest-network isolation are not claimed tested.

`QrProtocolTest`, `QrLifecycleTest` and `QrPhoneUiTest` use `-e qrSecurity true`
for tampering, single use, expiry, malformed/offline QR, retry, both cancellations,
rejection, account change and late approval after Back. Ordinary CI skips the
explicitly gated two-device/fixture tests. Core unit tests exercise URI validation
and encryption/authentication/direction binding without a network dependency.

Build/lint/unit commands follow TV-PAIRING.md. Proof is stored under ignored
`dist/qr-connection`; audit materials remain untouched.

## Verified for 0.7.0 (2026-11-16)

- Final source: 54 core unit tests, debug/release builds and lint for both APKs
  passed (zero lint errors). Server race tests, vet, seven packaging checks and
  shell syntax checks passed.
- Final APKs on two isolated Android 36 emulators: optical camera scan through
  the real scanner, private-CA HTTPS, D-pad confirmation, server device preview,
  explicit approval and a new authenticated TV device all passed. The same flow
  also passed with TV font scale 200% before the final hardening changes.
- Six TV protocol/lifecycle tests and two phone QR UI tests passed on final APKs,
  including stale preview refusal, wrong key, competing phone, expiry, rejection,
  cancellation, account change, Back/late approval and startup socket cleanup.
- Real camera-permission denial passed. Legacy setup compatibility passed three
  tests. Ordinary regression suites executed 14 mobile and three TV tests; gated
  fixture tests skipped by those suites are not counted as executed.
- Logs and screenshots are retained in `dist/qr-connection`. Camera input was the
  captured TV QR through the emulator imagefile camera, not a physical camera.

## Mobile sign-in (0.8.11)

On the new phone/tablet choose **Войти на сервер MJWatch → Войти по QR с другого устройства**. Password/invitation sign-in remains the default. On an authenticated phone choose **Аккаунт → Добавить телефон или планшет → Сканировать QR нового устройства**. Both devices must share a private Wi-Fi/LAN with peer access. Accept the named account/server on the new device, then approve the server device preview on the authenticated phone. The new phone receives its own session through the existing protected pairing poll. No password or existing session token is copied. The QR expires after five minutes; refresh/rejection/Back close the prior inbox and stop polling.

The QR protocol/URI remain compatible with the TV flow. Mobile uses a vertical QR layout and ordinary touch controls. Back returns to the password form and preserves entered text. The `MobileQrLoginTest` ordinary regression covers the optional entry point, rejection, key replacement and Back cleanup; `-e mobileQrFixture true` additionally verifies HTTPS pairing and a distinct mobile device session against an isolated fixture on ports 18643/18644.
