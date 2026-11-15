# Managed API v1 and data contract

All production routes are below HTTPS `/api/v1/`; redirects are disabled. JSON control bodies/responses are bounded. The app requires API 1 and `minClientApiVersion <= 1`. `/me`, `/config` and `/capabilities` advertise independent server/API/schema versions. Root `mjwatch version` and signed manifest use the same release version. No public remote-shell or TorrServer-settings endpoint exists.

| Routes | Access / behavior |
|---|---|
| `auth/login`, `auth/accept-invite`, `auth/refresh` | Rate-limited; single-use invite; rotating opaque access/refresh tokens; Argon2id password hashes |
| `me`, `auth/logout`, `devices[/id]` | Current identity; revoke own devices; tokens stored hashed server-side |
| `pairing`, `pairing/{id}`, `pairing/lookup`, `pairing/approve` | TV code + separate polling secret; expiration; explicit account/device preview |
| `snapshot`, `sync`, `mutations` | Per-user only; stable paginated snapshot watermark; revisions/cursors/idempotence |
| `history` DELETE | History epoch increment + tombstones; favorites remain separate |
| `playback`, `playback/{id}`, `playback/{id}/stream?file=N` | Owned opaque handle; magnet or bounded torrent upload; fixed loopback upstream, actual server file ID; Range/If-Range/HEAD; bounded streaming buffers |
| `metadata/home`, `metadata/search`, movie/TV/season/episode details, `metadata/images/...` | Authorized fixed-origin TMDB proxy, no client credentials; images retain authorization |
| `admin/status`, `admin/probe`, `admin/audit`, users/invites/devices | Admin ACL checked server-side; root string on device grants nothing |
| `admin/tmdb`, `/check`, `/credential`, `/cache` | HTTPS admin only; validate before rotation; no credential readback |
| `admin/settings`, `admin/backups` | Retention/schedule and encrypted backups; download reauthentication |

SQLite schema 1 contains users, devices, sessions, invites, pairing, entities, changes, mutation receipts, playback/request/job ownership, settings, bounded metadata cache, audit and materialized snapshot rows. Foreign keys and busy timeout apply per connection, WAL uses a local disk. Schema versions above the supported version are refused.

Entities are `opening`, `progress`, `favorite`, `preferences`; keys are scoped by authenticated user. Progress identity is infoHash + normalized full path + length unless explicit metadata correspondence exists. A torrent file ID is an API selector, not a global content identity. DTOs reject release URLs/passkeys and nested arbitrary payloads. Server assigns revisions and timestamps. Device clocks and maximum playback position never select the winner. Mutations carry UUID, base revision, server/history epoch, optional playback-session sequence. Duplicate IDs return durable receipts; stale revisions yield explicit conflicts. Deletes use tombstones and intentional restore requires a new mutation. Snapshot invalidates old epoch outbox into explicit conflict resolution; old offline progress cannot silently resurrect cleared history.

Android Room schema 3 migrates 1→2→3 without resetting old records. Cache/outbox/cursor/token keys include serverId:userId. Legacy local history stays until an explicit keep/delete choice; managed import is separate and explicit. Direct openings/progress never write persistent history or queue sync. Favorites remain available separately. Config export schema 2 accepts schema 1 and excludes account IDs, tokens, SSH, CA and TMDB secrets.

Control requests have finite deadlines; media has no total call timeout. A continuing stream checks session/device/user validity every five seconds and survives access-token rotation of its session. New streams need a valid access token. Client refreshes sessions before playback and periodically while active. Limits: two streams per user/four global; bounded concurrent playback preparation/uploads; paginated sync; bounded cache/body sizes. These are protective limits, not throughput benchmarks.
