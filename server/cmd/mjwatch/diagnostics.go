package main

import (
	"bytes"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
)

const diagnosticJournalConfig = "[Journal]\nStorage=persistent\nSystemMaxUse=16M\nSystemMaxFileSize=2M\nRuntimeMaxUse=8M\nRuntimeMaxFileSize=1M\nMaxRetentionSec=7day\nForwardToSyslog=no\n"

const diagnosticUnitConfig = "[Service]\nStandardOutput=journal\nStandardError=journal\nLogNamespace=mjwatch\nLogRateLimitIntervalSec=30s\nLogRateLimitBurst=100\n"

// A pre-update worker still runs its old maintenance.py. Its first root health
// probe invokes the new binary, which can migrate the installed units once.
// A shared native journal namespace bounds both services without changing neighbours.
func configureDiagnostics(unitDir string, run func(...string) error) (result error) {
	stamp := filepath.Join(unitDir, "mjwatch.service.d", ".diagnostics-applied")
	files := map[string]string{
		filepath.Join(filepath.Dir(unitDir), "journald@mjwatch.conf.d", "20-mjwatch-retention.conf"): diagnosticJournalConfig,
		filepath.Join(unitDir, "mjwatch.service.d", "20-mjwatch-diagnostics.conf"):                   diagnosticUnitConfig,
		filepath.Join(unitDir, "mjwatch-torrserver.service.d", "20-mjwatch-diagnostics.conf"):        diagnosticUnitConfig,
	}
	previous := map[string][]byte{}
	for path, desired := range files {
		raw, err := os.ReadFile(path)
		if err != nil && !os.IsNotExist(err) {
			return err
		}
		if !bytes.Equal(raw, []byte(desired)) {
			previous[path] = raw
		}
	}
	if len(previous) == 0 {
		if applied, err := os.ReadFile(stamp); err == nil && bytes.Equal(applied, []byte(diagnosticUnitConfig+diagnosticJournalConfig)) {
			return nil
		}
	}
	if err := os.Remove(stamp); err != nil && !os.IsNotExist(err) {
		return err
	}
	defer func() {
		if result == nil {
			return
		}
		// A failed logging migration must not leave the binary rollback using broken units.
		for path, raw := range previous {
			var err error
			if raw == nil {
				err = os.Remove(path)
				if os.IsNotExist(err) {
					err = nil
				}
			} else {
				err = os.WriteFile(path, raw, 0644)
			}
			if err != nil {
				log.Print("event=diagnostics_rollback_file_failed")
			}
		}
		if err := run("daemon-reload"); err != nil {
			log.Print("event=diagnostics_rollback_reload_failed")
		}
		if err := run("try-restart", "mjwatch-torrserver.service", "mjwatch.service"); err != nil {
			log.Print("event=diagnostics_rollback_restart_failed")
		}
	}()
	for path := range previous {
		if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
			return err
		}
		if err := os.WriteFile(path+".tmp", []byte(files[path]), 0644); err != nil {
			return err
		}
		if err := os.Rename(path+".tmp", path); err != nil {
			return err
		}
	}
	if err := run("daemon-reload"); err != nil {
		return err
	}
	if err := run("try-restart", "systemd-journald@mjwatch.service"); err != nil {
		return err
	}
	// Stdio changes require a restart during the already-confirmed maintenance operation.
	if err := run("try-restart", "mjwatch-torrserver.service", "mjwatch.service"); err != nil {
		return err
	}
	return os.WriteFile(stamp, []byte(diagnosticUnitConfig+diagnosticJournalConfig), 0600)
}

func installedDiagnostics(config string) error {
	if config != "/etc/mjwatch/config.json" {
		return nil
	}
	if _, err := os.Stat("/var/lib/mjwatch-install/owned"); os.IsNotExist(err) {
		return nil
	} else if err != nil {
		return err
	}
	err := configureDiagnostics("/etc/systemd/system", func(args ...string) error {
		if err := exec.Command("systemctl", args...).Run(); err != nil {
			return fmt.Errorf("diagnostics_unit_reload_failed")
		}
		return nil
	})
	if err != nil {
		log.Print("event=diagnostics_migration_failed")
	}
	return err
}
