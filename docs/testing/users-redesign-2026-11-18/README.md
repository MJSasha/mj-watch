# Users and settings layout — local verification, 2026-11-18

Base: published 0.9.13 (`1aa876b5f12f9a938504cdbc7300fabbec998dc1`). Published as [0.9.14 / build 1140](https://github.com/MJSasha/mj-watch/releases/tag/test-140), marked Latest. [CI](https://github.com/MJSasha/mj-watch/actions/runs/44404571366) passed build, phone runtime, TV runtime and publication. Downloaded APKs, update manifest and server supply were verified after publication: [verification report](release-verification.json). APK signing identity and server signing key are unchanged; both APKs are non-debuggable.

## Changes

- User list: initials, compact role/status, count, primary creation action and labelled refresh control.
- User profile: full name, access status, grouped invitation/device navigation and compact access actions with distinct icons.
- Invitation: clear expiry, share/copy actions, separate renewal/revocation section and collapsible server verification details.
- Devices: type icons, activity/status and contextual deletion; intentional empty-state card.
- Existing controllers, API calls, confirmation wording, sensitive clipboard flag and last-administrator protections retained. Full target names remain in destructive action accessibility descriptions and confirmation dialogs.
- Settings playback rows now share one group. This removes repeated parent spacing around dividers. Mobile row padding is 8 dp vertically instead of 16 dp; the minimum inner row remains 48 dp. TV row padding remains unchanged.

## Verification

Built mobile/TV debug apps and instrumentation APKs; both `lintDebug` tasks passed (0 errors; mobile 29 warnings, TV 18 warnings). `git diff --check` passed.

Native Android emulator, 1080×1920 at density 420:

- `UserManagementUiTest`: 5/5 passed, including opt-in clean screenshot review. Existing scenarios cover last-administrator protection, duplicate-name error persistence, copying invitations, token replacement with old-token rejection, device navigation, block/delete confirmations, long names, response isolation and clearing account-scoped data on logout.
- Light-theme screenshot review: 1/1 passed.
- Long-name/device navigation at system font scale 1.3: 1/1 passed; font scale restored afterward.
- `SettingsUiTest`: 3/3 passed (connection pages, discard/save behavior, persisted preferences and long content).
- `SettingsReviewTest`: 1/1 passed; includes playback rows after compacting.

Actual TV app at 1920×1080, density 320 on the same emulator:

- `UsersTvUiTest`: 1/1 passed. D-pad center opens the selected user beside the list, last administrator controls stay disabled, devices open and Back preserves context.

User scenarios used a real MJWatch Go service with an isolated database and fixture media/TorrServer, bound only to loopback ports 18543/18544. All accounts shown are synthetic. No production server or accounts were changed. Sharing to a recipient and physical-TV rendering were not tested; screenshots do not establish a full accessibility audit.

## Screenshots

- `before/`: clean baseline list, profile, invitation, empty devices on the old UI.
- `dark/`, `light/`: corresponding final native screens. User counts/names differ because scenario fixtures create synthetic accounts.
- `settings-playback.png`: compact playback preferences.
- `large-text-user-devices.png`: long name at font scale 1.3.
- `tv-user-profile.png`, `tv-user-devices.png`: final TV list/detail and devices.
