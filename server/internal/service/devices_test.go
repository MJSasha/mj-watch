package service

import (
	"fmt"
	"sync"
	"testing"
)

func TestDeviceDeletionAndInstallationDeduplication(t *testing.T) {
	s, owner := fixture(t)
	first, firstID := sameUserDevice(t, s, owner, "Same model")
	second, secondID := sameUserDevice(t, s, owner, "Same model")
	profile := map[string]string{"type": "phone", "installationId": "installation-11111111"}
	if code, _ := call(t, s, first, "PUT", "devices/self/profile", profile); code != 200 {
		t.Fatal(code)
	}
	if code, _ := call(t, s, second, "PUT", "devices/self/profile", map[string]string{"type": "phone", "installationId": "installation-22222222"}); code != 200 {
		t.Fatal(code)
	}
	newer, newerID := sameUserDevice(t, s, owner, "Same model")
	if code, _ := call(t, s, newer, "PUT", "devices/self/profile", profile); code != 200 {
		t.Fatal(code)
	}
	if code, _ := call(t, s, first, "GET", "me", nil); code != 401 {
		t.Fatal("old session survived", code)
	}
	if code, _ := call(t, s, second, "GET", "me", nil); code != 200 {
		t.Fatal("different installation merged", code)
	}
	var count int
	s.DB.QueryRow("SELECT count(*) FROM devices WHERE id=?", firstID).Scan(&count)
	if count != 0 {
		t.Fatal("duplicate retained")
	}
	// A revoked legacy device must remain deletable, not trapped behind its disabled menu.
	s.DB.Exec("UPDATE devices SET revoked=1 WHERE id=?", newerID)
	if code, _ := call(t, s, owner, "DELETE", "admin/users/"+func() string { i, _ := s.identity(owner); return i.User }()+"/devices/"+newerID, nil); code != 200 {
		t.Fatal(code)
	}
	for _, table := range []string{"devices", "sessions", "device_profiles", "device_installations"} {
		column := "device_id"
		if table == "devices" {
			column = "id"
		}
		if err := s.DB.QueryRow("SELECT count(*) FROM "+table+" WHERE "+column+"=?", newerID).Scan(&count); err != nil || count != 0 {
			t.Fatal(table, count, err)
		}
	}
	if code, _ := call(t, s, owner, "DELETE", "devices/"+secondID, nil); code != 200 {
		t.Fatal(code)
	}
	if code, _ := call(t, s, second, "GET", "me", nil); code != 401 {
		t.Fatal("deleted device still authenticated")
	}
	// Account isolation for explicit deletion and identical installation IDs.
	_, invite := call(t, s, owner, "POST", "admin/users", map[string]string{"name": "viewer"})
	_, user := call(t, s, "", "POST", "auth/accept-invite", map[string]string{"invite": invite["invite"].(string), "password": "correct-horse-battery", "device": "Same model"})
	token := user["accessToken"].(string)
	if code, _ := call(t, s, token, "DELETE", "devices/"+func() string { i, _ := s.identity(owner); return i.Device }(), nil); code != 404 {
		t.Fatal("cross-account delete", code)
	}
	if code, _ := call(t, s, token, "PUT", "devices/self/profile", profile); code != 200 {
		t.Fatal(code)
	}
	if code, _ := call(t, s, owner, "GET", "me", nil); code != 200 {
		t.Fatal("cross-account dedup", code)
	}
}

func TestDeviceAppVersionVisibleToAdminAndPreservedForLegacyProfile(t *testing.T) {
	s, owner := fixture(t)
	status, me := call(t, s, owner, "GET", "me", nil)
	if status != 200 || me["deviceAppVersion"] != true {
		t.Fatalf("missing version reporting capability: %d %v", status, me)
	}
	_, invite := call(t, s, owner, "POST", "admin/users", map[string]string{"name": "viewer"})
	_, login := call(t, s, "", "POST", "auth/accept-invite", map[string]string{
		"invite": invite["invite"].(string), "password": "correct-horse-battery", "device": "Phone",
	})
	viewer := login["accessToken"].(string)
	_, me = call(t, s, viewer, "GET", "me", nil)
	path := "admin/users/" + me["userId"].(string) + "/devices"
	device := func(token, path string) map[string]any {
		t.Helper()
		code, response := call(t, s, token, "GET", path, nil)
		if code != 200 {
			t.Fatalf("list devices: %d %v", code, response)
		}
		return response["devices"].([]any)[0].(map[string]any)
	}
	if got := device(owner, path); got["appVersionCode"] != nil {
		t.Fatal("unexpected version for legacy device", got)
	}
	for _, profile := range []map[string]any{
		{"type": "phone", "appVersionName": "0.12.43", "appVersionCode": 1264},
		{"type": "phone", "appVersionName": "0.12.44", "appVersionCode": 1265},
		{"type": "phone"},
	} {
		if code, response := call(t, s, viewer, "PUT", "devices/self/profile", profile); code != 200 {
			t.Fatalf("update profile: %d %v", code, response)
		}
	}
	if got := device(owner, path); got["appVersionName"] != "0.12.44" || got["appVersionCode"] != float64(1265) {
		t.Fatal("expected last reported version", got)
	}
	if code, _ := call(t, s, viewer, "GET", path, nil); code != 403 {
		t.Fatal("admin device listing accessible to viewer", code)
	}
	if got := device(viewer, "devices"); got["appVersionCode"] != nil {
		t.Fatal("version exposed outside admin listing", got)
	}
}

func TestConcurrentDeviceRefreshIsAtomic(t *testing.T) {
	s, _ := fixture(t)
	tokens := []string{}
	for range 4 {
		login, err := Bootstrap(s.DB, "owner", "concurrent refresh")
		if err != nil {
			t.Fatal(err)
		}
		tokens = append(tokens, login["refreshToken"].(string))
	}
	start := make(chan struct{})
	failures := make(chan error, len(tokens))
	var wg sync.WaitGroup
	for _, token := range tokens {
		wg.Add(1)
		go func(token string) {
			defer wg.Done()
			<-start
			for range 4 {
				code, result := call(t, s, "", "POST", "auth/refresh", map[string]string{"refreshToken": token})
				if code != 200 {
					failures <- fmt.Errorf("concurrent refresh: %d %v", code, result)
					return
				}
				if code, _ := call(t, s, "", "POST", "auth/refresh", map[string]string{"refreshToken": token}); code != 401 {
					failures <- fmt.Errorf("rotated token accepted: %d", code)
					return
				}
				token = result["refreshToken"].(string)
			}
		}(token)
	}
	close(start)
	wg.Wait()
	close(failures)
	for err := range failures {
		t.Error(err)
	}
}
