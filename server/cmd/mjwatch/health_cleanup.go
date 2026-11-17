package main

import (
	"database/sql"
	"dev.mjwatch/server/internal/service"
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"syscall"
)

var healthOperation = regexp.MustCompile(`^health-[0-9]{9,20}$`)

// Match the old root-only receipts and their actual session, never just a display name.
func cleanupMaintenanceChecks(db *sql.DB, directory string) error {
	entries, err := os.ReadDir(directory)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	for _, entry := range entries {
		name := entry.Name()
		if filepath.Ext(name) != ".bootstrap" || !healthOperation.MatchString(name[:len(name)-len(".bootstrap")]) {
			continue
		}
		info, err := entry.Info()
		if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 || info.Size() > 16384 {
			continue
		}
		owner, ok := info.Sys().(*syscall.Stat_t)
		if !ok || owner.Uid != uint32(os.Geteuid()) {
			continue
		}
		path := filepath.Join(directory, name)
		raw, err := os.ReadFile(path)
		var receipt struct {
			DeviceID     string `json:"deviceId"`
			RefreshToken string `json:"refreshToken"`
		}
		if err != nil || json.Unmarshal(raw, &receipt) != nil || receipt.DeviceID == "" || receipt.RefreshToken == "" {
			continue
		}
		result, err := db.Exec(`DELETE FROM devices WHERE id=? AND name='Maintenance check'
		 AND user_id IN (SELECT id FROM users WHERE role='admin')
		 AND EXISTS (SELECT 1 FROM sessions WHERE device_id=devices.id AND refresh=?)`, receipt.DeviceID, service.Hash(receipt.RefreshToken))
		if err != nil {
			return err
		}
		if count, _ := result.RowsAffected(); count == 1 {
			if err := os.Remove(path); err != nil {
				return err
			}
		}
	}
	return nil
}
