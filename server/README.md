# MJWatch managed server v1

Go + embedded SQLite WAL. Server version **0.1.19**, HTTPS API **1**, schema **1**. Versions are independent of the Android APK. TorrServer is pinned to **MatriX.145.2**; artifacts and hashes are in `supply.lock.json`.

## Build and trust

Requires Go 1.26.1, Python 3.9+, OpenSSL, Android JDK 21/SDK 36. Download the two official TorrServer artifacts named in `supply.lock.json` from the pinned upstream release and verify their SHA-256 before building.

```sh
export MJWATCH_SIGNING_KEY=/secure/path/release-private.pem
export TORRSERVER_ARTIFACT_DIR=/path/to/verified/artifacts
export MJWATCH_SERVER_VERSION=0.1.19
sh server/build.sh
sh server/check.sh
./gradlew testDebugUnitTest lintDebug :app-mobile:assembleDebug :app-tv:assembleDebug
```

`build.sh` produces Linux amd64/arm64 executables, a signed manifest, deterministic ZIP/tar.gz supplies and SHA256SUMS under `dist/server/`, and embeds the same supply in Android assets. A private signing key is mandatory; there is no skip-verification flag. For a local disposable supply, generate an RSA key with `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out /secure/path/release-private.pem`. `server/prepare-ci.sh --local-test` downloads pinned artifacts and uses an ephemeral test key; publication uses the stable `MJWATCH_RELEASE_SIGNING_KEY` CI secret. The 0.5.0 testing channel retains the public identity used by the earlier local supply and preserves its private key in that repository secret so future server updates remain compatible. A key change requires an APK trust update. This is a testing-channel identity, not a separate production identity. No private key or credentials are in source, APK or supply.

## Install from the native app

Choose connection → automatic installation. Enter host, root SSH password and optionally SSH port. Independently compare the SSH fingerprint with the provider console before trusting it. The installer accepts Debian and Ubuntu without a VERSION_ID allowlist, including Ubuntu 26.04. It checks actual prerequisites: Python 3.9+, active systemd, apt-get/systemctl/useradd/id/chown, amd64/arm64, at least 500 MB RAM (500,000,000 bytes of total physical memory) and 2 GiB free disk. Package repositories must be usable. Other distributions and derivative IDs are not assumed compatible merely from ID_LIKE. Accepted OS versions are not a live-tested support matrix: actual full installation evidence remains Debian 12 arm64; newer Ubuntu/Debian admission is covered by fixtures. See the acceptance reports.

The app uploads verified files and starts a detached systemd job. Reopening the installation page can resume/query actual state. No terminal is required for the user. Installer creates separate service accounts, loopback-only authenticated TorrServer, a private CA, scoped firewall rules, owner bootstrap, certificate renewal timer and autostart. Existing services/accounts/path conflicts stop installation. Repeating the same version preserves users/data. A version change uses the signed update workflow. Jackett/JacRed and optional TMDB are configured separately.

CA trust is delivered over pinned SSH and applies only to the managed origin. Hostname verification remains enabled. Root SSH secrets stay encrypted on the owner device; stored credentials require local credential unlock. Regular users never receive SSH, internal TorrServer credentials or the TMDB key.

## Invite a user

Owner: Settings → Server management → Users → enter a name → Add user → Copy invitation. The one-use invitation expires in 24 hours; the raw code is hidden from the main screen. Share it privately. Android clipboard content is marked sensitive.

Recipient: copy the received invitation → “Вставить приглашение” → compare the server certificate fingerprint with the owner and confirm → choose a password of at least 10 characters → “Создать аккаунт и войти”. Address, port, invitation code and CA are imported together. No manual JSON/PEM editing is needed. Clipboard is read only when that button is pressed; an empty/invalid clipboard opens a manual-paste dialog. Manual connection settings remain under a disclosure.

CA trust is explicitly confirmed and stored only for the imported origin. Import alone makes no network request and does not trust a certificate automatically. The existing TLS hostname/chain validation remains in effect; the UI import test is not proof of a successful connection to an arbitrary real server.

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

Legacy CA / Python 3.13+ `health_failed`: see [one-time installed-updater repair](../docs/testing/PYTHON314-HEALTH.md). Server 0.1.3 preserves existing CA trust and adds explicit critical signing key usage for new CAs.

## Internal JacRed (0.1.14)

Install or update the managed server from the app, then open **My server → JacRed** as the owner. The SSH workflow installs the pinned JacRed release for Linux AMD64 or ARM64, verifies its SHA-256, and starts a dedicated systemd service. JacRed listens on loopback. Signed-in clients search through the existing MJWatch HTTPS endpoint.

Choose trackers and a collection interval, or enter an HTTPS synchronization server you can access. A new catalog fills as collection or synchronization runs. **Settings → Jackett / JacRed** selects internal or external search on each client. Internal is the default for managed profiles. Direct TorrServer profiles use the configured external source.

Starting with server 0.1.15, **My server → JacRed** shows search health, catalog counts, per-tracker collection progress and the last 12 runs. An authenticated administrator can start a collection from that page. Installation and configuration continue to use owner SSH.

Server 0.1.16 can deliver catalog statistics independently of search health and collection progress. The stats panel retains its last values during refresh and offers a separate retry when statistics are unavailable.

Starting with server 0.1.18, **Refresh statistics** asks JacRed to recalculate the report from its local catalog. This works even with tracker collection disabled.

Starting with server 0.1.19, the administrator can read active JacRed jobs and start a supported tracker's release refresh in MJWatch. The job list shows live process progress. MJWatch keeps its scheduled collection history separately.

Starting with server 0.1.17, the owner can load JacRed settings over SSH and open the embedded JacRed admin page. MJ Watch verifies the server identity and opens a temporary authenticated loopback tunnel. Closing the page or backgrounding the app closes the tunnel. Advanced settings saved in the editor survive later changes from MJ Watch. The listener, credentials and collection settings are managed by MJ Watch.

The JacRed catalog lives in `/var/lib/mjwatch-jacred/Data`. On a replacement host, install JacRed and configure its sources again. To preserve the existing catalog, stop the JacRed service while copying this directory, and set ownership to `mjwatch-jacred` on the destination. The built-in MJWatch account/history transfer does not include the JacRed catalog.

## Resource profiles and diagnostics (0.1.7)

Starting with server 0.1.16, administrators can open **My server → Server resources** to monitor CPU load, available memory and disk space. The app refreshes the readings every five seconds while the page is open. CPU usage appears after the second sample. The page also shows the MJWatch data disk when it is on a separate filesystem.

The automatic viewing profile is chosen at startup using effective RAM (including inherited cgroup v2 limits): at most 1 GiB → one viewing session/preparation; at most 2 GiB → two; otherwise four. Unknown RAM uses one. Password checks/uploads allow one concurrent operation on the smallest profile, two otherwise. Two overlapping Range requests for the same session/torrent/file share one viewing slot, allowing seeking without consuming a second slot. Preparation, password/upload budgets and system memory limits stay tied to the RAM profile.

Owners can read `GET /api/v1/admin/playback/settings` and replace both limits with `PATCH` plus the returned `revision` in `If-Match`. The body is `{"maxStreams":0,"maxStreamsPerUser":0}`: zero means automatic; manual values are 1–8. Automatic per-account capacity is min(2, effective server capacity). Manual per-account capacity cannot exceed the effective server limit. Settings persist in SQLite and apply immediately; lowering them only prevents new views until capacity is available. Capacity refusals retain HTTP 429 / `stream_limit` and include `X-MJWatch-Error` and `X-MJWatch-Limit-Scope` (account/server). These additions are available starting with 0.1.8.

Owner → TorrServer groups cache, network and advanced settings. Key numeric inputs have tappable recommendations and adjacent maximum tiles; SSH access is collapsed until needed. Reading over SSH supplies RAM and disk estimates. `MemAvailable` includes neighbours' current consumption. The reserve is `max(128 MiB, 10% of effective RAM) + 96 MiB` for OS/neighbour growth and MJWatch peaks. The projected cache budget is at most half of effective RAM and the available memory after reserve, divided among existing torrents plus room for new viewers. Existing cache is not credited back. This is a conservative snapshot, not a guaranteed physical maximum. Unknown/insufficient memory produces no usable recommendation. Targets are at most 64 MiB on the smallest profile, 128 MiB otherwise. RAM-cache increases and disk-to-RAM switches are checked again before applying; reductions remain possible under pressure. Disk cache uses free space minus a 2 GiB reserve, divided by projected torrents and capped at 8 GiB per torrent; the recommendation is at most 256 MiB. Disk allocation increases and mode/path changes are checked again before applying. Reductions remain available under disk pressure. Protocol bounds for peers/rates are labelled separately from measured resource maxima. Existing settings are preserved until explicitly applied. New small installations start with 64 MiB cache, 30 peer connections and 25% preload.

Phone/TV → video parameters → Buffer shows buffered seconds, recommended target and estimated device maximum. Auto targets up to 64 MiB (16 MiB on low-RAM devices). The maximum considers app heap, current heap headroom and available RAM, capped at 128 MiB (24 MiB on low-RAM devices), with a 4 MiB minimum target. Loading aims for up to 120 seconds but stops at the byte target; allocator/decoder overhead is additional. A selection applies on the next video launch and is clamped again to current headroom. Device buffer and server cache serve different network links.

Both services use the native `mjwatch` journal namespace: 16 MiB persistent budget, 8 MiB runtime budget, seven-day retention and message-rate limits. Journald's normal allocation/rotation overhead applies. One namespaced journald instance serves both processes; host/default logging settings are unchanged. Diagnostics include slow/failed request category/status/duration, stream outcomes and minute-level available RAM/Go heap/disk/active-view snapshots. Application diagnostics omit request paths, queries, bodies and credentials. TorrServer's upstream logs may contain torrent names/hashes; logs are administrator data.

```sh
journalctl --namespace=mjwatch -u mjwatch.service -u mjwatch-torrserver.service --since '-30 min'
# Unit lifecycle/restarts and kernel OOM diagnostics remain in the host journal:
journalctl -u mjwatch.service -u mjwatch-torrserver.service --since '-30 min'
journalctl -k --since '-30 min'
```

The first root health probe after a signed update migrates old units, including old bootstrap-style probes. It adds one restart inside confirmed maintenance. Failed migration restores prior logging files and attempts to restart the previous configuration; a later probe can retry. Completed migration does not restart again.

Presence writes occur at most once a minute per device. Sync reuses its `/me` response, backs failed polling off through 60/120/240/300 seconds with jitter, and wakes on default-network reachability. Successful polling returns to 30 seconds. Local outbox data remains durable. Sync/retention/device queries have indexes; maintenance expires cache/snapshot/pairing/invitation records. Change history and mutation receipts are retained for cursor and replay semantics.

## Maintenance probes (0.1.4)

Server 0.1.4 replaces the old Maintenance check bootstrap sessions with a root-issued, 90-second credential scoped only to GET `/api/v1/admin/status`. It creates neither devices nor refresh sessions and keeps the authenticated TLS health check. The old helper's `bootstrap` health invocation is recognized during an in-place upgrade, so the worker already running before the binary switch cannot recreate the problem.

On the next health check, matching `health-<timestamp>.bootstrap` receipts are read only if regular, private and owned by the executing root user. A device is deleted only when its ID, admin ownership, exact Maintenance check name and stored refresh-token hash all match the receipt. Its sessions cascade away and the obsolete receipt is removed. Other devices and all user/history data remain unchanged. Old probes without verifiable receipts are preserved rather than guessed. Apply the signed 0.1.4 supply through Settings → My server → Maintenance → Update server to clean an existing installation.

## Device cleanup (0.1.6)

Deleting an individual device removes its database record and cascades sessions, receiver state, profiles and installation mapping. Previously revoked devices remain removable. Repeated sign-ins from one app installation are deduplicated by an opaque persisted UUID within the same account; equal names alone are never merged. Legacy devices without an installation identity can be removed explicitly. The client sends the new profile field only after `me.deviceInstallationIdentity=true`, preserving 0.1.5 compatibility.

## Move an installation to another server (0.1.9)

An owner can move a managed installation and keep each device's account, password, access and refresh tokens, server and history epochs, sync cursor, mutation receipts, invitations, settings and encrypted credentials. The move includes the SQLite database, its encryption key, TorrServer's persistent torrent library/settings and the encrypted backup directory. Downloaded torrent pieces are a disposable cache. The new host uses its own HTTPS identity, service accounts, loopback password and API port. Its firewall must allow the peer port in the transferred TorrServer settings.

1. Install a migration-capable client on phones, tablets and TVs before moving. Update the source to server **0.1.9** and install the same supply on a fresh destination with its final hostname, a distinct private CA, and sufficient disk space for staging and the installed copy. The source must be able to reach the destination's advertised HTTPS address. Both hosts must use the managed installer's standard paths. On the destination, the database, TorrServer and backup directories must be movable within the same Linux mount as the migration staging directory. The tool checks this before interrupting the source.
2. On your workstation configure two OpenSSH aliases for root access using keys or an agent. Verify both host keys independently and record them in `known_hosts`. The tool uses batch SSH and strict host key verification. Keep the old hostname, address and leaf certificate renewal available for returning devices.
3. Extract `migrate.py` **and** `migration_host.py` from the top level of the signed server supply ZIP into one directory. Alternatively run `server/migrate.py` from this repository with `server/packaging/migration_host.py` alongside the checkout. From the extracted directory run:

   ```sh
   python3 migrate.py mjwatch-old mjwatch-new
   ```

   If installation already created the new host's initial owner account and device, add `--replace-bootstrap` to explicitly replace that bootstrap. Target validation rejects additional users, devices, watching history, configured application settings, backups and torrents. The destination's previous files are retained under `/var/lib/mjwatch-install/migration/original`. SSH credentials stay on the workstation. Transfer streams over the two SSH connections.
4. During cutover current streams end, both source services stop, and the old HTTPS endpoint answers `503 migration_in_progress`. The script snapshots committed SQLite data, checks the received database, starts the destination and verifies its HTTPS identity, database and TorrServer health. Only then does the old endpoint announce the new origin and private CA with `409 server_moved`. Clients verify the new TLS hostname, CA, migration ID, server ID and epoch before reusing credentials. Existing token expiry and revocation dates still apply. Owner SSH management on the new host needs its own verified SSH access.

Rerun the same command after a disconnect. Before destination installation begins, `python3 migrate.py mjwatch-old mjwatch-new --abort` cancels the reservation and reopens the original. Once installation starts, resume forward to protect writes on the destination. The old host remains a read-only HTTPS announcement service after completion. Its source data stays on disk. A returning device must still be able to reach each intermediate address if there have been multiple moves. Ordinary backup restore remains a separate recovery operation that revokes sessions.
