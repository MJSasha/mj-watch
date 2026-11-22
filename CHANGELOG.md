# Changelog

## 0.12.18

- Постеры заполняют область изображения на карточках истории, подборок и каталога без боковых полос.
- На карточках истории и в разделе «Продолжить» отображается таймкод точки остановки.

## 0.12.17

- При конфликте синхронизации истории приложение сохраняет запись с более свежим временем просмотра. При совпадении времени сохраняется локальная запись.
- Карточки фильмов и сериалов сначала показывают вертикальную обложку, а если её нет — банер.

## 0.12.16

- На телефонах, планшетах и Android TV карточки фильмов и сериалов в подборках, поиске, рекомендациях, фильмографии и списке «Буду смотреть» теперь показывают название поверх крупной обложки.

## 0.12.15

- При переходе между сериями в плеере сохраняется номер сезона, если раздача содержит один определённый сезон. Это позволяет загрузить таймкоды SkipDB и для файлов без номера сезона в названии.
- На Android TV подсветка фокуса и наведения повторяет форму кнопок пропуска и «Следующая серия».

## 0.12.14

- На телефонах и планшетах кнопка скачивания сезона перенесена в строку «Эпизоды» и занимает меньше места.
- В плеере Android TV нажатие «Вверх» со шкалы снова скрывает панели и отменяет неподтверждённую перемотку.

## 0.12.13

- Если штатный декодер AAC сбоит, плеер пробует программное декодирование FFmpeg и продолжает с текущей позиции.
- С экрана ошибки аудио можно выбрать другую дорожку в том же файле или продолжить просмотр без звука.
- Меню озвучки и субтитров применяет выбранную дорожку сразу и открывается без скачка размеров.
- С главной убрана подпись «Каталог из кеша».

## 0.12.12

- После пропуска титров плеер сразу открывает следующую серию из текущего списка файлов, если она есть.
- Кнопки пропуска появляются в нижней части экрана над панелью управления.
- Настройка «Автопропуск через 5 секунд» применяется к точным и скорректированным меткам SkipDB, в том числе в скачанных сериях.

## 0.12.11

- На телефонах и планшетах можно скачать все серии сезона из выбранной раздачи.
- Для новых загрузок сохраняются обложки и доступные таймкоды пропуска по данным SkipDB.
- Без сети приложение открывает экран загрузок с готовыми видео и группирует серии по сезонам.

## 0.12.10

- Плеер показывает метки SkipDB для близких по длительности версий серии. Для скорректированных меток доступен ручной пропуск, а для точных совпадений работает таймер.
- При HTTP 403 плеер сразу показывает кнопку «Повторить». Проверка восстановления и сохранения позиции теперь ждёт фактического запроса к потоку.

## 0.12.9

- На шкале плеера голубые отрезки отмечают опенинг, блок «В предыдущих сериях», титры и анонс следующей серии по данным SkipDB.
- Параметры видео, аудио и субтитры и вложенные меню оформлены в стиле MJ Watch. В строках есть иконки, текущие значения и отметки выбора, а на ТВ виден фокус пульта.

## 0.12.8

- Экран TorrServer показывает подтверждённый режим кэша и свободное место для выбранного хранилища. Обновление перечитывает настройки и лимиты с сервера.

## 0.12.7

- В плеере телефона и ТВ убраны дубли кнопок. Настройки видео собраны в одном меню, выбор качества появляется при наличии нескольких разрешений.
- SkipDB добавляет пропуск опенинга, блока «В предыдущих сериях», титров и анонса. Можно перемотать сразу или включить автопропуск через 5 секунд. Для управляемого режима нужен сервер 0.1.12.
- Удаление из истории сбрасывает сохранённые позиции просмотра. Кнопка «Отменить» восстанавливает их.

## 0.12.6

- Плеер телефона, планшета и Android TV сохраняет последний кадр при подгрузке и восстановлении видео.

## 0.12.5

- В карточках фильмов и сериалов из TMDB появилась вкладка «Актёры» с фотографиями и ролями.
- Нажатие на актёра открывает фильмографию. Из списка можно перейти к карточке фильма или сериала.
- Для вкладки на подключённом сервере MJWatch нужна серверная версия 0.1.11.

## 0.12.4

- ТВ быстрее открывает большие раздачи. Списки файлов и серий создают строки по мере прокрутки, а длинная очередь плеера хранится в кэше.
- Поиск и фильтры обрабатывают раздачи в фоне. Экраны показывают, что загружается, а сброс фильтров возвращает список к началу.
- Плеер запоминает выбранную озвучку раздачи при смене серии и повторном открытии. Пульт возвращает фокус к кнопке после закрытия меню дорожек.
- Сокращена заставка запуска на ТВ и уменьшена работа при построении карточек истории.

## 0.12.3

- Плавная подсветка названия и индикатор буфера на заставке.
- Скорость потока и число сидов в строке загрузки.
- Изолированный запуск инструментальных тестов плеера.

## 0.12.2

- Полноэкранная заставка фильмов и серий с логотипами TMDB и статусом подключения внизу.
- Параллельная подготовка подписанной поставки и проверка перед публикацией релиза.

## 0.12.1

- Animate screen navigation, bottom navigation, artwork, and list changes on phones, tablets, and Android TV.
- Keep the title screen and its selected tab in place while the mobile release sheet is open.
- Fade player controls and their backgrounds in and out while preserving touch and D-pad behavior.

## 0.12.0

- Move a managed server to a fresh host over SSH while preserving user sessions, history, and settings.
- Phones, tablets, and Android TV switch to the verified new address automatically.

## 0.11.3

- Continue offline downloads in the background and resume partial files after temporary connection failures.
- Show download progress, speed and an estimated time in the notification. Android 16 uses the native progress style.

## 0.11.2

- Prefer the downloaded copy from a movie detail page and resume its saved position.
- Restore standard D-pad focus styling on the primary action in Android TV title details.

## 0.11.1

- Expose the next playable episode to Google TV media controls, including when a season is started from a phone.

## 0.11.0

- Show artwork, episode and watch progress together on continue-watching cards on phones, tablets and Android TV.
- Browse film and series details with a cinematic header, dedicated source tab and a vertical episode grid. Completed episodes have a checked number badge and a watched label.

## 0.10.1

- Open downloads within the mobile library tabs. Keep playback and device downloads separate, and choose the playback destination when a TV is connected. Show release quality on the source instead of repeating it on action buttons.

## 0.10.0

- Download a film or individual episodes to a phone or tablet, then open the saved files from Downloads without connecting to the server. Track progress, retry interrupted downloads and remove local copies from the same screen.

## 0.9.13

- Unify phone and TV settings with the TorrServer card style: connections, playback preferences, catalogue, updates, accounts, history/backups and maintenance. Keep existing actions, save/discard guards, credentials and update confirmations.
- Put advanced options in labelled expandable cards, add inline recommendations and API bounds for numeric settings, and clarify cache size per torrent with the projected torrent count.
- Use distinct icons for setting groups and their actions, retain matching tile heights, and make TV preference panes scrollable.

## 0.9.12

- Match Android TV home to the approved prototype: fixed categories, compact shelf gaps, a poster-only focus frame, measured shelf centring and smooth movement of previous rows beyond the viewport. Remove the featured title/actions strip and OK hint.
- Restore each shelf’s last focused card, including after opening details; return Up to the selected category. Show season/episode over continue-watching previews, tighten TV poster captions and hide the cache-status label.
- Keep TV action gaps compact and focus outlines inside clipped controls. Place the player timeline between current time and duration on both TV and mobile. Mobile home layout remains unchanged.
- Preserve server 0.1.8, resource/viewing-limit settings and the existing APK update signature.

## 0.9.11

- Bundle server 0.1.8 and let owners configure global and per-account viewing limits with RAM-based automatic defaults, immediate application and preservation of active views.
- Show a readable stream-capacity error and wait for an explicit retry instead of repeating rejected stream requests.
- Organize TorrServer settings into cache, network and advanced cards with inline, tappable recommendations and RAM/disk budget estimates beside key inputs.

## 0.9.10

- Add a memory-aware client video buffer with recommended/maximum targets and settings for the next playback. Keep the existing playback retry policy.
- Bundle server 0.1.7 with bounded diagnostic journals, resource snapshots, automatic 1/2/4-view profiles, cache RAM recommendations accounting for neighbours and cached torrents, and checked cache increases.
- Reduce device-presence writes and duplicate sync requests; back off failed sync and wake it on network restoration. Index sync queries and expire ephemeral data while preserving replay/history records.

## 0.9.8

- Keep TV carousel focus on a stable leading line and restore the last focused card when re-entering a row. Preserve the featured title when returning from details.
- Reserve space for TV focus borders inside scrolling layouts, draw custom TV row outlines inside their bounds, and convert scroll clearance from dp to physical pixels.
- Add remote-navigation and viewport-edge focus regressions.

## 0.9.4

- Delete account devices and their sessions completely, including revoked devices. Deduplicate repeated sign-ins by account-scoped installation identity, preserving distinct devices with matching names; bundle server 0.1.6 (#36). Rotate session tokens atomically during concurrent stream/device writes.
- Align continue-watching cards, stretch posters to the text height, simplify the history dialog and account screen, normalize phone/TV launch buttons, and make the home server shortcut show live connection state and open server settings. Reduce player scrims (#37).
- Explicitly select Matroska for MKV paths, including extensionless managed stream URLs; add real H.264/AAC MKV first-frame, audio and seek coverage (#38).
- Rewrite the README around features and visible screenshots; add the repository description (#41).
- Animate double-tap seeking on the touched side with a fading ripple and directional arrows, retaining seek bounds and hidden controls (#42).

## 0.9.2

- Restore starting new videos and resuming saved playback on phones/tablets independently of a paired or unavailable TV. Keep local and TV launch actions separate.
- Restore local file selection and Media3 launch, preserve remote target fencing, and keep mobile launch actions visible below the scrolling source list.
- Preserve existing progress storage and server 0.1.5. Add regressions for local playback after TV pairing, unavailable receivers, explicit TV routing and saved-position resume.

## 0.9.1

- Apply the approved native Android TV player design: soft gradients, a thin lime timeline, compact light icons and a single inset focus outline, without visible action labels or tooltips (#32).
- Preserve accessible action names and 48 dp targets; keep −10/+10 readable at 200% font scaling and retain native dialog/status focus feedback.
- Preserve D-pad/OK/Back, seek preview, six-second timeout, queue/skip/next, MediaSession and managed playback behavior. Mobile controls and server 0.1.5 remain unchanged.
- Include isolated real-video 720p/native1080p, large-font and focus evidence; physical TV/OEM and cloud four-device acceptance are not claimed.

## 0.9.0

- Add phone/tablet → authorized Android TV playback through the managed server, with one selected target, independent TVs, receiver-confirmed state, same-file joining and confirmed replacement. Playback stays on the TV when the controller disconnects.
- Publish Android system play/pause/seek and remote TV volume from a foreground media session; retain independent TV playback and physical remote controls. No in-app remote transport.
- Unify account devices with type icons and server-confirmed Unicode aliases, editable by ordinary users through ⋮ and a compact dialog. Preserve existing pairing/scanning.
- Keep independent cast/bookmark buttons, gate all phone launch/resume paths on a live target, and show connection loss/retry inside the film card. Restore TalkBack focus to the cast button after closing the chooser.
- Bundle server 0.1.5 with account-isolated remote sessions, target/session fencing, idempotent commands and revocation checks. Add a reproducible four-emulator integration workflow; its complete local run validates the managed path.
- Compatibility: remote playback requires managed mode and the foreground MJWatch TV app. Direct remote pairing and automatic Google Cast/LAN notifications are not implemented; issue #26 remains partially open.

## 0.8.16

- Replace the startup pulse with a cinematic MJ logo entrance, orbiting light and a staged wordmark reveal on phone, tablet and TV.
- Expand and dissolve the emblem into the ready screen with a short fade and depth transition. Wait for initialization, respect system animation duration settings, and retain the once-per-launch behavior across navigation and recreation.

## 0.8.14

- Recover transient player HTTP/network failures with bounded backoff, reset the retry budget after stable playback, refresh managed sessions and reprepare missing torrents. Retry rebuilds the stream for the exact original file while preserving position and pause state (#25).

- Authenticate explicitly during the final server-installation check before the account scope exists; retain refreshed access/refresh credentials when saving the verified connection. Fixes HTTP 401 after successful SSH installation, including expired bootstrap sessions.

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
