package main

import (
	"dev.mjwatch/server/internal/service"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestMaintenanceCleanupRequiresTheMatchingRootReceiptAndSession(t *testing.T) {
	directory := t.TempDir()
	db, err := service.Open(filepath.Join(directory, "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	write := func(name string, out map[string]any) {
		t.Helper()
		raw, _ := json.Marshal(out)
		if err := os.WriteFile(filepath.Join(directory, name), raw, 0600); err != nil {
			t.Fatal(err)
		}
	}
	old, err := service.Bootstrap(db, "owner", "Maintenance check")
	if err != nil {
		t.Fatal(err)
	}
	write("health-1700000000000000001.bootstrap", old)
	phone, _ := service.Bootstrap(db, "owner", "My phone")
	write("health-1700000000000000002.bootstrap", phone)
	sameName, _ := service.Bootstrap(db, "owner", "Maintenance check")
	write("normal-operation.bootstrap", sameName)
	wrongSession, _ := service.Bootstrap(db, "owner", "Maintenance check")
	wrongSession["refreshToken"] = "wrong token"
	write("health-1700000000000000003.bootstrap", wrongSession)
	linkTarget := filepath.Join(directory, "normal-operation.bootstrap")
	os.Symlink(linkTarget, filepath.Join(directory, "health-1700000000000000004.bootstrap"))
	for n := 0; n < 2; n++ {
		if err := cleanupMaintenanceChecks(db, directory); err != nil {
			t.Fatal(err)
		}
	}
	var devices, sessions int
	db.QueryRow("SELECT COUNT(*) FROM devices").Scan(&devices)
	db.QueryRow("SELECT COUNT(*) FROM sessions").Scan(&sessions)
	if devices != 3 || sessions != 3 {
		t.Fatal("wrong cleanup", devices, sessions)
	}
	if _, err := os.Stat(filepath.Join(directory, "health-1700000000000000001.bootstrap")); !os.IsNotExist(err) {
		t.Fatal("old secret receipt left behind", err)
	}
	var ownerSessions int
	db.QueryRow("SELECT COUNT(*) FROM sessions WHERE refresh=?", service.Hash(phone["refreshToken"].(string))).Scan(&ownerSessions)
	if ownerSessions != 1 {
		t.Fatal("real device was removed")
	}
}
