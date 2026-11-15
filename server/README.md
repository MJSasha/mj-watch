# MJWatch managed server v1

Go + embedded SQLite WAL. Server version **0.1.0**, HTTPS API **1**, schema **1**. Versions are independent of the Android APK. TorrServer is pinned to **MatriX.145.2**; artifacts and hashes are in `supply.lock.json`.

## Build and trust

Requires Go 1.26.1, Python 3.9+, OpenSSL, Android JDK 21/SDK 36. Download the two official TorrServer artifacts named in `supply.lock.json` from the pinned upstream release and verify their SHA-256 before building.

```sh
export MJWATCH_SIGNING_KEY=/secure/path/release-private.pem
export TORRSERVER_ARTIFACT_DIR=/path/to/verified/artifacts
export MJWATCH_SERVER_VERSION=0.1.0
sh server/build.sh
sh server/check.sh
./gradlew testDebugUnitTest lintDebug :app-mobile:assembleDebug :app-tv:assembleDebug
```

`build.sh` produces Linux amd64/arm64 executables, a signed manifest, deterministic ZIP/tar.gz supplies and SHA256SUMS under `dist/server/`, and embeds the same supply in Android assets. A private signing key is mandatory; there is no skip-verification flag. For a local disposable supply, generate an RSA key with `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out /secure/path/release-private.pem`. `server/prepare-ci.sh --local-test` downloads pinned artifacts and uses an ephemeral test key; publication uses the stable `MJWATCH_RELEASE_SIGNING_KEY` CI secret. The 0.5.0 testing channel retains the public identity used by the earlier local supply and preserves its private key in that repository secret so future server updates remain compatible. A key change requires an APK trust update. This is a testing-channel identity, not a separate production identity. No private key or credentials are in source, APK or supply.

## Install from the native app

Choose connection → automatic installation. Enter host, root SSH password and optionally SSH port. Independently compare the SSH fingerprint with the provider console before trusting it. Supported installer targets are Debian 12 / Ubuntu 24.04, systemd, amd64/arm64, at least 2 GiB RAM/free disk; tested combinations are listed in the acceptance report.

The app uploads verified files and starts a detached systemd job. Reopening the installation page can resume/query actual state. No terminal is required for the user. Installer creates separate service accounts, loopback-only authenticated TorrServer, a private CA, scoped firewall rules, owner bootstrap, certificate renewal timer and autostart. Existing services/accounts/path conflicts stop installation. Repeating the same version preserves users/data. A version change uses the signed update workflow. Jackett/JacRed and optional TMDB are configured separately.

CA trust is delivered over pinned SSH and applies only to the managed origin. Hostname verification remains enabled. Root SSH secrets stay encrypted on the owner device; stored credentials require local credential unlock. Regular users never receive SSH, internal TorrServer credentials or the TMDB key.

## Operations and updates

* TorrServer settings/read/apply/defaults: root SSH fixed `mjwatchctl` commands. JSON inputs, revision checks, resource/path validation, preserved unknown fields, durable receipts and reread verification. Detached unique systemd jobs survive SSH disconnects.
* TMDB settings/key validation/rotation/deletion/cache: authenticated **HTTPS admin API**, never SSH. Both v3 keys and Read Access Tokens are accepted. Candidate validation precedes replacement. Credentials are AES-GCM encrypted under a separate server key; no readback. Metadata/images use fixed TMDB origins and bounded cache.
* Users, invitations, devices and sync: HTTPS with server-enforced ACL. Last active owner cannot be deleted/blocked. Root SSH can bootstrap/recover the owner.
* Backup: online SQLite Backup API; encrypted chunked AES-GCM with scrypt passphrase derivation and authenticated end marker. Download requires password reauthentication. Archives contain secrets: retain passphrases separately and store archives safely.
* Restore: upload a `.mjwb` archive to the server backup directory through trusted administration, choose its name and passphrase in native maintenance. Staging/integrity/schema checks precede replacement. Sessions/invitations are revoked, epoch changes; clients log in and snapshot again. Original data is retained for failure recovery.
* Update: import a signed `.zip` supply in native maintenance, enter SSH credentials and backup passphrase, confirm active streams. The app verifies signature/hash/API/schema; root verifies again with installed trust key, creates an encrypted backup, atomically changes the version symlink, checks HTTPS/SQLite/TorrServer. Failed health restores the previous binaries and verifies them. Schema 1 is supported; unknown future schemas are rejected. This is not a general rollback engine for arbitrary future database migrations.

Paths: `/etc/mjwatch` configuration/keys; `/var/lib/mjwatch` SQLite; `/var/lib/torrserver-mjwatch` TorrServer; `/var/backups/mjwatch` archives; `/var/lib/mjwatch-install` jobs/receipts; `/opt/mjwatch/releases/<version>` immutable version directories; `/opt/mjwatch/current` active symlink. Stable root operation transport is outside that symlink so polling survives a bad update.

Service units are `mjwatch.service`, `mjwatch-torrserver.service`, `mjwatch-renew.timer`. Root recovery uses `/usr/local/sbin/mjwatchctl bootstrap` with a JSON device/operation ID over trusted SSH. Do not expose TorrServer or root control on public HTTP. Ports/firewall/plan are installation-specific; the app receives the selected HTTPS endpoint automatically.

## Tests

`check.sh` runs service/control contracts, race detector, vet and packaging syntax. `cmd/fixture` is test-only and is not included in server supply/APK: private-CA HTTPS API on 18443, loopback fixture control on 18444, SQLite and controlled MP4 upstream. Never deploy it. Android tests require explicit reverse ports to a dedicated emulator. Live installation/update/restore evidence and untested scenarios: [`../docs/testing/SERVER-MANAGEMENT-V1.md`](../docs/testing/SERVER-MANAGEMENT-V1.md).
