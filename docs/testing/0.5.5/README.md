# 0.5.5 — simple invitations and explicit private-CA trust

Owner flow: copy invitation with a clear 24-hour/one-use explanation and copy feedback, without a raw token occupying the page. The server fingerprint is available for independent comparison; Android clipboard payload is marked sensitive. Recipient flow: copy received invitation, press “Вставить приглашение”, confirm the origin and CA fingerprint, choose a new password, create the account. Address/port/token/CA are decoded together. Manual fields remain available under a disclosure.

The former manual CA button silently swallowed endpoint errors and cleared the CA text even if saving failed. It now validates HTTPS/origin first. PEM escaped newlines are normalized (including trailing escaped newline). Decode validates bounded input, HTTPS/no userinfo/query/fragment, token shape and X509 CA before displaying a trust prompt. Import never writes trust automatically or makes a network request; the old password is cleared. Origin-bound trust, hostname verification and certificate-chain validation remain intact. No single-use token, private key or user credential is included in this report; the PEM fixtures are disposable public test CAs and only ship with test artifacts.

Verification:

- 40 core tests passed, including valid copied packet/PEM normalization and invalid/insecure URL, broken CA and input-size cases.
- Mobile/TV debug APK builds and lint passed.
- Dedicated MJWatch_Phone_36 / emulator-5582: InvitationUiTest passed against a public fixture CA. It copies a complete packet, confirms no trust before the explicit prompt, confirms storage for :8443 but not :443, and checks that a password enables “Создать аккаунт и войти” while the regular login is hidden. No network request or actual invitation consumption occurs. [Screenshot](invitation-ready.png).
- Initial JVM test attempted AndroidKeyStore access, which Robolectric does not supply; that assertion now belongs to the actual Android test. A trailing escaped newline exposed a normalization issue, which was corrected and retested.

No private server was contacted. The user's exact TLS failure is not diagnosed from the generic message alone: wrong/untrusted CA, address/certificate mismatch and other TLS issues remain distinguishable only with actual runtime evidence. Full real-server account creation and a dedicated TV invitation-input scenario remain unverified in this change.
