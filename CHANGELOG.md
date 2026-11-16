# Changelog

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
