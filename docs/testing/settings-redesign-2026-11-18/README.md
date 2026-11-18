# Settings redesign — 0.9.13

Built on the parallel 0.9.12 source (`e9efe4d`). Server supply stays at 0.1.8. This change reuses the production TorrServer card, theme tokens, input controls and native TV focus components. No new UI dependency, server route or cache allocation policy was added.

## Flow and visual review

Native captures use the same phone viewport (1080×1920, density 420) before and after. TV captures use the actual TV APK at 1920×1080, density 320, on an isolated phone emulator; they are not a claim of physical TV/OEM testing. Both themes were reviewed. Manual screenshot tests use `-e settingsReview <folder>` and skip by default.

| Step | Surface | Result and preserved controls |
| --- | --- | --- |
| 1 | Settings overview | Titled cards for sources, playback, appearance and application; source-group, search and server icons are distinct. All existing links and exit remain. |
| 2 | Jackett / JacRed | Address, API key and connection check remain primary. Protocol, indexers and local HTTP remain available in a labelled disclosure. Expanded drafts survive collapse and save. |
| 3 | Direct TorrServer | Address/check/local HTTP remain; Basic Auth stays available in its own disclosure. Save, discard, secret separation and playback-stop confirmation handlers are preserved. |
| 4 | Catalogue | Direct token/toggle and managed refresh/account actions remain. Server TMDB configuration, connection check, cache clearing and key removal retain their API calls and confirmations. |
| 5 | Playback, theme and updates | Existing quality/audio/subtitle preferences, theme choices, automatic checking, manual check, download/install/retry and GitHub link remain. TV preference content scrolls. |
| 6 | Account and users | Sync, devices, conflict resolution, legacy import, logout, QR/manual pairing, user creation and invitation actions remain. Forms use the same floating labels/cards. |
| 7 | History and backups | History/database limits, schedule, archive creation/download and history clearing remain. Recommendations/API bounds sit beside numeric inputs. API bounds are explicitly separate from free-resource estimates. |
| 8 | Maintenance and update | Update entry, SSH credentials, restart/cache/password/restore operations remain. The four-step update wizard, pending-operation recovery and confirmation gates are preserved. |

Representative comparisons: [search before](before/settings-search.png) / [after](after-dark/settings-search.png), [retention before](before/server-retention.png) / [after](after-dark/server-retention.png), [overview](after-dark/settings.png), [light theme](after-light/server-retention.png), [TV](tv/settings.png).

The original search screen exposed protocol/indexers as separate ungrouped controls; these now sit together with a clear expansion indicator. The original retention form was one long sequence; history and daily copies now have separate cards, units, input validation and inline advice. An intermediate repeated server icon was corrected across the group and child rows. Recommendation/limit tiles share row height when labels wrap.

Cache wording now explicitly says **per torrent**, and shows the projected torrent count used by the existing conservative budget. This was cross-checked against the pinned [TorrServer cache implementation](https://github.com/YouROK/TorrServer/blob/MatriX.145.2/server/torr/storage/torrstor/cache.go). Dynamic allocation between viewers is not introduced.

## Validation

- Mobile/TV debug APKs, Android test APKs, mobile/TV lint, core/playback JVM tests passed on the combined 0.9.12 source.
- Phone instrumentation: all seven settings/TorrServer checks passed, including expanded-field preservation, save/discard, independent connection pages, retained TorrServer credentials, subtitle preference and measured cache advice.
- Dark/light native screenshot review captured 15 routes per run. The final TV review also passed with the keyboard dismissed for the overview captures.
- TV instrumentation: form width, D-pad focus and Back passed; both update-wizard tests passed (four preparation steps with explicit confirmation, and recovery of a pending operation without starting another one).
- `git diff --check` and release-script syntax passed.

Admin form screenshots are unloaded demo states; no settings were applied over SSH to a live server, no backup was created/downloaded, and no invitation was sent. Wizard tests stop at confirmation. Screenshot checks do not establish complete accessibility compliance. Full production phone/TV CI gates release publication.
