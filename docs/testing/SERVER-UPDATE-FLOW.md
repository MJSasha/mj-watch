# Server update wizard (0.8.1)

Open **Настройки → Мой сервер → Обслуживание → Обновить сервер** as the owner.

1. Choose the bundled package (normal path) or import a signed ZIP. Selection explicitly confirms the version and does not contact or change the server.
2. Enter or unlock root SSH credentials. Explain that these come from the hosting provider and differ from the MJWatch account password. Address is prefilled; port is optional.
3. Choose and repeat a backup phrase (16–1024 characters); explicitly confirm saving it. Explain the encrypted pre-update backup and that this does not change SSH or account passwords.
4. Review server, version, backup and interruption warning. A separate confirmation starts the operation.

The app checks live admin authorization and SSH server identity before updating. Real client stages are access checks, package upload, then a combined server operation (backup/install/health). Old server helpers do not report finer progress, so the UI never pretends those individual stages have completed. Success requires a verified receipt.

Only operation ID, target version, host and SSH port are stored for recovery. Passwords and the backup phrase are in memory only. Recreating the preparation form returns to credential entry if credentials were lost. After an operation might have started, a subsequent visit only offers checking that same operation; it never silently starts another one. The last update ID from the old maintenance form is migrated too. Read-only receipt checking uses root SSH and server identity, so it remains possible if the HTTP service is down.

`update_failed_binary_rollback_confirmed` reports a confirmed rollback. `health_failed` explicitly says rollback is unconfirmed and advises checking server state/logs before retrying. Copyable diagnostics contain only the safe error code, operation ID and version. This change does not diagnose or fix an individual production server's health failure.

Other maintenance operations remain available under **Другие операции**.

## Checks

- `:core:testDebugUnitTest`: health error copy distinguishes confirmed rollback from an unknown outcome.
- `ServerUpdateFlowTest` (phone and TV): native entry point, package-selection feedback, required SSH fields, phrase mismatch, explicit saved-phrase check, confirmation cancel without creating an operation, back navigation; migration of a previous operation, check-only recovery without a backup phrase and password clearing on activity recreation.
- Debug builds and lint for both APKs. UI tests use synthetic credentials and do not connect to a production server or run an update.
