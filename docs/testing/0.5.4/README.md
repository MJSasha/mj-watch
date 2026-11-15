# 0.5.4 — sparse TorrServer settings patch

Root cause: ServerScreens compared every snapshot key against a parsed clone. JSONObject retains identity equality, so unchanged nested objects could be submitted as edits. Server Validate correctly rejected those keys with unsupported_field. The app now iterates only the server-advertised editable field names and normalizes numeric comparisons; the full settings snapshot is not sent as changes. The server continues preserving unknown values in the merged settings payload and enforcing its allowlist.

Confirmed failed operation polling clears its encrypted pending request; interrupted/unknown operations retain it. The generic account/history “Синхронизировано” message is hidden on the TorrServer form. Drafts remain dirty after failure; successful apply still requires server reread and confirmation.

Local validation: 38 core tests (two new regression cases), mobile/TV lint and debug APK builds passed. Go control contract race test passed with a nested unknown-object preservation/rejection fixture. Regression checks cover cloned nested objects and arrays, excluded protected fields, numeric JVM type normalization, and actual cache/preload/boolean changes. An initial test incorrectly expected Android JSONArray to use identity equality; it was corrected to use JSONObject, which demonstrates the original defect. No actual private SSH target was contacted. The full user-server response was not collected, so the precise offending key on that server is not claimed.

After updating the APK, read settings again with “Прочитать по SSH”, change the required value and apply. Previously saved invalid pending requests are not rewritten/replayed silently.
