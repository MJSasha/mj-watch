# Changelog

## 0.8.13

- Keep the APK download dialog open after verification and offer installation or Later in the same window, with TV focus and the existing APK/permission checks.
- Replace blue player letterboxing with dark colours sampled locally from video frames, with black before frames are available (#24).
- Reserve two title lines and one metadata line so catalogue cards keep equal height.
- Bundle server 0.1.4: root maintenance uses a short-lived status-only credential rather than creating devices. Updating the server removes old Maintenance check sessions only when their root bootstrap receipts prove their origin; real devices and user data remain intact.

## 0.8.12

- Replace separate TV/mobile pairing entry points with one scanner icon in the account header and one shared device confirmation flow.
- Style connection QR codes with rounded modules/finders, a small MJ centre mark and the family accent; retain quiet space and error correction, and verify decoding down to 160 pixels.
- Reset the connection screen scroll when opening QR sign-in so the code remains visible after editing the manual form.

## 0.8.11

- Add optional QR sign-in on a new phone/tablet, approved from an authenticated phone with a separate server session and the existing encrypted LAN protocol.

- Seek ten seconds backwards/forwards by double-tapping the left/right half of the mobile video, with feedback and bounded positions (#20).
- Show the MJ logo from the native launch window and an animated branded startup screen while local configuration loads (#21).
- Add an explicit exit action in settings and confirmation on Back from home; stop playback/downloads and remove the task while keeping settings and history (#22).
- Keep update download percentages, bytes, verification status and a focused cancel action in a dialog outside the TV settings scroll area (#23).
- Add phone/TV startup, exit, download-progress and mobile gesture runtime regressions.

## 0.8.10

- Check the Latest release descriptor directly instead of using the unauthenticated GitHub REST API, so shared IP rate limits do not block app updates.
- Keep repository/tag/package, HTTPS redirect, hash and APK signature checks; report HTTP errors and check timeouts clearly.

## 0.8.9

- Keep phone navigation and search input usable in short landscape windows, with the keyboard open and at 200% font scale.
- Fix TV D-pad navigation out of input fields, restore the original focus after returning from details and updates, and keep pairing controls and connection focus rings within screen bounds.
- Use a compact spinner over the retained video frame for brief rebuffering on phone and TV, without stealing focus. Keep explanations and recovery actions for initial preparation, long waits and errors.
- Show build numbers for installed and available updates, clarify demo progress limitations, and fix Russian release-count plurals.
- Explain missing file-picker support on TV and cover the audit fixes with targeted runtime regressions.

## 0.8.8

- Keep the existing TV player design and add consistent remote navigation, immediate seeking with hidden controls, and timeline preview confirmed by OK or cancelled by Back.
- Hide controls after six seconds, retain focus across updates and panels, and exit directly with Back outside modal, preview and episode-offer states. Ignore repeated OK activations and handle hardware media keys through MediaSession.
- Add episode selection, video quality/speed/fit settings, validated opening/ending markers and a cancellable next-episode offer with a playback-based ten-second countdown. Auto-next defaults to on; automatic marker skipping defaults to off.
- Preserve paused position and speed across player recreation and in-place track/video changes; save progress approximately every fifteen seconds and before exit or episode changes.
- Run player unit and fake-engine remote regressions in CI alongside phone/TV runtime tests.

## 0.8.7

- Resume synced series when the device has no local release URL: reconstruct the magnet from the validated torrent hash/path/size identity in saved progress, then resume the exact episode and position. Existing private links remain preferred.
- Show loading in the continue button and a visible detail-screen error when an old record cannot be restored, instead of silently doing nothing.
- Add phone and TV regressions for normal resume, missing local links and unavailable legacy records.

## 0.8.6

- Show the saved season and episode in history and continue-watching cards (issue #14).
- Use the shared external focus ring around TV poster cards, with stable focus and pressed bounds (issue #15).
- Open TV library actions after OK is released, preserve the dialog and focus its explicit delete action (issue #16).
- Replace the server overview text block with one compact live status strip: connection, role, health, active streams, server version, last check and refresh every 15 seconds (issue #17).
- Replace the large catalog loading banner with a compact spinner and keep search feedback visible until all search work finishes (issue #18).

## 0.8.5

- Add previous/next episode controls to the phone and TV players, including media remote keys. Use all playable files from the selected release in season/episode order, exclude samples, preserve server file IDs and save each episode separately.
- Keep the screen awake during playback and buffering; release the wake request on pause, completion and exit.
- Fix season filters that interpreted episode ranges as additional seasons (issue #13).

## 0.8.4

- Match TV action buttons to the supplied MJ Family state reference: 52 dp primary / 48 dp secondary, stable rounded shape, unchanged focus fill with an external ring, darker press and tinted disabled state.
- Add an explicit TV loading state with a native spinner and disabled activation; connect it to update checking/download and settings saves.

## 0.8.3

- Add a restrained, theme-aware surface gradient to the automatic-update row, compact update notice and stream-preparation status; keep ordinary controls and surfaces flat.

- Align shared buttons, chips, tabs, badges, fields and settings groups with the MJ Family control sizes, spacing, shapes and typography. Keep long button labels readable at double font scale.
- Let the entire switch row toggle a setting, with a single accessible switch action instead of requiring a precise tap on the thumb.
- Separate selected filters from TV focus, retain focus without scaling controls, and use the actual focused/disabled content color for TV button labels and icons. Define explicit error colors for both themes.

## 0.8.2

- Fix managed maintenance on Python 3.13+ with existing MJWatch CAs that lack `keyUsage`. Use an explicit private-CA TLS client context with required chain, validity, hostname and TLS 1.2+ checks; retain the installed CA and account data.
- Server 0.1.3 creates new CAs with critical certificate-signing key usage. Existing CAs are never rotated. Add real TLS regression checks for legacy CAs, strict validation of new CAs, wrong CA/hostname/expiry and a guarded one-time repair for installed old updaters.
- Include the server-update wizard from 0.8.1.

## 0.8.1

- Replace the long server-update form with a four-step wizard: package, SSH access, backup phrase and final review. Explain every credential, confirm the backup phrase and keep other maintenance operations collapsed.
- Show actual upload and server-operation stages, confirm completion only from a verified receipt, and retain an operation identifier for read-only status checks after interruption or recreation. Recover operations started by 0.8.0 without launching them again.
- Distinguish confirmed rollback from `health_failed`, whose rollback result is unknown; show plain-language recovery guidance and copyable diagnostics without credentials. This UI change does not resolve a server's failed health check by itself.

## 0.8.0

- Replace JSON invitation sharing with a short HTTPS link and Android's share sheet. Tapping it opens password registration directly on cold and warm app starts; address and server trust are filled automatically.
- Add verified Android App Links and a download/open page for recipients without MJWatch. Invitation fields stay in the URL fragment; the page has no registration form, analytics or calls to the invited server.
- Bootstrap private-CA trust only when the server's presented CA matches the invitation fingerprint, while preserving TLS chain, expiry and hostname verification. The server 0.1.2 supply includes its public CA in the TLS chain; private keys remain on the server.
- Keep single-use expiry/revocation checks, legacy invitation imports and existing profiles. Opening a link alone never changes account or trust; the recipient explicitly creates a password before joining.

## 0.7.1

- #8–9: add Series and load dedicated movie/TV/anime/animation collections instead of filtering the weekly trending page. Cache each section separately; preserve the selected section on Back. Managed categories require server 0.1.1 and explain the required update on older servers.
- #10: group the user summary, invitation/device actions and access management, add icons and consequences, retain named confirmations and last-administrator protection.
- #11: group release filters into wrapping choices with available qualities, a live result count, active-filter count and one reset action.
- #12: separate TV timeline navigation from seeking, strengthen action focus, keep cinematic home readable in light theme, label the navigation rail, focus connection content, launch a selected file with a second OK, and replace `und` with a readable track label.

## 0.7.0

- TV connects through an automatically generated QR, with no server address,
  password, code or file transfer on TV. The authenticated phone scans it with
  its camera, transfers public settings through an encrypted one-time LAN inbox,
  then previews and explicitly approves the device.
- TV explicitly accepts the named account/server before trusting its CA. Managed
  HTTPS and hostname checks remain enforced; passwords, account tokens and the TV
  polling secret are never sent over the local bootstrap channel.
- QR expires after five minutes. Refresh, rejection, cancellation on either
  device and account changes terminate the old attempt. Old codes cannot replace
  a new proposal. Phone camera scanning stays in portrait orientation.
- Manual code/file/password routes remain under secondary actions. Both phone
  and TV APKs need this update, and must share a private local network.

Validation: docs/testing/TV-QR.md.

## 0.6.2

- Dedicated TV pairing screen: import public connection settings from a phone,
  explicitly compare the CA fingerprint, then display the code without a username
  or password on TV. Phone exports only the HTTPS address and public CA.
- Visible connection errors, retry/cancel, expiration feedback, and cancellation
  of pairing polling on Back; old polling jobs cannot clear a new code. TLS and
  hostname verification remain enforced.
- Update the session regression test to the current player status wording, which
  blocked the 0.6.1 release despite the intended server-origin rejection.

Validation: docs/testing/TV-PAIRING.md.

## 0.6.1

- #6: compact loading/buffering status in the player, meaningful connection retry
  and error states, explicit retry/back actions; hide unknown-duration controls
  while preparing video. Slow buffering no longer pauses playback after a minute.
- #7: independent user/list/device/invitation state; compact user list, dedicated
  create/detail/invite/device pages, scoped operation errors separate from sync,
  last-admin protection, named destructive confirmations and invitation replacement.
- Separate account/device/TV/logout tasks, disable empty legacy imports, add radio
  selection to preferences and preserve two-pane user management on wide screens/TV.

Validation: docs/testing/ISSUES-6-7.md.

## 0.6.0

- #1: shared MJ launcher icon with the exact Reader monogram, Watch film-strip
  symbol, adaptive color/monochrome layers, notification silhouette and TV banner.
- #2: one history/continue card per metadata title; cached artwork and description,
  film-first navigation and most recent linked resume position. Removal/undo
  includes all grouped openings and preserves separate favorites.
- #3: quality, season and voice filters; multi-season packs, numeric episode order,
  season-aware file selection and retained film/episode context.
- #4: managed members no longer see direct TorrServer or catalogue configuration;
  parser edits preserve the account and unrelated credentials. Empty/missing
  parser addresses default to https://jac.red.
- #5: removed the inert animations row and its TV counterpart.

Published as a normal GitHub Latest release after all CI gates pass. The existing
APK/server keys and update descriptor format are retained for compatibility.

Validation and scope: docs/testing/ISSUES-1-5.md.
