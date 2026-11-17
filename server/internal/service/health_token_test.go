package service

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestMaintenanceCredentialIsShortLivedReadOnlyAndCreatesNoDevices(t *testing.T) {
	s, owner := fixture(t)
	torr := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200) }))
	defer torr.Close()
	s.Config.TorrURL = torr.URL
	var before int
	s.DB.QueryRow("SELECT COUNT(*) FROM devices").Scan(&before)
	var previous string
	for n := 0; n < 5; n++ {
		out, err := MaintenanceToken(s.DB)
		if err != nil {
			t.Fatal(err)
		}
		token := out["accessToken"].(string)
		if code, status := call(t, s, token, "GET", "admin/status", nil); code != 200 || status["databaseHealthy"] != true || status["torrserverHealthy"] != true {
			t.Fatal(code, status)
		}
		for _, target := range []struct{ method, path string }{{"GET", "devices"}, {"GET", "admin/users"}, {"POST", "admin/probe"}, {"POST", "auth/refresh"}} {
			if code, _ := call(t, s, token, target.method, target.path, nil); code < 400 {
				t.Fatal("credential escaped status scope", target, code)
			}
		}
		if previous != "" {
			if code, _ := call(t, s, previous, "GET", "admin/status", nil); code != 401 {
				t.Fatal("old token still active", code)
			}
		}
		previous = token
	}
	var after int
	s.DB.QueryRow("SELECT COUNT(*) FROM devices").Scan(&after)
	if after != before {
		t.Fatal("maintenance created devices", before, after)
	}
	var sessions int
	s.DB.QueryRow("SELECT COUNT(*) FROM sessions").Scan(&sessions)
	if sessions != 1 {
		t.Fatal("maintenance created sessions", sessions)
	}
	expired, _ := json.Marshal(healthCredential{Hash(previous), 0})
	s.DB.Exec("UPDATE settings SET value=? WHERE key='maintenance_access'", string(expired))
	if code, _ := call(t, s, previous, "GET", "admin/status", nil); code != 401 {
		t.Fatal("expired token accepted", code)
	}
	if code, _ := call(t, s, owner, "GET", "me", nil); code != 200 {
		t.Fatal("owner session changed", code)
	}
}
