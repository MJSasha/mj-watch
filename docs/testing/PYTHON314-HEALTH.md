# health_failed on Python 3.13+ with a legacy MJWatch CA

Confirmed symptom: `SSLCertVerificationError: CA cert does not include key usage extension`.

Older installations created a CA without explicit `keyUsage`; Python 3.13+ enables strict X.509 profile validation in `create_default_context`. The update can therefore fail its HTTPS check and the rollback check even when both systemd services are running.

Server 0.1.3 uses an explicit private-CA `PROTOCOL_TLS_CLIENT` context for maintenance, with chain/expiry/hostname verification required and TLS 1.2 minimum. Only `/etc/mjwatch/ca.crt` is trusted. It does not use `CERT_NONE`, disable hostname verification or rotate the CA. Newly created CAs explicitly include critical `keyCertSign,cRLSign` and `CA:TRUE` extensions and pass strict verification.

## Existing server recovery

Updating the APK alone cannot repair the already installed old updater. From a root console, download `server/packaging/fix-python314-health.py` from the reviewed release commit (use the commit hash in the GitHub release) to `/tmp/mjwatch-fix.py`, then run `python3 /tmp/mjwatch-fix.py`.

The script refuses to run during another installer/maintenance operation. It first authenticates an HTTPS request using the installation's CA and requires the unauthenticated `/api/v1/me` response to be HTTP 401. It sends no passwords or session tokens. It then patches only the known old TLS-context expression in the installed root-owned maintenance helper, saves a `.before-python314` backup, preserves file mode and replaces the helper atomically. Unknown contents are rejected; repeated execution is safe. CA certificates, keys, databases, user accounts and settings are not modified.

After `Проверка HTTPS пройдена. Исправление установлено.`, repeat the server update. In 0.8.2 choose the bundled **0.1.3** package in **Настройки → Мой сервер → Обслуживание → Обновить сервер**. If the wizard restores a prior failed operation, use **Проверить результат обновления** first and copy its diagnostics. After the repair, select **Причина ошибки исправлена — подготовить обновление заново** and explicitly confirm returning to preparation. No update is started by this action. Do not retry before fixing an unknown failure.

Checks: `server/check.sh` runs real TLS tests reproducing strict legacy-CA rejection, successful authenticated legacy-CA transport, rejection of wrong CA/hostname/expired certificates, strict new-CA validation and atomic/idempotent repair behavior. Python tests can also run directly with `python3 server/packaging/test_health_tls.py`.
