# Issues 6–7: buffering and user-management regressions

Source requirements: [#6](https://github.com/MJSasha/mj-watch/issues/6) (including
its supplied player screenshot) and [#7](https://github.com/MJSasha/mj-watch/issues/7).
The original settings audit is kept separately; its screenshots are not presented
as screenshots of these fixes.

## Player (#6)

The full-width native status TextView is replaced with a bounded Compose surface:
spinner, preparation/buffering/reconnection text, and cancel/back. Error states
have an explicit retry when useful. Unknown-duration playback controls stay hidden
until the stream is ready. The existing retry limit remains; an initial failure
can be retried against the same stream without adding another torrent. The
watchdog no longer pauses playback after one minute of buffering. A three-minute
terminal timeout still prevents endless preparation.

The instrumentation test delays an actual local HTTP response, checks the loading
surface width, supplies invalid media, then retries with the bundled real MP4.
It requires the actual Media3 first-frame callback and disappearance of the status.

## Settings/users (#7)

- F01/F02: lazy compact list and separate create/user/invite/device routes. User
  list, devices keyed by user ID, transient invitation results and draft name have
  independent state. Wide screens/TV show list and detail beside each other.
  Configuration observation remains active across profile changes: the call to
  WatchModel.cancel is qualified so CoroutineScope.cancel cannot cancel the
  settings collector itself. Logout and invitation/device cleanup are regressed.
- F03/F04: last active administrator cannot be blocked/deleted in the UI. Existing
  server ACL and last-owner checks remain authoritative for races. Confirmations
  name the person, operation and consequences; access revocation does not promise
  remote erasure of offline data or restoration of old sessions.
- F05: operation messages are independent of background sync, scoped to the user
  or form, and errors use an assertive live region with bring-into-view. A failed
  creation keeps the name. Successful creation refreshes the list; a refresh
  failure retains the confirmed user and invitation rather than repeating POST.
- F06/F07: invitation names its recipient, shows the localised expiry derived from
  the API's expiresIn, explains recipient steps, and retains sensitive clipboard
  marking. Reissuing explicitly warns that the old invitation stops working.
  Certificate comparison remains under a disclosure. The raw invitation stays
  only in the ViewModel; after process death it requires explicit reissue.
  Device pages name the person, explain an empty list and return to that person.
- F08/F09: separate account, own devices, TV pairing and logout tasks. Current
  device is identified only by the API's actual deviceId. Empty legacy import is
  disabled. Logout explains retained versus deleted history/favorites/outbox.
  Settings separate content connections from account/server management. Roles
  are Russian; server summaries use loaded values rather than fabricated success.
- F10: preference options have visible radio markers, RadioButton roles and
  selected-state semantics.

No new API fields are assumed. The users list shows only known name, role and
blocked state, not an invented pending-invitation or device count.

## Reproducible checks

```sh
source .env.local
./gradlew :core:testDebugUnitTest :app-mobile:lintDebug :app-tv:lintDebug \
  :app-mobile:assembleDebug :app-tv:assembleDebug \
  :app-mobile:assembleRelease :app-tv:assembleRelease \
  :app-mobile:assembleDebugAndroidTest :app-tv:assembleDebugAndroidTest
sh server/check.sh
```

Use a dedicated database and emulator. The fixture supports optional
MJWATCH_FIXTURE_PORT / MJWATCH_FIXTURE_CONTROL_PORT; defaults remain 18443/18444.
This run uses 18543/18544 and a separate AVD to avoid the original audit fixture.
Reverse both ports to that emulator and run instrumentation with `-e issueFixture
true`. This flag is required for UserManagementUiTest and UsersTvUiTest; ordinary
CI skips these fixture-specific tests rather than contacting a real server.

Core tests cover last-administrator protection, UTF-8 name limits, actual user and
device fields and friendly API errors. UserManagementUiTest checks duplicate-name
failure across sync, create/copy/refresh/reissue, rejection of the old invitation,
empty devices, recreation/Back, block/delete, own devices, empty import, logout
choices, radio semantics, long names and response/account isolation.

Mobile regression classes: UserManagementUiTest, BufferingUiTest, SettingsUiTest,
IssueFixesUiTest and MetadataFlowTest. TV: UsersTvUiTest, BufferingUiTest and VerticalFlowTest.
The TV buffering test also delays HTTP and verifies retry/first frame, including
that focus is requested only after the playback controls exist.
The TV layout and D-pad are tested on the same isolated API 36 arm64 emulator in
1920×1080 / 240 dpi mode; this is not a physical TV or Android TV system image.
Generated screenshots belong in ignored dist/issues-6-7, not in Git.

TalkBack speech, physical-device networking, invitation delivery to a second
person, remote SSH operations and process-kill restoration are not claimed.

## Result

47 core unit tests passed. All 12 selected mobile UI tests and all 3 selected TV
UI tests passed on the isolated emulator. The long-name/device-navigation test
also passed at fontScale 2.0. Server race/vet, seven packaging tests and syntax
checks passed. Both applications build in debug/release; lint has no errors.
