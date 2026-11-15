# 0.5.2 — capability-based installer admission

Removed the Debian 12 / Ubuntu 24.04 VERSION_ID allowlist. Exact distribution IDs `debian` and `ubuntu` are accepted independent of version if Python >=3.9, active systemd, apt-get/systemctl/useradd/id/chown and amd64/arm64 are present. Python 3.9 is needed by Path.is_relative_to used by supply validation. Existing root, memory/disk, signed supply, ownership conflicts, CA, authentication and firewall safeguards remain enforced.

Pure preflight fixtures accept Ubuntu 20.04 (with a sufficiently new Python), 22.04, 24.04, 25.10, 26.04, unknown future version strings; Debian 11/12/13/testing; amd64 and arm64. They reject old Python, unsupported architecture, missing systemd or apt and derivative distribution IDs even with ID_LIKE=debian/ubuntu. These are admission checks, not full OS installation certification.

`python3 server/packaging/test_install_status.py`: six tests passed (three preflight tests with subcases plus three real status/error-handler checks). `sh server/check.sh`: Go race tests, vet, Python tests/syntax and shell syntax passed. Local signed supply regenerated from pinned binary inputs with a disposable local-only identity; publication uses the existing CI signing secret.

No user server was contacted. Actual full installation evidence remains the earlier Debian 12 arm64 run. Ubuntu 26.04 full installation remains unverified; package repositories, services and protected HTTPS startup can still return stage-specific errors. Updating the APK alone does not change a previously uploaded script: use “Повторить установку” / “Установить автоматически” to upload the new signed supply, rather than merely re-reading the previous failed status.

Local Android verification: 36 core tests passed, mobile/TV lint and both debug APK builds passed. This change modifies preflight and error text; no new local Android runtime run was performed. CI will run the existing phone/TV runtime suites before publishing.
