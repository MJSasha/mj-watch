# TV pairing / 0.6.2

## Reproduction

The unmodified 0.6.1 debug APK was retained and tested on an isolated API 36
arm64 emulator (`mjwatch-issues-6-7`, emulator-5560). With the managed HTTPS
address configured and its private CA absent from SecretStore, D-pad centre on
«Войти по коду TV» returned «Не удалось проверить TLS-сертификат сервера» and
left pairing null. This was a real TLS request to the dedicated Go fixture on
18543/18544, not an inferred cause or a mocked controller. A touch-style click
was not a valid way to exercise the TV Button; the reproduction uses D-pad.

## Resulting flow

The original T09 mockup specifies JSON import through the system file picker;
it does not specify managed pairing or QR. The managed extension follows that
import mechanism. A logged-in phone exports `mjwatch-tv.json` from Account →
Add TV. Transfer the file using an available file provider or USB. TV opens the
file picker, previews the HTTPS origin and CA fingerprint, and requires explicit
comparison with the phone. Confirmation stores only that origin's CA and starts
the existing server pairing protocol. Android trust applies when CA is empty.
TV shows the six-digit code; the phone previews the device and explicitly
approves it. No account password, invitation token, access/refresh token or user
ID is transferred in this file. Importing it alone cannot sign a device in.

The default TV screen contains no username/password form. Manual address and
account forms remain available as secondary paths. The code, waiting state and
errors are on the active screen. Retry and cancellation are explicit. Polling
has one cancellable job, uses the server expiry and stops on Back; a cancelled
job cannot clear a newer code or continue signing in in the background.

TLS validation and hostname verification are unchanged. No HTTP bootstrap,
trust-all client, certificate scraping, server discovery or QR service was added.
TVs without a document provider receive an actionable error rather than a crash.
A provider/file transfer is still needed for the initial private-CA setup; this
was not tested on a physical television or with a cloud provider.

## Checks

```sh
source .env.local
./gradlew :core:testDebugUnitTest :app-mobile:lintDebug :app-tv:lintDebug \
  :app-mobile:assembleDebug :app-tv:assembleDebug \
  :app-mobile:assembleRelease :app-tv:assembleRelease \
  :app-mobile:assembleDebugAndroidTest :app-tv:assembleDebugAndroidTest
sh server/check.sh
```

Start `server/cmd/fixture` with a new temporary directory and
MJWATCH_FIXTURE_PORT=18543 / MJWATCH_FIXTURE_CONTROL_PORT=18544. Reverse those
ports only on the dedicated emulator. Do not reuse the audit fixture at
18443/18444. Run `PairingTvUiTest` and `TvSetupUiTest` using the instrumentation
argument `-e pairingFixture true`. Ordinary CI skips these isolated-fixture tests.

TV coverage: unknown private CA and visible retry; invalid settings file; actual
DocumentsUI import; cancelled trust grants no CA; explicit fingerprint approval;
code appearance; cancel/restart survives an old poll interval; protected polling;
authorised phone lookup/approval; actual TV session/me; hostname mismatch; and
Back followed by late phone approval cannot sign the TV in. Mobile coverage:
export UI/fingerprint, actual file writing, parser round trip and absence of
account credentials. Core checks reject wrong schemas, oversized packets,
non-HTTPS/credential-bearing URLs and malformed CA, and check expiry feedback.

Screenshots are generated in ignored `dist/tv-pairing`, not the unrelated audit
folder. The emulator uses a 1920×1080 / 240 dpi TV viewport; it is an Android
phone image, not evidence of physical Android TV compatibility.

## Prior release status

Run 43831486425 built 0.6.1 and passed TV runtime, but mobile SessionTest still
looked for the removed player wording «Адрес сервера изменён». The current status
is «Подключение изменено». The assertion is updated; the test continues to check
that a mismatched stream origin is rejected without rendering a first frame.
The failed run skipped publish-release, so it did not publish 0.6.1.

## Verification result

50 core unit tests pass; both APKs build in debug/release; both lint reports have
zero errors. Server race/vet, seven packaging tests and syntax checks pass.
The ordinary runtime suites pass (mobile: 14 executed, 18 environment-gated
skips; TV: three executed, five skips). The dedicated fixture tests also pass:
two TV pairing tests, phone settings export, and the main TV scenario at font
scale 2.0. The changed player-origin assertion also passed within the full mobile
suite. No skipped live-server/SSH/PiP/physical-device checks are claimed as passed.

After the upstream assertion fix, run 43833746383 completed successfully and
published 0.6.1 (`test-27`). Its completion was explicitly awaited before sending
the TV fix, so the existing release was not cancelled by a new main push.
