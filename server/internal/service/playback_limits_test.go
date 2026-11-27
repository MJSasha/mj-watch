package service

import (
	"bytes"
	"encoding/json"
	"net/http/httptest"
	"testing"
)

func TestStreamSettingsPersistAndDoNotInterruptExistingViews(t *testing.T) {
	s, token := fixture(t)
	code, snapshot := call(t, s, token, "GET", "admin/playback/settings", nil)
	if code != 200 {
		t.Fatal(code, snapshot)
	}
	update := func(total, perUser int, revision string) int {
		raw, _ := json.Marshal(StreamSettings{total, perUser})
		r := requestForTest("PATCH", "/api/v1/admin/playback/settings", bytes.NewReader(raw), token)
		r.Header.Set("If-Match", revision)
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		return w.Code
	}
	revision := snapshot["revision"].(string)
	if code = update(2, 2, revision); code != 200 {
		t.Fatal(code)
	}
	if !admitTestStream(s, "viewer", "one") || !admitTestStream(s, "viewer", "two") {
		t.Fatal("configured second view rejected")
	}
	if admitTestStream(s, "viewer", "three") {
		t.Fatal("account/global limit bypassed")
	}
	if update(3, 3, revision) != 409 {
		t.Fatal("stale change accepted")
	}
	_, snapshot = call(t, s, token, "GET", "admin/playback/settings", nil)
	if update(9, 1, snapshot["revision"].(string)) != 400 {
		t.Fatal("unsupported limit accepted")
	}
	if update(1, 2, snapshot["revision"].(string)) != 400 {
		t.Fatal("per-account limit above total accepted")
	}
	restarted := New(s.DB, s.Config)
	if restarted.maxStreams != 2 || restarted.maxStreamsPerUser != 2 {
		t.Fatal("settings lost after restart")
	}
	if update(1, 1, snapshot["revision"].(string)) != 200 {
		t.Fatal("could not lower limit")
	}
	if len(s.streams) != 2 || admitTestStream(s, "another", "new") {
		t.Fatal("lowering interrupted views or admitted a new one")
	}
	releaseTestStream(s, "one")
	releaseTestStream(s, "two")
	if !admitTestStream(s, "viewer", "new") {
		t.Fatal("released slot unavailable")
	}
	releaseTestStream(s, "new")
	_, snapshot = call(t, s, token, "GET", "admin/playback/settings", nil)
	if update(0, 0, snapshot["revision"].(string)) != 200 || s.maxStreams != s.recommendedStreams {
		t.Fatal("automatic profile not restored")
	}
	i, _ := s.identity(token)
	s.DB.Exec("UPDATE users SET role='user' WHERE id=?", i.User)
	if code, _ := call(t, s, token, "GET", "admin/playback/settings", nil); code != 403 {
		t.Fatal("user may manage server limits")
	}
}
